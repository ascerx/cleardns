package com.cleardns.app.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.TileService
import com.cleardns.app.MainActivity

class ClearDnsTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, DnsVpnService::class.java)
        if (VpnState.isRunning.value) {
            stopService(intent)
        } else if (VpnService.prepare(this) == null) {
            startService(intent)
        } else {
            // Consent dialog can only be shown from an activity.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
