package com.cleardns.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.cleardns.app.AppPrefs
import com.cleardns.app.MainActivity
import com.cleardns.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local VPN that intercepts DNS queries and resolves them over HTTPS (DoH),
 * blocking queries for blocked domains. Selected apps can be excluded from
 * the VPN entirely (they keep using the system resolver).
 */
class DnsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val doh = DohClient(protect = { socket -> protect(socket) })
    private val pending = AtomicLong(0)

    private var tun: ParcelFileDescriptor? = null
    private val blockedCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)
    private val logCounter = AtomicLong(0)

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PAUSE -> {
                    VpnState.blockingEnabled.value = !VpnState.blockingEnabled.value
                    AppPrefs.putBoolean(context, AppPrefs.KEY_BLOCKING, VpnState.blockingEnabled.value)
                    refreshNotification()
                }
                ACTION_STOP -> {
                    saveCounters()
                    ServiceCompat.stopForeground(this@DnsVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        BlocklistManager.load(this)
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
        }
        registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_RESTART -> {
                Log.i(TAG, "Restarting VPN (app skip list changed)")
                stopVpnInternal()
                startVpn()
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(notificationReceiver)
        saveCounters()
        stopVpnInternal()
        scope.cancel()
        executor.shutdown()
        super.onDestroy()
    }

    private fun startVpn() {
        if (tun != null) return
        val builder = Builder()
            .setSession("ClearDNS")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute(DNS_IP, 32)
            .addDnsServer(DNS_IP)
            .setMtu(1500)

        // Apps excluded from the VPN (Rethink-style "skip apps"): they bypass
        // ClearDNS completely and keep using the system's DNS.
        val skipped = VpnState.skippedUids.value
        val pm = packageManager
        var excluded = 0
        for (uid in skipped) {
            for (pkg in pm.getPackagesForUid(uid) ?: emptyArray()) {
                try {
                    builder.addDisallowedApplication(pkg)
                    excluded++
                } catch (e: Exception) {
                    Log.w(TAG, "Could not skip pkg $pkg: ${e.message}")
                }
            }
        }
        if (excluded > 0) Log.i(TAG, "Excluded $excluded packages from the VPN")

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "establish() returned null")
            VpnState.isRunning.value = false
            return
        }
        Log.i(TAG, "VPN established: tun addr $TUN_ADDRESS, dns $DNS_IP")
        tun = fd
        VpnState.isRunning.value = true
        scope.launch(Dispatchers.IO) { readLoop(fd) }
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(65535)
        val fakeDnsIp = dnsIpBytes()
        try {
            while (tun === fd && VpnState.isRunning.value) {
                val length = input.read(buffer)
                if (length < 20) continue
                val packet = buffer.copyOf(length)
                handlePacket(packet, output, fakeDnsIp)
            }
        } catch (e: Exception) {
            if (tun === fd && VpnState.isRunning.value) Log.w(TAG, "read loop ended: ${e.message}")
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream, fakeDnsIp: ByteArray) {
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) {
            logOnce("drop: not IPv4 (v$version)")
            return
        }
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) {
            logOnce("drop: proto $protocol (no UDP)")
            return
        }
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (packet.size < ihl + 8) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) {
            logOnce("drop: udp port $dstPort != 53")
            return
        }
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        if (udpLen < 12) return
        val dnsLen = udpLen - 8
        val dnsQuery = packet.copyOfRange(ihl + 8, ihl + 8 + dnsLen)
        val srcIp = packet.copyOfRange(12, 16)

        logOnce("DNS query received: ${DnsProtocol.extractDomain(dnsQuery)} ($dnsLen bytes)")
        pending.incrementAndGet()
        executor.execute {
            val response = resolveDns(dnsQuery)
            if (response == null) {
                Log.w(TAG, "DoH failed for ${DnsProtocol.extractDomain(dnsQuery)}")
                return@execute
            }
            try {
                val out = DnsProtocol.buildUdpV4(response, fakeDnsIp, srcIp, srcPort)
                synchronized(output) { output.write(out) }
            } catch (_: Exception) {
                // TUN may have closed while we were resolving; ignore.
            }
        }
    }

    private fun resolveDns(query: ByteArray): ByteArray? {
        val domain = DnsProtocol.extractDomain(query)
        val blocked = domain != null && VpnState.blockingEnabled.value && BlocklistManager.isBlocked(domain)
        if (blocked) {
            totalCount.incrementAndGet()
            val count = blockedCount.incrementAndGet()
            VpnState.queriesTotal.value = totalCount.get()
            VpnState.queriesBlocked.value = count
            Log.d(TAG, "BLOCKED: $domain")
            return DnsProtocol.buildNxDomain(query)
        }
        val response = doh.resolve(query) ?: return null
        totalCount.incrementAndGet()
        VpnState.queriesTotal.value = totalCount.get()
        return response
    }

    private fun stopVpnInternal() {
        VpnState.isRunning.value = false
        tun?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        tun = null
    }

    private fun saveCounters() {
        AppPrefs.putLong(applicationContext, AppPrefs.KEY_QUERIES_TOTAL, totalCount.get())
        AppPrefs.putLong(applicationContext, AppPrefs.KEY_QUERIES_BLOCKED, blockedCount.get())
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        refreshNotification()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val blocking = VpnState.blockingEnabled.value
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseTitle = if (blocking) "Pause blocking" else "Resume blocking"
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(
                    if (blocking) getString(R.string.notif_title)
                    else getString(R.string.notif_title_paused),
                )
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(0, pauseTitle, pauseIntent)
                .addAction(0, "Stop", stopIntent)
                .build()
        nm.notify(NOTIF_ID, notification)
    }

    private fun logOnce(message: String) {
        if (logCounter.incrementAndGet() <= 25) Log.d(TAG, "#${logCounter.get()}: $message")
    }

    private fun dnsIpBytes(): ByteArray = DNS_IP.split('.').map { it.toInt().toByte() }.toByteArray()

    companion object {
        private const val TAG = "DnsVpnService"
        private const val CHANNEL_ID = "status"
        private const val NOTIF_ID = 1
        const val TUN_ADDRESS = "10.111.222.1"
        const val DNS_IP = "10.111.222.3"
        const val ACTION_PAUSE = "com.cleardns.app.PAUSE"
        const val ACTION_STOP = "com.cleardns.app.STOP"
        const val ACTION_RESTART = "com.cleardns.app.RESTART"
    }
}
