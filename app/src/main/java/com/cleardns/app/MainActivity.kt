package com.cleardns.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.cleardns.app.vpn.BlocklistManager
import com.cleardns.app.vpn.ConnLogEntry
import com.cleardns.app.vpn.DnsVpnService
import com.cleardns.app.vpn.DohClient
import com.cleardns.app.vpn.StatsStore
import com.cleardns.app.vpn.VpnState
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ---------------- Design tokens (neon techy) ----------------

private val Bg0 = Color(0xFF060A13)
private val Bg1 = Color(0xFF0B1122)
private val Bg2 = Color(0xFF111A33)
private val Neon = Color(0xFF00E5FF)
private val Violet = Color(0xFF8B5CF6)
private val Pink = Color(0xFFF472B6)
private val Green = Color(0xFF34D399)
private val Red = Color(0xFFF87171)
private val Amber = Color(0xFFFBBF24)
private val CardBg = Color(0xFF101A30).copy(alpha = 0.92f)
private val CardBorder = Color(0xFF24345C)
private val TextPrimary = Color(0xFFE8F0FF)
private val TextMuted = Color(0xFF8296B8)

private val GlowBrush = Brush.verticalGradient(listOf(Neon, Violet))
private val DangerBrush = Brush.verticalGradient(listOf(Red, Pink))
private val CardShape = RoundedCornerShape(18.dp)

@Composable
private fun NeonCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier
        .fillMaxWidth()
        .border(1.dp, CardBorder, CardShape)
        .clip(CardShape)
    Card(
        modifier = if (onClick != null) m.clickable(onClick = onClick) else m,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String, accent: Color = Neon) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp,
        color = accent,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

class MainActivity : ComponentActivity() {

    private var rewardedAd: RewardedAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VpnState.blockingEnabled.value = AppPrefs.getBoolean(this, AppPrefs.KEY_BLOCKING, true)
        VpnState.adFreeUntil.value = AppPrefs.getLong(this, AppPrefs.KEY_AD_FREE_UNTIL, 0L)
        VpnState.queriesTotal.value = AppPrefs.getLong(this, AppPrefs.KEY_QUERIES_TOTAL, 0L)
        VpnState.queriesBlocked.value = AppPrefs.getLong(this, AppPrefs.KEY_QUERIES_BLOCKED, 0L)
        VpnState.skippedUids.value = AppPrefs.skippedUids(this)
        VpnState.appRules.value = AppPrefs.appRules(this)
        VpnState.ipRules.value = AppPrefs.ipRules(this)
        VpnState.customBlocklist.value = AppPrefs.customDomains(this)
        VpnState.whitelist.value = AppPrefs.whitelist(this)
        VpnState.selectedLists.value = AppPrefs.listIds(this)
        VpnState.dohEndpoint.value = AppPrefs.endpoint(this, DohClient.DEFAULT_ENDPOINT)
        VpnState.mode.value = AppPrefs.mode(this, DnsVpnService.MODE_DNS)
        BlocklistManager.load(this)

