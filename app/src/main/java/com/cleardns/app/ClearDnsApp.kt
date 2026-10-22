package com.cleardns.app

import android.app.Application
import android.webkit.WebSettings
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ClearDnsApp : Application() {

    companion object {
        /** True once MobileAds.initialize completed (all ad loading waits for this). */
        val adsInitialized = MutableStateFlow(false)
    }

    override fun onCreate() {
        super.onCreate()
        // Force WebView provider initialization HERE on the main thread first.
        // AdMob's initialize() touches WebSettings from a background thread;
        // chromium init needs the main thread, so running ads-init without a
        // warm WebView deadlocks both threads.
        try {
            WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
        }
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@ClearDnsApp) {}
            adsInitialized.value = true
        }
    }
}
