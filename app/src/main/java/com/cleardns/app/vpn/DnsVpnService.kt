package com.cleardns.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.DomainOpts
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.FlowSummary
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.Tunnel
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
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * ClearDNS VPN service.
 *
 *  - Mode "dns": narrow-route local VPN intercepting DNS (UDP/53) only; resolves
 *    via DoH (DohClient) and blocks blocked domains (fast path, no netstack).
 *  - Mode "firewall": full-tunnel VPN powered by Rethink's firestack engine
 *    (Intra.connect) with per-app firewall rules (block/bypass), connection
 *    monitoring (FlowListener) and DNS-level blocking (DNSListener).
 *
 * Implements gomobile's Bridge (FlowListener + DNSListener + ServerListener +
 * ProxyListener + Controller) exactly like Rethink's own VpnService.
 */
class DnsVpnService : VpnService(), Bridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)

    // DNS-only mode state
    private var doh: DohClient = DohClient()
    private var tun: ParcelFileDescriptor? = null
    private val blockedCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)
    private val logCounter = AtomicLong(0)

    // Firewall mode state
    private var tunnel: Tunnel? = null
    private var firewallTun: ParcelFileDescriptor? = null
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val ownerCache = HashMap<String, Int>(1024)
    private val ownerLock = Any()
    private val connLog = ConcurrentLinkedQueue<ConnLogEntry>()
    private val dnsLog = ConcurrentLinkedQueue<DnsLogEntry>()
    private val appStats = HashMap<Int, LongArray>(256)
    private val statsLock = Any()
    private val dnsIdCounter = AtomicLong(0)

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PAUSE -> {
                    VpnState.blockingEnabled.value = !VpnState.blockingEnabled.value
                    AppPrefs.putBoolean(context, AppPrefs.KEY_BLOCKING, VpnState.blockingEnabled.value)
                    refreshNotification()
                }
                ACTION_STOP -> {
                    VpnState.userStopRequested = true
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
        if (intent == null) {
            // System redelivery (START_STICKY). If the user asked to stop, stay stopped.
            if (VpnState.userStopRequested) {
                saveCounters()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // Service was killed by the system (OOM etc.): restore the VPN.
            startAsForeground()
            startVpn()
            return START_STICKY
        }
        startAsForeground()
        when (intent.action) {
            ACTION_RESTART -> {
                Log.i(TAG, "Restarting VPN (settings changed)")
                stopVpnInternal()
                startVpn()
            }
            else -> {
                VpnState.userStopRequested = false
                startVpn()
            }
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

    // ------------------------------------------------------------------ setup

    private fun startVpn() {
        val mode = VpnState.mode.value
        VpnState.skippedUids.value = AppPrefs.skippedUids(this)
        VpnState.appRules.value = AppPrefs.appRules(this)
        doh = DohClient(
            endpoint = VpnState.dohEndpoint.value.ifEmpty { DohClient.DEFAULT_ENDPOINT },
            protect = { socket -> protect(socket) },
        )
        if (mode == MODE_FIREWALL) startFirewallTunnel() else startDnsOnlyTunnel()
    }

    private fun disallowedPackages(): Set<String> {
        val pm = packageManager
        val packages = mutableSetOf<String>()
        for (uid in VpnState.appRules.value.filterValues { it == RULE_BYPASS }.keys +
            VpnState.skippedUids.value) {
            pm.getPackagesForUid(uid)?.let { packages.addAll(it) }
        }
        return packages
    }

    private fun baseBuilder(session: String): Builder {
        val builder = Builder()
            .setSession(session)
            .setMtu(1500)
        for (pkg in disallowedPackages()) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Could not skip pkg $pkg: ${e.message}")
            }
        }
        return builder
    }

    /** Full-tunnel: everything goes through firestack (gVisor netstack). */
    private fun startFirewallTunnel() {
        val fd = baseBuilder("ClearDNS Firewall")
            .addAddress(TUN_ADDRESS, 24)
            .addAddress("fd66:f83a:c650::1", 120)
            .addDnsServer(DNS_IP)
            .addDnsServer("fd66:f83a:c650::3")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .establish()
        if (fd == null) {
            Log.e(TAG, "firewall: establish() returned null")
            VpnState.isRunning.value = false
            return
        }
        firewallTun = fd
        try {
            val t = Intra.connect(
                fd.getFd().toLong(),
                1500L, // link mtu
                1500L, // tun mtu
                "$TUN_ADDRESS/24,fd66:f83a:c650::1/120",
                "$DNS_IP:53,fd66:f83a:c650::3:53",
                null, // DefaultDNS: added as DoH transport below
                this, // Bridge
            )
            tunnel = t
            val endpoint = VpnState.dohEndpoint.value.ifEmpty { DohClient.DEFAULT_ENDPOINT }
            Intra.addDoHTransport(t, "Preferred", endpoint, "")
            Intra.addDoHTransport(t, "Plus", DohClient.CLOUDFLARE_ENDPOINT, "")
            AppPrefs.putBoolean(applicationContext, AppPrefs.KEY_VPN_WAS_RUNNING, true)
            VpnState.isRunning.value = true
            Log.i(TAG, "firewall: firestack tunnel connected (endpoint=$endpoint)")
        } catch (e: Exception) {
            Log.e(TAG, "firewall: Intra.connect failed: ${e.message}", e)
            stopVpnInternal()
            VpnState.isRunning.value = false
        }
    }

    /** DNS-only: narrow route trick, only DNS enters the TUN. */
    private fun startDnsOnlyTunnel() {
        val fd = baseBuilder("ClearDNS")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute(DNS_IP, 32)
            .addDnsServer(DNS_IP)
            .establish()
        if (fd == null) {
            Log.e(TAG, "dns-only: establish() returned null")
            VpnState.isRunning.value = false
            return
        }
        Log.i(TAG, "dns-only: VPN established")
        tun = fd
        AppPrefs.putBoolean(applicationContext, AppPrefs.KEY_VPN_WAS_RUNNING, true)
        VpnState.isRunning.value = true
        scope.launch(Dispatchers.IO) { readLoop(fd) }
    }

    // ------------------------------------------------------------- dns-only loop

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(65535)
        val fakeDnsIp = dnsIpBytes()
        try {
            while (tun === fd && VpnState.isRunning.value) {
                val length = input.read(buffer)
                if (length < 20) continue
                handlePacket(buffer.copyOf(length), output, fakeDnsIp)
            }
        } catch (e: Exception) {
            if (tun === fd && VpnState.isRunning.value) Log.w(TAG, "read loop ended: ${e.message}")
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
        val dnsQuery = packet.copyOfRange(ihl + 8, ihl + 8 + udpLen - 8)
        val srcIp = packet.copyOfRange(12, 16)
        executor.execute {
            val response = resolveDns(dnsQuery) ?: return@execute
            try {
                val out = DnsProtocol.buildUdpV4(response, fakeDnsIp, srcIp, srcPort)
                synchronized(output) { output.write(out) }
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveDns(query: ByteArray): ByteArray? {
        val domain = DnsProtocol.extractDomain(query)
        val blocked = domain != null && VpnState.blockingEnabled.value && BlocklistManager.isBlocked(domain)
        if (blocked) {
            VpnState.queriesBlocked.value = blockedCount.incrementAndGet()
            VpnState.queriesTotal.value = totalCount.incrementAndGet()
            return DnsProtocol.buildNxDomain(query)
        }
        val response = doh.resolve(query) ?: return null
        VpnState.queriesTotal.value = totalCount.incrementAndGet()
        return response
    }

    // ---------------------------------------------------------------- Bridge

    override fun preflow(protocol: Int, uid: Int, src: String?, dst: String?): PreMark {
        val realUid = resolveUid(protocol, uid, src, dst)
        val pm = PreMark()
        pm.setUID(realUid.toString())
        pm.setIsUidSelf(realUid == android.os.Process.myUid())
        return pm
    }

    override fun flow(
        protocol: Int,
        uid: Int,
        src: String?,
        dst: String?,
        origdsts: String?,
        domains: String?,
        probableDomains: String?,
        blocklists: String?,
        isAlg: Boolean,
    ): Mark {
        val realUid = resolveUid(protocol, uid, src, dst)
        val appId = realUid % 100000
        val protoName = if (protocol == 6) "tcp" else if (protocol == 17) "udp" else "icmp"
        val appBlockRule = VpnState.blockingEnabled.value &&
            (VpnState.appRules.value[appId] == RULE_BLOCK || VpnState.appRules.value[realUid] == RULE_BLOCK)
        val dstIp = dst?.substringBeforeLast(':')?.trim('[', ']') ?: ""
        val ipBlocked = VpnState.blockingEnabled.value &&
            dstIp.isNotEmpty() && IpRules.matches(dstIp, VpnState.ipRules.value)
        val block = appBlockRule || ipBlocked

        val mark = Mark()
        mark.setPIDCSV(if (block) Backend.Block else Backend.Exit)
        mark.setCID(newConnId())
        mark.setUID(realUid.toString())

        // Monitor entry
        val entry = ConnLogEntry(
            cid = mark.getCID(),
            uid = realUid,
            proto = protoName,
            target = dst ?: "",
            domain = firstDomain(probableDomains, domains),
            blocked = block,
            startedAt = System.currentTimeMillis(),
        )
        synchronized(statsLock) {
            val s = appStats.getOrPut(appId) { LongArray(4) }
            s[0]++
            if (block) s[1]++
            appStats[appId] = s
        }
        pushLog(entry)

        if (block) Log.d(TAG, "FIREWALL: blocked ${appLabel(realUid)} -> $dst")
        return mark
    }

    override fun inflow(protocol: Int, uid: Int, src: String?, dst: String?): Mark {
        val mark = Mark()
        mark.setPIDCSV(Backend.Exit)
        mark.setCID(newConnId())
        mark.setUID(uid.toString())
        return mark
    }

    override fun flowing(m: Mark?) {
        // Final mark applied; nothing to do beyond logging.
    }

    override fun postflow(s: FlowSummary?) {
        if (s == null) return
        val uid = s.getUID()?.toIntOrNull() ?: return
        val appId = uid % 100000
        synchronized(statsLock) {
            val st = appStats.getOrPut(appId) { LongArray(4) }
            st[2] += s.getRx()
            st[3] += s.getTx()
            appStats[appId] = st
        }
        publishStats()
        updateLogEntry(s.getID(), s.getRx(), s.getTx(), s.getDuration())
    }

    // ------------------------------------------------------- DNS listener

    override fun onQuery(who: String?, uid: String?, domain: String?, qtyp: Long): DNSOpts? {
        if (domain == null) return null
        val realUid = uid?.toIntOrNull() ?: -1
        val appId = if (realUid < 0) -1 else realUid % 100000
        val appBlocked = appId >= 0 && VpnState.blockingEnabled.value &&
            (VpnState.appRules.value[appId] == RULE_BLOCK || VpnState.appRules.value[realUid] == RULE_BLOCK)
        val domainBlocked = VpnState.blockingEnabled.value && BlocklistManager.isBlocked(domain)
        if (!appBlocked && !domainBlocked) return null
        val opts = DNSOpts()
        opts.setUID(uid ?: "-1")
        opts.setIPCSV("")
        opts.setTIDCSV(Backend.BlockAll)
        opts.setTIDSECCSV("")
        return opts
    }

    override fun onPrequery(who: String?, uid: String?, domain: String?, qtyp: Long): DomainOpts? =
        null

    override fun onUpstreamAnswer(
        who: String?,
        smm: DNSSummary?,
        forPref: DNSOpts?,
        unmodifiedipcsv: String?,
    ): DNSOpts? = null

    override fun onResponse(smm: DNSSummary?) {
        if (smm == null) return
        val uid = smm.getUID()?.toIntOrNull() ?: -1
        val entry = DnsLogEntry(
            id = smm.getID() ?: ("q${dnsIdCounter.incrementAndGet()}"),
            uid = uid,
            domain = smm.getQName() ?: "",
            rcode = smm.getRCode().toInt(),
            cached = smm.getCached(),
            latencyMs = (smm.getLatency() * 1000).toLong(),
            timestamp = System.currentTimeMillis(),
        )
        if (entry.domain.isEmpty()) return
        dnsLog.offer(entry)
        while (dnsLog.size > MAX_LOG) dnsLog.poll()
        VpnState.dnsLog.value = dnsLog.toList()
    }

    // ---------------------------------------------- resolver/proxy/server listeners

    override fun onDNSAdded(id: String?) {}
    override fun onDNSRemoved(id: String?) {}
    override fun onDNSStopped() {}

    override fun svcRoute(
        sid: String?,
        pid: String?,
        network: String?,
        sipport: String?,
        dipport: String?,
    ): Tab {
        val tab = Tab()
        tab.setCID(newConnId())
        tab.setBlock(false)
        return tab
    }

    override fun onSvcComplete(s: ServerSummary?) {}

    override fun onProxyAdded(id: String?, handle: String?) {}
    override fun onProxyRemoved(id: String?, handle: String?) {}
    override fun onProxyUpdated(id: String?, handle: String?) {}
    override fun onProxyStopped(id: String?, handle: String?) {}
    override fun onProxiesStopped() {}

    // -------------------------------------------------------------- controller

    override fun bind4(who: String?, addrport: String?, fd: Long) {}
    override fun bind6(who: String?, addrport: String?, fd: Long) {}

    override fun protect(who: String?, fd: Long) {
        try {
            protect(fd.toInt()) // VpnService.protect(int)
        } catch (e: Exception) {
            Log.w(TAG, "protect($fd) failed: ${e.message}")
        }
    }

    // --------------------------------------------------------------- helpers

    private fun resolveUid(protocol: Int, uid: Int, src: String?, dst: String?): Int {
        if (uid >= 0) return uid
        if (protocol != 6 && protocol != 17) return -1
        val srcAddr = parseAddrPort(src) ?: return -1
        val dstAddr = parseAddrPort(dst) ?: return -1
        val key = "$protocol|$srcAddr|$dstAddr"
        synchronized(ownerLock) { ownerCache[key]?.let { return it } }
        val found = try {
            cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
        } catch (_: Exception) {
            -1
        }
        var result = found
        if (result <= 0 && protocol == 17) {
            // UDP is connectionless: retry with local port 0 then wildcard remote.
            result = try {
                cm.getConnectionOwnerUid(17, InetSocketAddress(srcAddr.address, 0), dstAddr)
            } catch (_: Exception) {
                -1
            }
        }
        synchronized(ownerLock) {
            if (ownerCache.size > 4096) ownerCache.clear()
            ownerCache[key] = result
        }
        return result
    }

    private fun parseAddrPort(s: String?): InetSocketAddress? {
        if (s == null) return null
        return try {
            val trimmed = s.trim()
            if (trimmed.contains(']')) {
                // "[v6]:port"
                val addr = trimmed.substringBefore(']').removePrefix("[")
                val port = trimmed.substringAfterLast(':').toInt()
                InetSocketAddress(addr, port)
            } else {
                val idx = trimmed.lastIndexOf(':')
                if (idx == -1) InetSocketAddress(trimmed, 0)
                else InetSocketAddress(trimmed.substring(0, idx), trimmed.substring(idx + 1).toInt())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstDomain(vararg csvs: String?): String {
        for (csv in csvs) {
            val first = csv?.split(',')?.firstOrNull { it.isNotBlank() }
            if (!first.isNullOrBlank()) return first.trim()
        }
        return ""
    }

    private fun appLabel(uid: Int): String {
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun newConnId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    private fun pushLog(entry: ConnLogEntry) {
        connLog.offer(entry)
        while (connLog.size > MAX_LOG) connLog.poll()
        publishLog()
    }

    private fun updateLogEntry(cid: String, rx: Long, tx: Long, duration: Long) {
        connLog.firstOrNull { it.cid == cid }?.let {
            it.rx = rx
            it.tx = tx
            it.durationMs = duration
            it.ended = true
        }
        publishLog()
    }

    private fun publishLog() {
        VpnState.connLog.value = connLog.toList()
    }

    private fun publishStats() {
        synchronized(statsLock) {
            VpnState.appStats.value = HashMap(appStats)
        }
    }

    private fun stopVpnInternal() {
        AppPrefs.putBoolean(applicationContext, AppPrefs.KEY_VPN_WAS_RUNNING, false)
        VpnState.isRunning.value = false
        try {
            tunnel?.disconnect()
        } catch (_: Exception) {
        }
        tunnel = null
        firewallTun?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        firewallTun = null
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
                .addAction(0, if (blocking) "Pause blocking" else "Resume blocking", pauseIntent)
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
        private const val MAX_LOG = 400
        const val TUN_ADDRESS = "10.111.222.1"
        const val DNS_IP = "10.111.222.3"
        const val ACTION_PAUSE = "com.cleardns.app.PAUSE"
        const val ACTION_STOP = "com.cleardns.app.STOP"
        const val ACTION_RESTART = "com.cleardns.app.RESTART"
        const val MODE_DNS = "dns"
        const val MODE_FIREWALL = "firewall"
        const val RULE_BYPASS = "bypass"
        const val RULE_BLOCK = "block"
    }
}
