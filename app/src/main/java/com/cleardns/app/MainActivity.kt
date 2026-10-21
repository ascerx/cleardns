package com.cleardns.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
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
import com.cleardns.app.vpn.BlocklistManager
import com.cleardns.app.vpn.ConnLogEntry
import com.cleardns.app.vpn.DnsVpnService
import com.cleardns.app.vpn.DohClient
import com.cleardns.app.vpn.VpnState
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ---------------- Design tokens (neon techy) ----------------

private val Bg0 = Color(0xFF060A13)
private val Bg1 = Color(0xFF0B1122)
private val Bg2 = Color(0xFF111A33)
private val Neon = Color(0xFF00E5FF)
private val NeonDim = Color(0xFF0891B2)
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

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun GlowBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Bg0, Bg1, Bg2)),
            ),
    ) {
        content()
    }
}

@Composable
private fun NeonCard(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier
        .fillMaxWidth()
        .border(1.dp, CardBorder, CardShape)
        .clip(CardShape)
    if (brush != null) m = m.border(1.5.dp, brush, CardShape)
    Card(
        modifier = (if (onClick != null) m.clickable(onClick = onClick) else m),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = CardBg),
        content = { Column(modifier = Modifier.padding(16.dp), content = content) },
    )
}

private enum class Screen { Home, Apps, Domains, Lists, Monitor }

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
        VpnState.customBlocklist.value = AppPrefs.customDomains(this)
        VpnState.whitelist.value = AppPrefs.whitelist(this)
        VpnState.selectedLists.value = AppPrefs.listIds(this)
        VpnState.dohEndpoint.value = AppPrefs.endpoint(this, DohClient.DEFAULT_ENDPOINT)
        VpnState.mode.value = AppPrefs.mode(this, DnsVpnService.MODE_DNS)
        BlocklistManager.load(this)

        loadRewardedAd()

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
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    GlowBackground {
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
        if (VpnState.isRunning.value) {
            stopService(Intent(this, DnsVpnService::class.java))
            saveCounters()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (VpnService.prepare(this) == null) {
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

    override fun onPause() {
        saveCounters()
        super.onPause()
    }

    companion object {
        private const val REQUEST_VPN = 100
    }
}

@Composable
private fun AppRoot(onToggle: () -> Unit, onWatchAd: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.Home) }
    when (screen) {
        Screen.Home -> HomeScreen(
            onToggle = onToggle,
            onWatchAd = onWatchAd,
            onOpenApps = { screen = Screen.Apps },
            onOpenDomains = { screen = Screen.Domains },
            onOpenLists = { screen = Screen.Lists },
            onOpenMonitor = { screen = Screen.Monitor },
        )
        Screen.Apps -> AppsScreen(onBack = { screen = Screen.Home })
        Screen.Domains -> DomainsScreen(onBack = { screen = Screen.Home })
        Screen.Lists -> ListsScreen(onBack = { screen = Screen.Home })
        Screen.Monitor -> MonitorScreen(onBack = { screen = Screen.Home })
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp, 16.dp, 16.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun HomeScreen(
    onToggle: () -> Unit,
    onWatchAd: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    val isRunning by VpnState.isRunning.collectAsState()
    val blocking by VpnState.blockingEnabled.collectAsState()
    val total by VpnState.queriesTotal.collectAsState()
    val blocked by VpnState.queriesBlocked.collectAsState()
    val adFreeUntil by VpnState.adFreeUntil.collectAsState()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()
    val mode by VpnState.mode.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pulse = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = if (isRunning) 0.9f else 0.25f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "glow",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        // ---- Status ----
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .border(10.dp, Neon.copy(alpha = glowAlpha * 0.35f), CircleShape)
                    .border(2.dp, if (isRunning) Neon else TextMuted, CircleShape),
            )
            Icon(
                imageVector = if (isRunning) Icons.Filled.GppGood else Icons.Filled.GppBad,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isRunning) Neon else TextMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isRunning) "Protected" else "Protection off",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
        )
        Text(
            text = when {
                isRunning && blocking && mode == "firewall" -> "DNS + Firewall · full tunnel"
                isRunning && blocking -> "Encrypted DNS · tracker blocking"
                isRunning -> "Blocking paused"
                else -> "Tap start to shield your device"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (isRunning && !blocking) Amber else TextMuted,
        )
        Spacer(Modifier.height(20.dp))

        // ---- Mode selector ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ModeChip("DNS only", Icons.Filled.Public, mode == "dns", Modifier.weight(1f)) {
                VpnState.mode.value = DnsVpnService.MODE_DNS
                AppPrefs.setMode(context, DnsVpnService.MODE_DNS)
            }
            ModeChip("DNS + Firewall", Icons.Filled.Bolt, mode == "firewall", Modifier.weight(1f)) {
                VpnState.mode.value = DnsVpnService.MODE_FIREWALL
                AppPrefs.setMode(context, DnsVpnService.MODE_FIREWALL)
            }
        }
        Text(
            text = "Mode changes apply on the next VPN start",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))

        // ---- Start/Stop button ----
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isRunning) DangerBrush else GlowBrush)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (isRunning) "STOP" else "START",
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                letterSpacing = 2.sp,
                color = Bg0,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- Stats ----
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Queries", formatCount(total), Neon, Modifier.weight(1f))
            StatCard("Blocked", formatCount(blocked), Pink, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))

        // ---- Blocking switch ----
        NeonCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Block trackers", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatCount(BlocklistManager.size().coerceAtLeast(1).toLong())} + ${VpnState.customBlocklist.value.size} domain rules",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
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
            Spacer(Modifier.height(8.dp))
            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Neon,
                    trackColor = Bg2,
                )
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(
                onClick = { scope.launch { BlocklistManager.downloadAndMerge(context, VpnState.selectedLists.value) } },
                enabled = !refreshing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Neon),
            ) {
                Text(if (refreshing) "Updating…" else "Update blocklist")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Menu ----
        MenuRow(Icons.Filled.MonitorHeart, "Network monitor", "Live connections & per-app traffic", Neon, onOpenMonitor)
        MenuRow(Icons.Filled.Tune, "Apps", "Per-app firewall rules", Violet, onOpenApps)
        MenuRow(Icons.Filled.Block, "Domain rules", "Custom blocked & allowed domains", Pink, onOpenDomains)
        MenuRow(Icons.Filled.Search, "Lists & DNS server", "Blocklists and encrypted resolvers", Green, onOpenLists)

        Spacer(Modifier.height(16.dp))

        // ---- Ads ----
        val adsHidden = System.currentTimeMillis() < adFreeUntil
        if (!adsHidden) {
            TextButton(onClick = onWatchAd) {
                Text("Remove ads for 24 hours (watch video)", color = TextMuted)
            }
            Spacer(Modifier.height(4.dp))
            BannerAd()
        } else {
            Text(
                text = "Ads removed — thanks for your support!",
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun ModeChip(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) CardBg else Color(0xFF0C1424))
            .border(
                1.5.dp,
                if (selected) GlowBrush else Brush.verticalGradient(listOf(CardBorder, CardBorder)),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = if (selected) Neon else TextMuted, modifier = Modifier.size(16.dp))
            Text(label, color = if (selected) TextPrimary else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .border(1.dp, CardBorder, CardShape)
            .clip(CardShape),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    NeonCard(modifier = Modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Text("›", color = TextMuted, fontSize = 22.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
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

// ---------- Apps screen (tri-state firewall rules) ----------

private data class AppEntry(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Bitmap,
)

@Composable
private fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rules by VpnState.appRules.collectAsState()
    val isRunning by VpnState.isRunning.collectAsState()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<AppEntry?>(null) }

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

    fun applyRule(uid: Int, rule: String?) {
        val current = rules.toMutableMap()
        if (rule == null) current.remove(uid) else current[uid] = rule
        VpnState.appRules.value = current
        AppPrefs.setAppRules(context, current)
        if (isRunning) {
            context.startService(
                Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RESTART),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Apps", "Tap an app: filter · block internet · bypass ClearDNS", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search", color = TextMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Neon,
                unfocusedBorderColor = CardBorder,
                cursorColor = Neon,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        if (loading) {
            Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Neon)
            }
        } else {
            val filtered = apps.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val rule = rules[app.uid]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selected = app }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(38.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 15.sp, color = TextPrimary)
                            Text(app.packageName, fontSize = 11.sp, color = TextMuted)
                        }
                        StateBadge(rule)
                    }
                }
            }
        }
    }

    selected?.let { app ->
        RulePickerDialog(
            label = app.label,
            current = VpnState.appRules.value[app.uid],
            onPick = { rule ->
                applyRule(app.uid, rule)
                selected = null
            },
            onDismiss = { selected = null },
        )
    }
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
                RuleOption("Block internet", "Cut all network access for this app", Red, current == "block") { onPick("block") }
                RuleOption("Bypass", "App skips ClearDNS (system DNS)", Amber, current == "bypass") { onPick("bypass") }
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

