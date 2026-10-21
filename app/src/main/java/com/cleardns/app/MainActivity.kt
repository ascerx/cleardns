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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cleardns.app.vpn.BlocklistManager
import com.cleardns.app.vpn.DnsVpnService
import com.cleardns.app.vpn.DohClient
import com.cleardns.app.vpn.VpnState
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.google.android.gms.ads.FullScreenContentCallback

private enum class Screen { Home, Apps, Domains, Lists }

class MainActivity : ComponentActivity() {

    private var rewardedAd: RewardedAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore persisted state.
        VpnState.blockingEnabled.value = AppPrefs.getBoolean(this, AppPrefs.KEY_BLOCKING, true)
        VpnState.adFreeUntil.value = AppPrefs.getLong(this, AppPrefs.KEY_AD_FREE_UNTIL, 0L)
        VpnState.queriesTotal.value = AppPrefs.getLong(this, AppPrefs.KEY_QUERIES_TOTAL, 0L)
        VpnState.queriesBlocked.value = AppPrefs.getLong(this, AppPrefs.KEY_QUERIES_BLOCKED, 0L)
        VpnState.skippedUids.value = AppPrefs.skippedUids(this)
        VpnState.customBlocklist.value = AppPrefs.customDomains(this)
        VpnState.whitelist.value = AppPrefs.whitelist(this)
        VpnState.selectedLists.value = AppPrefs.listIds(this)
        VpnState.dohEndpoint.value = AppPrefs.endpoint(this, DohClient.DEFAULT_ENDPOINT)
        BlocklistManager.load(this)

        loadRewardedAd()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        onToggle = { onToggleProtection() },
                        onWatchAd = { showRewardedAd() },
                    )
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
        )
        Screen.Apps -> AppsScreen(onBack = { screen = Screen.Home })
        Screen.Domains -> DomainsScreen(onBack = { screen = Screen.Home })
        Screen.Lists -> ListsScreen(onBack = { screen = Screen.Home })
    }
}

