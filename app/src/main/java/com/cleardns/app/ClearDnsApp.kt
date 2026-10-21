package com.cleardns.app

import android.app.Application
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClearDnsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@ClearDnsApp)
        }
    }
}
