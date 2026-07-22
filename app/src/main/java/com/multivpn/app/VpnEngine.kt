package com.multivpn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/**
 * Thin wrapper around the OpenVPN engine that is compiled INTO this app
 * (ics-openvpn core). No external apps involved: parsing, tunnel service,
 * status callbacks and disconnect all run inside our own process.
 */
class VpnEngine(private val context: Context) : VpnStatus.StateListener {

    interface Listener {
        /** Live tunnel state ("CONNECTED", "NOPROCESS", "AUTH", ...) on the main thread. */
        fun onVpnStatus(state: String, message: String)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceBinder: IOpenVPNServiceInternal? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceBinder = IOpenVPNServiceInternal.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
        }
    }

    /** Call from Activity.onResume(): registers status updates + binds the tunnel service. */
    fun attach() {
        VpnStatus.addStateListener(this)
        val intent = Intent(context, OpenVPNService::class.java)
        intent.action = OpenVPNService.START_SERVICE
        try {
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (ignored: Exception) {
            bound = false
        }
    }

    /** Call from Activity.onPause(). */
    fun detach() {
        VpnStatus.removeStateListener(this)
        if (bound) {
            try {
                context.unbindService(serviceConnection)
            } catch (ignored: Exception) {
            }
            bound = false
        }
        serviceBinder = null
    }

    /**
     * Parses an inline .ovpn config and launches the tunnel.
     * Caller must have obtained VpnService.prepare() consent first.
     * @throws Exception if the config cannot be parsed.
     */
    fun start(inlineConfig: String, displayName: String) {
        val parser = ConfigParser()
        parser.parseConfig(StringReader(inlineConfig))
        val profile = parser.convertProfile()
        profile.mName = displayName
        ProfileManager.setTemporaryProfile(context, profile)
        VPNLaunchHelper.startOpenVpn(profile, context)
    }

    fun disconnect() {
        try {
            serviceBinder?.stopVPN(false)
        } catch (ignored: RemoteException) {
        }
    }

    // ------------------------------------------------ VpnStatus.StateListener

    // v0.7.x StateListener: updateState gained a trailing Intent parameter.
    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        mainHandler.post {
            listener?.onVpnStatus(state ?: "", logmessage ?: "")
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        // Not needed; state updates cover the UI.
    }
}
