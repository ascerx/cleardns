package com.cleardns.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.cleardns.app.vpn.BlocklistManager
import com.cleardns.app.vpn.DnsVpnService
import com.cleardns.app.vpn.VpnState
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var rewardedAd: RewardedAd? = null

    private val prefs by lazy { getSharedPreferences("cleardns", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore persisted state.
        VpnState.blockingEnabled.value = prefs.getBoolean(KEY_BLOCKING, true)
        VpnState.adFreeUntil.value = prefs.getLong(KEY_AD_FREE_UNTIL, 0L)
        VpnState.queriesTotal.value = prefs.getLong(KEY_QUERIES_TOTAL, 0L)
        VpnState.queriesBlocked.value = prefs.getLong(KEY_QUERIES_BLOCKED, 0L)
        BlocklistManager.load(this)

        loadRewardedAd()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
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
        // Notifications permission (33+) for the foreground-service notification.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
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
        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewardedAd()
            }
        }
        ad.show(this) {
            // Reward earned: no ads for 24 hours.
            VpnState.adFreeUntil.value = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)
            prefs.edit().putLong(KEY_AD_FREE_UNTIL, VpnState.adFreeUntil.value).apply()
        }
    }

    private fun saveCounters() {
        prefs.edit()
            .putLong(KEY_QUERIES_TOTAL, VpnState.queriesTotal.value)
            .putLong(KEY_QUERIES_BLOCKED, VpnState.queriesBlocked.value)
            .apply()
    }

    companion object {
        private const val REQUEST_VPN = 100
        private const val KEY_BLOCKING = "blocking"
        private const val KEY_AD_FREE_UNTIL = "ad_free_until"
        private const val KEY_QUERIES_TOTAL = "queries_total"
        private const val KEY_QUERIES_BLOCKED = "queries_blocked"
    }
}

@Composable
private fun HomeScreen(onToggle: () -> Unit, onWatchAd: () -> Unit) {
    val context = LocalContext.current
    val isRunning by VpnState.isRunning.collectAsState()
    val blocking by VpnState.blockingEnabled.collectAsState()
    val total by VpnState.queriesTotal.collectAsState()
    val blocked by VpnState.queriesBlocked.collectAsState()
    val adFreeUntil by VpnState.adFreeUntil.collectAsState()
    val refreshing by BlocklistManager.isRefreshing.collectAsState()

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

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
        if (isRunning) {
            Text(
                text = "Encrypted DNS + tracker blocking",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
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
                    Switch(checked = blocking, onCheckedChange = { VpnState.blockingEnabled.value = it })
                }
                Text(
                    text = "${BlocklistManager.size().coerceAtLeast(1)} domains in blocklist",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            BlocklistManager.refresh(context)
                        }
                    },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (refreshing) "Updating…" else "Update blocklist")
                }
            }
        }

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