        // Wait for AdMob init before loading any ad (prevents main-thread class-lock
        // contention with MobileAds.initialize).
        lifecycleScope.launch {
            ClearDnsApp.adsInitialized.first { it }
            withContext(Dispatchers.Main.immediate) { loadRewardedAd() }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Neon,
                    secondary = Violet,
                    tertiary = Pink,
                    surface = CardBg,
                    background = Bg0,
                    onPrimary = Bg0,
                    onSurface = TextPrimary,
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg0) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Bg0, Bg1, Bg2))),
                    ) {
                        AppRoot(
                            onToggle = { onToggleProtection() },
                            onWatchAd = { showRewardedAd() },
                        )
                    }
                }
            }
        }
    }

    private fun onToggleProtection() {
        Log.d("ClearDnsUI", "onToggle called: isRunning=${VpnState.isRunning.value}")
        syncServiceState()
        Log.d("ClearDnsUI", "after sync: isRunning=${VpnState.isRunning.value}")
        if (VpnState.isRunning.value) {
            VpnState.userStopRequested = true
            stopService(Intent(this, DnsVpnService::class.java))
            VpnState.isRunning.value = false
            saveCounters()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (VpnService.prepare(this) == null) {
            Log.d("ClearDnsUI", "prepare ok -> startService")
            startService(Intent(this, DnsVpnService::class.java))
        } else {
            startActivityForResult(VpnService.prepare(this), REQUEST_VPN)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == Activity.RESULT_OK) {
            startService(Intent(this, DnsVpnService::class.java))
        }
    }

    private fun loadRewardedAd() {
        RewardedAd.load(
            this,
            BuildConfig.AD_UNIT_REWARDED,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                }
            },
        )
    }

    private fun showRewardedAd() {
        val ad = rewardedAd
        if (ad == null) {
            loadRewardedAd()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewardedAd()
            }
        }
        ad.show(this) {
            VpnState.adFreeUntil.value = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)
            AppPrefs.putLong(this@MainActivity, AppPrefs.KEY_AD_FREE_UNTIL, VpnState.adFreeUntil.value)
        }
    }

    private fun saveCounters() {
        AppPrefs.putLong(this, AppPrefs.KEY_QUERIES_TOTAL, VpnState.queriesTotal.value)
        AppPrefs.putLong(this, AppPrefs.KEY_QUERIES_BLOCKED, VpnState.queriesBlocked.value)
    }

    override fun onResume() {
        super.onResume()
        syncServiceState()
    }

    private fun syncServiceState() {
        val running = try {
            getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == DnsVpnService::class.java.name }
        } catch (_: Exception) {
            VpnState.isRunning.value
        }
        VpnState.isRunning.value = running
    }

    override fun onPause() {
        saveCounters()
        super.onPause()
    }

    companion object {
        private const val REQUEST_VPN = 100
    }
}

// ================= App root: bottom navigation =================

@Composable
private fun AppRoot(onToggle: () -> Unit, onWatchAd: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A101F)) {
                val items = listOf(
                    Triple(Icons.Filled.Shield, "Home", 0),
                    Triple(Icons.Filled.Tune, "Firewall", 1),
                    Triple(Icons.Filled.Visibility, "Monitor", 2),
                    Triple(Icons.Filled.Public, "DNS", 3),
                    Triple(Icons.Filled.Settings, "Settings", 4),
                )
                items.forEach { (icon, label, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, null, tint = if (tab == index) Neon else TextMuted) },
                        label = {
                            Text(label, color = if (tab == index) Neon else TextMuted, fontSize = 10.sp)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Neon.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(bottom = inner.calculateBottomPadding())) {
            when (tab) {
                0 -> HomeTab(onToggle = onToggle, onWatchAd = onWatchAd, onGoTo = { tab = it })
                1 -> FirewallTab()
                2 -> MonitorTab()
                3 -> DnsTab()
                4 -> SettingsTab(onWatchAd = onWatchAd)
            }
        }
    }
}

// ================= HOME =================