@Composable
private fun HomeScreen(
    onToggle: () -> Unit,
    onWatchAd: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenLists: () -> Unit,
) {
    val isRunning by VpnState.isRunning.collectAsState()
    val blocking by VpnState.blockingEnabled.collectAsState()
    val total by VpnState.queriesTotal.collectAsState()
    val blocked by VpnState.queriesBlocked.collectAsState()
    val adFreeUntil by VpnState.adFreeUntil.collectAsState()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        Icon(
            imageVector = if (isRunning) Icons.Filled.GppGood else Icons.Filled.GppBad,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isRunning) "Protection active" else "Protection off",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (isRunning && !blocking) {
            Text(
                text = "Blocking paused",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.height(20.dp))

        FilledTonalButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(0.7f).height(48.dp),
        ) {
            Text(if (isRunning) "STOP" else "START")
        }

        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatCard("Queries", total.toString(), Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            StatCard("Blocked", blocked.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Block trackers")
                    Switch(checked = blocking, onCheckedChange = {
                        VpnState.blockingEnabled.value = it
                        AppPrefs.putBoolean(context, AppPrefs.KEY_BLOCKING, it)
                    })
                }
                Text(
                    text = "${BlocklistManager.size().coerceAtLeast(1)} + ${VpnState.customBlocklist.value.size} domain rules",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scope.launch { BlocklistManager.downloadAndMerge(context, VpnState.selectedLists.value) } },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (refreshing) "Updating…" else "Update blocklist")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SimpleMenuCard(
            title = "Apps excluded from protection",
            subtitle = "${VpnState.skippedUids.value.size} apps bypass the VPN",
            onClick = onOpenApps,
        )
        Spacer(Modifier.height(10.dp))
        SimpleMenuCard(
            title = "Custom blocked domains",
            subtitle = "${VpnState.customBlocklist.value.size} custom rules",
            onClick = onOpenDomains,
        )
        Spacer(Modifier.height(10.dp))
        SimpleMenuCard(
            title = "Lists & DNS server",
            subtitle = "${VpnState.selectedLists.value.size} blocklists · ${dnsLabel(VpnState.dohEndpoint.value)}",
            onClick = onOpenLists,
        )

        Spacer(Modifier.height(16.dp))

        val adsHidden = System.currentTimeMillis() < adFreeUntil
        if (!adsHidden) {
            TextButton(onClick = onWatchAd) {
                Text("Remove ads for 24 hours (watch video)")
            }
            Spacer(Modifier.height(8.dp))
            BannerAd()
        } else {
            Text(
                text = "Ads removed — thanks for your support!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SimpleMenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
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

// ---------- Apps screen ----------

private data class AppEntry(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Bitmap,
    var skipped: Boolean,
)

@Composable
private fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val skipped by VpnState.skippedUids.collectAsState()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(skipped) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = runCatching {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }.getOrDefault(emptyList())
            packages
                .filter { pkg ->
                    pkg.requestedPermissions?.any { it == android.Manifest.permission.INTERNET } == true
                }
                .filter { it.packageName != context.packageName }
                .mapNotNull { pkg ->
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    AppEntry(
                        uid = appInfo.uid,
                        packageName = pkg.packageName,
                        label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg.packageName },
                        icon = drawableToBitmap(pm.getApplicationIcon(appInfo)),
                        skipped = appInfo.uid in skipped,
                    )
                }
                .sortedWith(compareBy({ !it.skipped }, { it.label.lowercase() }))
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Apps", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Excluded apps use the regular system DNS (no filtering)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
        if (loading) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else {
            val filtered = apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, fontSize = 15.sp)
                            Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(
                            if (app.skipped) "excluded" else "filtered",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (app.skipped) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Switch(
                            checked = app.skipped,
                            onCheckedChange = { exclude ->
                                val current = VpnState.skippedUids.value.toMutableSet()
                                if (exclude) {
                                    current.add(app.uid)
                                    app.skipped = true
                                } else {
                                    current.remove(app.uid)
                                    app.skipped = false
                                }
                                VpnState.skippedUids.value = current
                                AppPrefs.setSkippedUids(context, current)
                                // Restart VPN so the new skip list takes effect.
                                if (VpnState.isRunning.value) {
                                    context.startService(
                                        Intent(context, DnsVpnService::class.java)
                                            .setAction(DnsVpnService.ACTION_RESTART),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, 48, 48)
    drawable.draw(canvas)
    return bitmap
}

// ---------- Lists & DNS screen ----------

private val DNS_OPTIONS = listOf(
    "Google" to DohClient.DEFAULT_ENDPOINT,
    "Cloudflare" to DohClient.CLOUDFLARE_ENDPOINT,
    "Quad9" to "https://quad9.net/dns-query",
    "AdGuard" to "https://dns.adguard-dns.com/dns-query",
)

private fun dnsLabel(endpoint: String): String =
    DNS_OPTIONS.firstOrNull { it.second == endpoint }?.first ?: endpoint.removePrefix("https://").substringBefore('/')

@Composable
private fun ListsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by VpnState.selectedLists.collectAsState()
    val endpoint by VpnState.dohEndpoint.collectAsState()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()
    val progress by BlocklistManager.progress.collectAsState()
    val error by BlocklistManager.lastError.collectAsState()
    val listCount by remember { derivedStateOf { BlocklistManager.size() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Lists & DNS server", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Choose what to block and who resolves",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Blocklists", fontWeight = FontWeight.SemiBold)
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
                        )
                        Column {
                            Text(list.label, fontSize = 14.sp)
                            Text(
                                list.url.removePrefix("https://"),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { scope.launch { BlocklistManager.downloadAndMerge(context, selected) } },
                    enabled = !refreshing && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (refreshing) (progress.ifEmpty { "Working…" }) else "Download & merge (${dnsDomainCountLabel(listCount)})")
                }
                error?.let {
                    Text(
                        "Last error: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("DNS-over-HTTPS server", fontWeight = FontWeight.SemiBold)
                Text(
                    "Applied on the next VPN start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
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
                        )
                        Text(label)
                    }
                }
            }
        }
    }
}

private fun dnsDomainCountLabel(count: Int): String {
    return when {
        count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000f)}M domains"
        count >= 1_000 -> "${count / 1_000}K domains"
        else -> "$count domains"
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Custom blocked domains", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Add domains you want to block",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("example.com") },
                singleLine = true,
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
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
        Text(
            "Blocked domains (and their subdomains) get NXDOMAIN while filtering is on.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(12.dp, 8.dp),
        )
        domains.sorted().forEach { domain ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(domain, modifier = Modifier.weight(1f))
                IconButton(onClick = { toDelete = domain }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (domains.isEmpty()) {
            Text(
                "No custom domains yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Text(
            "Allowed domains (whitelist)",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(
            "Never blocked, even if a blocklist matches.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        var allowInput by remember { mutableStateOf("") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = allowInput,
                onValueChange = { allowInput = it },
                placeholder = { Text("example.com") },
                singleLine = true,
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
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
        VpnState.whitelist.value.sorted().forEach { domain ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(domain, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val current = VpnState.whitelist.value - domain
                    VpnState.whitelist.value = current
                    AppPrefs.setWhitelist(context, current)
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (VpnState.whitelist.value.isEmpty()) {
            Text(
                "No allowed domains yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
        if (toDelete != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Remove domain") },
                text = { Text("Stop blocking \"${toDelete}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        val current = VpnState.customBlocklist.value - toDelete!!
                        VpnState.customBlocklist.value = current
                        AppPrefs.setCustomDomains(context, current)
                        toDelete = null
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
            )
        }
    }
}
