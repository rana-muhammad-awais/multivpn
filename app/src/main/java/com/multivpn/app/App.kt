package com.multivpn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import de.blinkt.openvpn.core.VpnStatus

/**
 * Creates the notification channels the embedded OpenVPN service posts to
 * (channel ids are fixed by the ics-openvpn core) and initialises its log cache.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            VpnStatus.initLogCache(cacheDir)
        } catch (ignored: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels()
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                "openvpn_bg",
                getString(R.string.channel_background),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.channel_background_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                "openvpn_newstat",
                getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                "openvpn_userreq",
                getString(R.string.channel_userreq),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.channel_userreq_desc) }
        )
    }
}