@Composable
private fun HomeTab(onToggle: () -> Unit, onWatchAd: () -> Unit, onGoTo: (Int) -> Unit) {
    val isRunning by VpnState.isRunning.collectAsState()
    val blocking by VpnState.blockingEnabled.collectAsState()
    val total by VpnState.queriesTotal.collectAsState()
    val blocked by VpnState.queriesBlocked.collectAsState()
    val mode by VpnState.mode.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()
    val adFreeUntil by VpnState.adFreeUntil.collectAsState()

    val pulse = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = if (isRunning) 0.85f else 0.2f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "glow",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .border(10.dp, Neon.copy(alpha = glowAlpha * 0.4f), androidx.compose.foundation.shape.CircleShape)
                    .border(2.dp, if (isRunning) Neon else TextMuted, androidx.compose.foundation.shape.CircleShape),
            )
            Icon(
                imageVector = if (isRunning) Icons.Filled.GppGood else Icons.Filled.GppBad,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = if (isRunning) Neon else TextMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isRunning) "Protected" else "Protection off",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
        )
        Text(
            text = when {
                isRunning && blocking && mode == DnsVpnService.MODE_FIREWALL -> "DNS + Firewall · full tunnel"
                isRunning && blocking -> "Encrypted DNS · tracker blocking"
                isRunning -> "Blocking paused"
                else -> "Tap start to shield your device"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (isRunning && !blocking) Amber else TextMuted,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isRunning) DangerBrush else GlowBrush)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (isRunning) "STOP" else "START",
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
                color = Bg0,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Queries", formatCount(total), Neon, Modifier.weight(1f))
            StatCard("Blocked", formatCount(blocked), Pink, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        NeonCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Block trackers & ads", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    if (refreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = Neon,
                            trackColor = Bg2,
                        )
                    }
                }
                Switch(
                    checked = blocking,
                    onCheckedChange = {
                        VpnState.blockingEnabled.value = it
                        AppPrefs.putBoolean(context, AppPrefs.KEY_BLOCKING, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Neon, checkedThumbColor = Bg0),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuRow(Icons.Filled.Bolt, "Firewall", "Per-app internet access rules", Violet) { onGoTo(1) }
        MenuRow(Icons.Filled.Visibility, "Network monitor", "Connections, DNS log, IP rules", Neon) { onGoTo(2) }
        MenuRow(Icons.Filled.Public, "DNS settings", "Blocklists, resolvers, domain rules", Green) { onGoTo(3) }

        Spacer(Modifier.height(12.dp))
        val adsHidden = System.currentTimeMillis() < adFreeUntil
        if (!adsHidden) {
            TextButton(onClick = onWatchAd) {
                Text("Remove ads for 24 hours (watch video)", color = TextMuted)
            }
            BannerAd()
        } else {
            Text("Ads removed — thanks!", color = Green, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(52.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.border(1.dp, CardBorder, CardShape).clip(CardShape),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    NeonCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Text("›", color = TextMuted, fontSize = 20.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun BannerAd() {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        factory = { context ->
            AdView(context).apply {
                adUnitId = BuildConfig.AD_UNIT_BANNER
                setAdSize(AdSize.BANNER)
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

// ================= FIREWALL =================

private data class AppEntry(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Bitmap,
)

@Composable
private fun FirewallTab() {
    val context = LocalContext.current
    val rules by VpnState.appRules.collectAsState()
    val isRunning by VpnState.isRunning.collectAsState()
    val mode by VpnState.mode.collectAsState()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var editingIsolate by remember { mutableStateOf<Int?>(null) }
    var filter by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = runCatching {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }.getOrDefault(emptyList())
            packages
                .filter { it.requestedPermissions?.any { p -> p == android.Manifest.permission.INTERNET } == true }
                .filter { it.packageName != context.packageName }
                .mapNotNull { pkg ->
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    AppEntry(
                        uid = appInfo.uid,
                        packageName = pkg.packageName,
                        label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg.packageName },
                        icon = drawableToBitmap(pm.getApplicationIcon(appInfo)),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
        loading = false
    }

    fun setRule(uid: Int, rule: String?) {
        val current = rules.toMutableMap()
        if (rule == null) current.remove(uid) else current[uid] = rule
        VpnState.appRules.value = current
        AppPrefs.setAppRules(context, current)
        // Only bypass needs a VPN rebuild (disallowed list); wifi/mobile/isolate
        // blocks are evaluated per-connection at runtime.
        val needsRestart = isRunning && (rule == "bypass" || rules[uid] == "bypass")
        if (needsRestart) {
            context.startService(
                Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RESTART),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Firewall",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
        if (mode != DnsVpnService.MODE_FIREWALL) {
            NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Requires DNS + Firewall mode", color = Amber, fontWeight = FontWeight.SemiBold)
                Text(
                    "Blocking apps and monitoring connections only work in full-tunnel mode. Switch mode in Home and restart the VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps…", color = TextMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Neon,
                unfocusedBorderColor = CardBorder,
                cursorColor = Neon,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "all" to "All",
                "block" to "Blocked",
                "bypass" to "Bypassed",
                "filter" to "Filtered",
            ).forEach { (id, label) ->
                FilterChip(
                    selected = filter == id,
                    onClick = { filter = id },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Neon.copy(alpha = 0.15f),
                        selectedLabelColor = Neon,
                    ),
                )
            }
        }
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Neon)
            }
        } else {
            val visible = apps.filter { app ->
                val matchesQuery = app.label.contains(query, true) || app.packageName.contains(query, true)
                val matchesFilter = when (filter) {
                    "block" -> rules[app.uid] == "block"
                    "bypass" -> rules[app.uid] == "bypass"
                    "filter" -> rules[app.uid] == null
                    else -> true
                }
                matchesQuery && matchesFilter
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    val rule = rules[app.uid]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selected = app }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 15.sp, color = TextPrimary, maxLines = 1)
                            Text(app.packageName, fontSize = 10.sp, color = TextMuted, maxLines = 1)
                        }
                        // Quick WiFi/Data block toggles (like Rethink)
                        QuickRuleIcon(
                            icon = Icons.Filled.Wifi,
                            active = rule == "block_wifi",
                            tint = Red,
                            contentDesc = "Block WiFi",
                        ) {
                            val next = if (rule == "block_wifi") null else "block_wifi"
                            setRule(app.uid, next)
                        }
                        Spacer(Modifier.width(2.dp))
                        QuickRuleIcon(
                            icon = Icons.Filled.SignalCellularAlt,
                            active = rule == "block_mobile",
                            tint = Pink,
                            contentDesc = "Block mobile data",
                        ) {
                            val next = if (rule == "block_mobile") null else "block_mobile"
                            setRule(app.uid, next)
                        }
                        Spacer(Modifier.width(10.dp))
                        StateBadge(rule)
                    }
                }
            }
        }
    }

    selected?.let { app ->
        RulePickerDialog(
            label = app.label,
            current = rules[app.uid],
            onPick = { rule ->
                setRule(app.uid, rule)
                if (rule == "isolate") editingIsolate = app.uid
                selected = null
            },
            onDismiss = { selected = null },
        )
    }

    editingIsolate?.let { uid ->
        IsolateDomainsDialog(
            uid = uid,
            onDone = { editingIsolate = null },
        )
    }
}

@Composable
private fun QuickRuleIcon(
    icon: ImageVector,
    active: Boolean,
    tint: Color,
    contentDesc: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDesc,
        tint = if (active) tint else Color(0xFF4A5878),
        modifier = Modifier
            .size(24.dp)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun IsolateDomainsDialog(uid: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val all by VpnState.isolateDomains.collectAsState()
    val current = all[uid] ?: emptySet()
    var input by remember { mutableStateOf("") }
    val appName = remember(uid) {
        try {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0),
                ).toString()
            } ?: "uid $uid"
        } catch (_: Exception) {
            "uid $uid"
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Isolate: allowed domains", color = TextPrimary) },
        text = {
            Column {
                Text(
                    "\"$appName\" may ONLY reach these domains (and subdomains). Empty list = all traffic blocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                AddDomainRow(input, { input = it }, Neon) {
                    val d = input.trim().lowercase().trimEnd('.')
                    if (d.contains('.') && d.none { it.isWhitespace() }) {
                        val next = all.toMutableMap()
                        next[uid] = current + d
                        VpnState.isolateDomains.value = next
                        AppPrefs.setIsolateDomains(context, next)
                        input = ""
                    }
                }
                current.sorted().forEach { domain ->
                    RuleRow(domain) {
                        val next = all.toMutableMap()
                        next[uid] = current - domain
                        VpnState.isolateDomains.value = next
                        AppPrefs.setIsolateDomains(context, next)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done", color = Neon) } },
        containerColor = Color(0xFF0D1526),
    )
}

@Composable
private fun StateBadge(rule: String?) {
    val (text, color) = when (rule) {
        "block" -> "BLOCKED" to Red
        "bypass" -> "BYPASS" to Amber
        else -> "FILTERED" to Neon
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun RulePickerDialog(label: String, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label, color = TextPrimary) },
        text = {
            Column {
                RuleOption("Filtered", "Normal internet + ClearDNS filtering", Neon, current == null) { onPick(null) }
                RuleOption("Block WiFi", "No access on WiFi / unmetered", Violet, current == "block_wifi") { onPick("block_wifi") }
                RuleOption("Block mobile data", "No access on cellular / metered", Pink, current == "block_mobile") { onPick("block_mobile") }
                RuleOption("Block internet", "Cut all network access", Red, current == "block") { onPick("block") }
                RuleOption("Bypass", "App skips ClearDNS entirely (system DNS)", Amber, current == "bypass") { onPick("bypass") }
                RuleOption("Isolate", "Only its own allowed domains work", Green, current == "isolate") { onPick("isolate") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) } },
        containerColor = Color(0xFF0D1526),
    )
}

@Composable
private fun RuleOption(title: String, desc: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) color else CardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        if (selected) Text("✓", color = color, fontWeight = FontWeight.Black)
    }
    Spacer(Modifier.height(8.dp))
}

// ================= MONITOR =================

@Composable
private fun MonitorTab() {
    val context = LocalContext.current
    val entries by VpnState.connLog.collectAsState()
    val dnsEntries by VpnState.dnsLog.collectAsState()
    val stats by VpnState.appStats.collectAsState()
    val ipRules by VpnState.ipRules.collectAsState()
    var subTab by remember { mutableStateOf(0) }
    var labels by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var ipInput by remember { mutableStateOf("") }
    var connFilter by remember { mutableStateOf("all") }

    val allUids = (stats.keys + entries.map { it.uid % 100000 } + dnsEntries.map { it.uid % 100000 }).toSet()
    LaunchedEffect(allUids.size) {
        if (allUids.isEmpty()) return@LaunchedEffect
        labels = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            allUids.associateWith { uid ->
                try {
                    val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@associateWith "uid $uid"
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    "uid $uid"
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Monitor",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
        TabRow(
            selectedTabIndex = subTab,
            containerColor = Color.Transparent,
            contentColor = Neon,
        ) {
            listOf("Connections", "DNS log", "IP rules", "Reports").forEachIndexed { i, label ->
                Tab(
                    selected = subTab == i,
                    onClick = { subTab = i },
                    text = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        when (subTab) {
            0 -> {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "blocked" to "Blocked", "allowed" to "Allowed").forEach { (id, label) ->
                        FilterChip(
                            selected = connFilter == id,
                            onClick = { connFilter = id },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Neon.copy(alpha = 0.15f),
                                selectedLabelColor = Neon,
                            ),
                        )
                    }
                }
                val visible = entries.filter {
                    when (connFilter) {
                        "blocked" -> it.blocked
                        "allowed" -> !it.blocked
                        else -> true
                    }
                }
                if (visible.isEmpty()) {
                    EmptyHint(
                        Icons.Filled.Visibility,
                        "No connections yet.\nEnable DNS + Firewall mode and start the VPN.",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        items(visible.asReversed(), key = { it.cid }) { e ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(
                                        1.dp,
                                        if (e.blocked) Red.copy(alpha = 0.5f) else CardBorder,
                                        RoundedCornerShape(12.dp),
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = CardBg),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            labels[e.uid % 100000] ?: "uid ${e.uid % 100000}",
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (e.blocked) Red else TextPrimary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                        )
                                        Text(e.proto.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    }
                                    val shown = if (e.domain.isNotEmpty() && e.domain != e.target)
                                        "${e.domain}  →  ${e.target}" else e.target
                                    Text(shown, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                                    if (e.rx + e.tx > 0) {
                                        Text(
                                            "↑ ${formatCount(e.tx)}   ↓ ${formatCount(e.rx)}",
                                            fontSize = 11.sp,
                                            color = Neon,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (dnsEntries.isEmpty()) {
                    EmptyHint(Icons.Filled.Public, "No DNS queries logged yet.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        items(dnsEntries.asReversed(), key = { it.id + it.timestamp }) { d ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.domain, fontSize = 14.sp, color = TextPrimary, maxLines = 1)
                                    Text(
                                        "${labels[d.uid % 100000] ?: "uid ${d.uid % 100000}"} · ${if (d.cached) "cached" else "${d.latencyMs}ms"}",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                    )
                                }
                                val blockedDns = d.rcode != 0
                                Text(
                                    if (blockedDns) "NXDOMAIN" else "OK",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (blockedDns) Pink else Green,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            HorizontalDivider(color = Color(0xFF16203A))
                        }
                    }
                }
            }
            2 -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        placeholder = { Text("IP or CIDR (1.2.3.4 or 10.0.0.0/8)", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Red,
                            unfocusedBorderColor = CardBorder,
                            cursorColor = Red,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        val rule = ipInput.trim()
                        if (rule.contains('.') || rule.contains(':')) {
                            VpnState.ipRules.value = ipRules + rule
                            AppPrefs.setIpRules(context, ipRules + rule)
                            ipInput = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, null, tint = Red)
                    }
                }
                if (ipRules.isEmpty()) {
                    EmptyHint(Icons.Filled.Block, "No IP rules. Add an IP or CIDR to block it for all apps.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        items(ipRules.sorted(), key = { it }) { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(rule, color = TextPrimary, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    VpnState.ipRules.value = ipRules - rule
                                    AppPrefs.setIpRules(context, ipRules - rule)
                                }) {
                                    Icon(Icons.Filled.Delete, null, tint = Red)
                                }
                            }
                        }
                    }
                }
            }
            3 -> ReportsTab(labels)
        }
    }
}

@Composable
private fun ReportsTab(labels: Map<Int, String>) {
    val stats by VpnState.appStats.collectAsState()
    val hourly = remember(stats) { StatsStore.hourlyForDay() }
    val daily = remember(stats) { StatsStore.dailyLast7() }
    val top = remember(stats) { StatsStore.topAppsToday() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionLabel("Today by hour", Neon)
        val maxVal = hourly.maxOf { it[0] }.coerceAtLeast(1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0C1424))
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            hourly.forEachIndexed { hour, h ->
                val frac = h[0].toFloat() / maxVal
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    if (h[1] > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(((h[1].toFloat() / maxVal) * 40).dp.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(Pink),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((frac * 70).dp.coerceAtLeast(if (h[0] > 0) 3.dp else 1.dp))
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (h[0] > 0) Brush.verticalGradient(listOf(Neon, Violet))
                                else Brush.verticalGradient(listOf(CardBorder, CardBorder)),
                            ),
                    )
                }
            }
        }
        Text(
            "24h — blue: connections · pink: blocked",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel("Last 7 days", Violet)
        daily.forEach { (day, arr) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(day, fontSize = 13.sp, color = TextMuted, modifier = Modifier.width(90.dp))
                Text(
                    "${formatCount(arr[0])} conns · ↓↑ ${formatCount(arr[2] + arr[3])}B · ${formatCount(arr[1])} blocked",
                    fontSize = 12.sp,
                    color = TextPrimary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Top apps today", Pink)
        if (top.isEmpty()) {
            Text(
                "No data yet — connect in DNS + Firewall mode.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        } else {
            top.entries.sortedByDescending { it.value[0] }.take(10).forEach { (uid, arr) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        labels[uid] ?: "uid $uid",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        "${formatCount(arr[0])} conns · ↓ ${formatCount(arr[2])} ↑ ${formatCount(arr[3])}",
                        fontSize = 11.sp,
                        color = TextMuted,
                    )
                    if (arr[1] > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${formatCount(arr[1])}✕",
                            style = MaterialTheme.typography.labelSmall,
                            color = Red,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(40.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// ================= DNS tab =================

private val DNS_OPTIONS = listOf(
    "Google" to DohClient.DEFAULT_ENDPOINT,
    "Cloudflare" to DohClient.CLOUDFLARE_ENDPOINT,
    "Quad9" to "https://quad9.net/dns-query",
    "AdGuard" to "https://dns.adguard-dns.com/dns-query",
)

@Composable
private fun DnsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by VpnState.selectedLists.collectAsState()
    val endpoint by VpnState.dohEndpoint.collectAsState()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()
    val progress by BlocklistManager.progress.collectAsState()
    val error by BlocklistManager.lastError.collectAsState()
    val customDomains by VpnState.customBlocklist.collectAsState()
    val whitelist by VpnState.whitelist.collectAsState()
    var blockInput by remember { mutableStateOf("") }
    var allowInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
    ) {
        Text(
            "DNS",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        SectionLabel("Blocklists")
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            BlocklistManager.AVAILABLE.forEach { list ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = list.id in selected,
                        onCheckedChange = { checked ->
                            val next = if (checked) selected + list.id else selected - list.id
                            VpnState.selectedLists.value = next
                            AppPrefs.setListIds(context, next)
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Neon),
                    )
                    Text(list.label, fontSize = 13.sp, color = TextPrimary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { scope.launch { BlocklistManager.downloadAndMerge(context, selected) } },
                enabled = !refreshing && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Bg0),
            ) {
                Text(if (refreshing) (progress.ifEmpty { "Working…" }) else "Download & merge")
            }
            error?.let {
                Text("Last error: $it", style = MaterialTheme.typography.labelSmall, color = Red, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("Encrypted resolver (DoH)", Violet)
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            DNS_OPTIONS.forEach { (label, url) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = endpoint == url,
                        onClick = {
                            VpnState.dohEndpoint.value = url
                            AppPrefs.setEndpoint(context, url)
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Neon),
                    )
                    Text(label, color = TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("Always-block domains", Pink)
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            AddDomainRow(blockInput, { blockInput = it }, Neon) {
                val d = blockInput.trim().lowercase().trimEnd('.')
                if (d.contains('.') && d.none { it.isWhitespace() }) {
                    VpnState.customBlocklist.value = customDomains + d
                    AppPrefs.setCustomDomains(context, customDomains + d)
                    blockInput = ""
                }
            }
            customDomains.sorted().forEach { domain ->
                RuleRow(domain) {
                    VpnState.customBlocklist.value = customDomains - domain
                    AppPrefs.setCustomDomains(context, customDomains - domain)
                }
            }
            if (customDomains.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("Never-block domains (whitelist)", Green)
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            AddDomainRow(allowInput, { allowInput = it }, Green) {
                val d = allowInput.trim().lowercase().trimEnd('.')
                if (d.contains('.') && d.none { it.isWhitespace() }) {
                    VpnState.whitelist.value = whitelist + d
                    AppPrefs.setWhitelist(context, whitelist + d)
                    allowInput = ""
                }
            }
            whitelist.sorted().forEach { domain ->
                RuleRow(domain) {
                    VpnState.whitelist.value = whitelist - domain
                    AppPrefs.setWhitelist(context, whitelist - domain)
                }
            }
            if (whitelist.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AddDomainRow(value: String, onValueChange: (String) -> Unit, accent: Color, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("example.com", color = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = CardBorder,
                cursorColor = accent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, null, tint = accent)
        }
    }
}

@Composable
private fun RuleRow(domain: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(domain, modifier = Modifier.weight(1f), color = TextPrimary, fontSize = 14.sp)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, null, tint = Red)
        }
    }
}

// ================= SETTINGS tab =================

@Composable
private fun SettingsTab(onWatchAd: () -> Unit) {
    val mode by VpnState.mode.collectAsState()
    val adFreeUntil by VpnState.adFreeUntil.collectAsState()
    val context = LocalContext.current
    val adsHidden = System.currentTimeMillis() < adFreeUntil

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        SectionLabel("Protection mode", Violet)
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            ModeOption(
                "DNS only",
                "Fast. Encrypts DNS + blocks tracker domains. No per-app firewall.",
                mode == DnsVpnService.MODE_DNS,
            ) {
                VpnState.mode.value = DnsVpnService.MODE_DNS
                AppPrefs.setMode(context, DnsVpnService.MODE_DNS)
            }
            Spacer(Modifier.height(8.dp))
            ModeOption(
                "DNS + Firewall",
                "Full tunnel: per-app internet blocking, live connection monitor, IP rules.",
                mode == DnsVpnService.MODE_FIREWALL,
            ) {
                VpnState.mode.value = DnsVpnService.MODE_FIREWALL
                AppPrefs.setMode(context, DnsVpnService.MODE_FIREWALL)
            }
            Text(
                "Mode changes apply on the next VPN start.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("Unsafe traffic", Red)
        val quic by VpnState.safetyQuic.collectAsState()
        val http by VpnState.safetyHttp.collectAsState()
        val priv by VpnState.safetyPrivate.collectAsState()
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SafetyRow("Block QUIC (UDP 443)", "Forces apps to use TCP + TLS, more inspectable", quic) {
                VpnState.safetyQuic.value = it
                AppPrefs.setSafety(context, DnsVpnService.KEY_SAFETY_QUIC, it)
            }
            SafetyRow("Block cleartext HTTP (port 80)", "Only HTTPS gets through", http) {
                VpnState.safetyHttp.value = it
                AppPrefs.setSafety(context, DnsVpnService.KEY_SAFETY_HTTP, it)
            }
            SafetyRow("Block private-IP targets", "Stops apps from reaching LAN/loopback", priv) {
                VpnState.safetyPrivate.value = it
                AppPrefs.setSafety(context, DnsVpnService.KEY_SAFETY_PRIVATE, it)
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("Support the project", Green)
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (adsHidden) {
                Text("Ads removed — thanks for your support!", color = Green)
            } else {
                Text("ClearDNS is free and funded by ads.", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onWatchAd,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg0),
                ) {
                    Text("Remove ads for 24h (watch video)")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("About")
        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, null, tint = Neon, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ClearDNS ${BuildConfig.VERSION_NAME}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Encrypted DNS (DoH) + firewall, powered by the firestack engine (RethinkDNS).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SafetyRow(title: String, desc: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = Bg0),
        )
    }
}

@Composable
private fun ModeOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Neon.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (selected) Neon else CardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) Neon else TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        if (selected) Text("✓", color = Neon, fontWeight = FontWeight.Black)
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, 48, 48)
    drawable.draw(canvas)
    return bitmap
}
