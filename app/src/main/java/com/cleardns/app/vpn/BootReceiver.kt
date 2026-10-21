package com.cleardns.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cleardns.app.AppPrefs

/**
 * Restarts the VPN on boot if it was running when the device shut down.
 * (VPN consent persists across reboots, so no interaction is needed.)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPrefs.getBoolean(context, AppPrefs.KEY_VPN_WAS_RUNNING, false)) return
        context.startForegroundService(Intent(context, DnsVpnService::class.java))
    }
}
