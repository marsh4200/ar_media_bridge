package za.co.arsmarthome.mediabridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Advertises _armediabridge._tcp so Home Assistant discovers the box automatically. */
class NsdAdvertiser(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int, deviceId: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("model", Build.MODEL)
            setAttribute("manufacturer", Build.MANUFACTURER)
            setAttribute("version", BuildConfig.VERSION_NAME)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS register error", e)
            listener = null
        }
    }

    fun unregister() {
        listener?.let {
            try {
                nsd.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_armediabridge._tcp"
        private const val TAG = "ARMediaBridge"
    }
}
