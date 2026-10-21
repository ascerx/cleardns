package com.cleardns.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
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
 * blocking queries for blocked domains.
 *
 * Architecture (same trick used by well-known non-root DNS apps):
 *  - The TUN interface only receives traffic for the fake DNS IP (10.111.222.3).
 *  - Android hands apps the fake IP as their DNS server while the VPN is active.
 *  - DNS queries arrive as UDP/53 packets on the TUN; everything else is unaffected.
 */
class DnsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val doh = DohClient()
    private val pending = AtomicLong(0)

    private var tun: ParcelFileDescriptor? = null
    private val blockedCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        BlocklistManager.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpnInternal()
        scope.cancel()
        executor.shutdown()
        super.onDestroy()
    }

    private fun startVpn() {
        if (tun != null) return
        val fd = Builder()
            .setSession("ClearDNS")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute(DNS_IP, 32)
            .addDnsServer(DNS_IP)
            .setMtu(1500)
            .establish() ?: run {
            Log.e(TAG, "establish() returned null")
            VpnState.isRunning.value = false
            return
        }
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
            while (VpnState.isRunning.value) {
                val length = input.read(buffer)
                if (length < 20) continue
                val packet = buffer.copyOf(length)
                handlePacket(packet, output, fakeDnsIp)
            }
        } catch (e: Exception) {
            if (VpnState.isRunning.value) Log.w(TAG, "read loop ended: ${e.message}")
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream, fakeDnsIp: ByteArray) {
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (packet.size < ihl + 8) return
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        if (udpLen < 12) return
        val dnsLen = udpLen - 8
        val dnsQuery = packet.copyOfRange(ihl + 8, ihl + 8 + dnsLen)
        val srcIp = packet.copyOfRange(12, 16)

        pending.incrementAndGet()
        executor.execute {
            val response = resolveDns(dnsQuery)
            val result = response ?: return@execute
            try {
                val out = DnsProtocol.buildUdpV4(result, fakeDnsIp, srcIp, srcPort)
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

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun dnsIpBytes(): ByteArray = DNS_IP.split('.').map { it.toInt().toByte() }.toByteArray()

    companion object {
        private const val TAG = "DnsVpnService"
        private const val CHANNEL_ID = "status"
        private const val NOTIF_ID = 1
        const val TUN_ADDRESS = "10.111.222.1"
        const val DNS_IP = "10.111.222.3"
    }
}