// ---------- Monitor screen ----------

@Composable
private fun MonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by VpnState.connLog.collectAsState()
    val stats by VpnState.appStats.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var labels by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        labels = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            stats.keys.associateWith { uid ->
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
        ScreenHeader("Network monitor", "Last ${VpnState.connLog.value.size} connections (live)", onBack)
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "All", "blocked" to "Blocked", "allowed" to "Allowed").forEach { (id, label) ->
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
        val visible = entries.filter {
            when (filter) {
                "blocked" -> it.blocked
                "allowed" -> !it.blocked
                else -> true
            }
        }
        if (visible.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.MonitorHeart, null, tint = TextMuted, modifier = Modifier.size(44.dp))
                Text(
                    "No connections recorded yet.\nSwitch to DNS + Firewall mode and start the VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(visible.asReversed(), key = { it.cid }) { e ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .border(
                                1.dp,
                                if (e.blocked) Red.copy(alpha = 0.5f) else CardBorder,
                                RoundedCornerShape(14.dp),
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    labels[e.uid] ?: "uid ${e.uid % 100000}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (e.blocked) Red else TextPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    e.proto.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (e.domain.isNotEmpty() && e.domain != e.target) "${e.domain} → ${e.target}"
                                else e.target,
                                fontSize = 12.sp,
                                color = TextMuted,
                                maxLines = 1,
                            )
                            if (!e.ended && e.rx + e.tx > 0) {
                                Text(
                                    "↑ ${formatCount(e.tx)} · ↓ ${formatCount(e.rx)}",
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
}

// ---------- Domains screen ----------

@Composable
private fun DomainsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val domains by VpnState.customBlocklist.collectAsState()
    var input by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Domain rules", "Custom blocked & allowed domains", onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("example.com", color = TextMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Neon,
                    unfocusedBorderColor = CardBorder,
                    cursorColor = Neon,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val domain = input.trim().lowercase().trimEnd('.')
                    if (domain.contains('.') && domain.none { it.isWhitespace() }) {
                        val current = VpnState.customBlocklist.value + domain
                        VpnState.customBlocklist.value = current
                        AppPrefs.setCustomDomains(context, current)
                        input = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = Neon)
            }
        }
        Text(
            "Blocked domains (and subdomains) answer NXDOMAIN.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(16.dp, 8.dp),
        )
        domains.sorted().forEach { domain ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(domain, modifier = Modifier.weight(1f), color = TextPrimary, fontSize = 14.sp)
                IconButton(onClick = { toDelete = domain }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Red)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = CardBorder)
        Spacer(Modifier.height(12.dp))

        Text(
            "Allowed domains (whitelist)",
            fontWeight = FontWeight.SemiBold,
            color = Green,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "Never blocked, even if a blocklist matches.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        var allowInput by remember { mutableStateOf("") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = allowInput,
                onValueChange = { allowInput = it },
                placeholder = { Text("example.com", color = TextMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green,
                    unfocusedBorderColor = CardBorder,
                    cursorColor = Green,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val domain = allowInput.trim().lowercase().trimEnd('.')
                    if (domain.contains('.') && domain.none { it.isWhitespace() }) {
                        val current = VpnState.whitelist.value + domain
                        VpnState.whitelist.value = current
                        AppPrefs.setWhitelist(context, current)
                        allowInput = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = Green)
            }
        }
        VpnState.whitelist.value.sorted().forEach { domain ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(domain, modifier = Modifier.weight(1f), color = TextPrimary, fontSize = 14.sp)
                IconButton(onClick = {
                    val current = VpnState.whitelist.value - domain
                    VpnState.whitelist.value = current
                    AppPrefs.setWhitelist(context, current)
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Red)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (toDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove domain", color = TextPrimary) },
            text = { Text("Stop blocking \"${toDelete}\"?", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    val current = VpnState.customBlocklist.value - toDelete!!
                    VpnState.customBlocklist.value = current
                    AppPrefs.setCustomDomains(context, current)
                    toDelete = null
                }) { Text("Remove", color = Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = TextMuted) } },
            containerColor = Color(0xFF0D1526),
        )
    }
}

// ---------- Lists & DNS screen ----------

private val DNS_OPTIONS = listOf(
    "Google" to DohClient.DEFAULT_ENDPOINT,
    "Cloudflare" to DohClient.CLOUDFLARE_ENDPOINT,
    "Quad9" to "https://quad9.net/dns-query",
    "AdGuard" to "https://dns.adguard-dns.com/dns-query",
)

@Composable
private fun ListsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by VpnState.selectedLists.collectAsState()
    val endpoint by VpnState.dohEndpoint.collectAsState()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()
    val progress by BlocklistManager.progress.collectAsState()
    val error by BlocklistManager.lastError.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Lists & DNS server", "Choose what to block and who resolves", onBack)

        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Blocklists", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
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
                    Column {
                        Text(list.label, fontSize = 14.sp, color = TextPrimary)
                        Text(
                            list.url.removePrefix("https://"),
                            fontSize = 10.sp,
                            color = TextMuted,
                        )
                    }
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
                Text(
                    "Last error: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = Red,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        NeonCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("DNS-over-HTTPS server", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Applied on the next VPN start",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(32.dp))
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, 48, 48)
    drawable.draw(canvas)
    return bitmap
}
