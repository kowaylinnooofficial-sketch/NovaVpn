package com.novavpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Xray TUN service. It runs in :xray, isolated from WireGuard's GoBackend process,
 * because two independent Go runtimes must never be loaded into one process.
 * Requires a real, reachable Xray/VMess/VLESS server profile.
 */
class XrayVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    private val dialerController = object : DialerController {
        override fun protectFd(fd: Int): Boolean = this@XrayVpnService.protect(fd)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> executor.execute { stopTunnel() }
            ACTION_IMPORT -> {
                val text = intent.getStringExtra(EXTRA_PROFILE_TEXT).orEmpty()
                executor.execute { importProfile(text) }
            }
            ACTION_START -> {
                if (!running) {
                    startForeground(NOTIFICATION_ID, notification("Starting Xray tunnel…"))
                    executor.execute { startTunnel() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun importProfile(raw: String) {
        try {
            require(raw.isNotBlank()) { "Paste a share link or Xray JSON first." }
            val savedName = if (raw.trimStart().startsWith("{")) {
                V2RayProfileManager.importText(this, raw)
            } else {
                try {
                    val request = JSONObject().put("apiVersion", 3)
                        .put("method", "convertShareLinksToXrayJson")
                        .put("payload", JSONObject().put("text", raw.trim()))
                    val response = JSONObject(LibXray.invoke(request.toString()))
                    check(response.optBoolean("success")) {
                        response.optString("error", "Unsupported or invalid share link")
                    }
                    val outbounds = response.optJSONObject("data")?.optJSONArray("outbounds")
                        ?: error("Share link did not produce any Xray outbound.")
                    require(outbounds.length() > 0) { "No usable server was found in the share link." }
                    val root = JSONObject()
                        .put("log", JSONObject().put("loglevel", "warning"))
                        .put("outbounds", outbounds)
                    if ((0 until outbounds.length()).none { outbounds.getJSONObject(it).optString("tag") == "direct" }) {
                        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                    }
                    java.io.File(filesDir, "v2ray-profile.json").writeText(root.toString(2))
                    outbounds.optJSONObject(0)?.optString("tag", "Xray profile") ?: "Xray profile"
                } catch (shareError: Exception) {
                    // libXray's URI parser intentionally omits legacy VMess QR links;
                    // retain compatibility with the old vmess:// Base64(JSON) format.
                    if (raw.trim().startsWith("vmess://", ignoreCase = true)) {
                        V2RayProfileManager.importText(this, raw)
                    } else throw shareError
                }
            }
            sendState(false, "Imported and validated: $savedName")
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, false)
                .putExtra(EXTRA_MESSAGE, "Imported and validated: $savedName")
                .putExtra(EXTRA_IMPORTED, true))
        } catch (e: Exception) {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, false)
                .putExtra(EXTRA_MESSAGE, e.message ?: "Profile import failed")
                .putExtra(EXTRA_IMPORTED, false))
        }
    }

    private fun startTunnel() {
        try {
            val profile = V2RayProfileManager.readProfile(this)
                ?: error("Import a VMess/V2Ray profile first.")
            val root = JSONObject(profile)
            val outbounds = root.optJSONArray("outbounds")
                ?: error("Profile has no Xray outbounds.")
            require(outbounds.length() > 0) { "Profile contains no outbound servers." }

            // The Android VpnService owns the TUN fd; Xray consumes this fd directly.
            val pfd = Builder()
                .setSession("NovaVPN Xray")
                .setMtu(1500)
                .addAddress("10.77.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish() ?: error("Android did not create the VPN interface.")
            tun = pfd

            val inbound = JSONObject()
                .put("tag", "nova-tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject()
                    .put("name", "NovaVPN")
                    .put("mtu", 1500)
                    .put("gateway", JSONArray().put("10.77.0.1/30"))
                    .put("dns", JSONArray().put("1.1.1.1")))
            root.put("inbounds", JSONArray().put(inbound))
            root.put("env", JSONObject().put("xray.tun.fd", pfd.fd.toString()))
            if (!root.has("log")) root.put("log", JSONObject().put("loglevel", "warning"))

            // Ensure Xray's own sockets and Go DNS lookups bypass this VPN.
            LibXray.registerDialerController(dialerController)
            LibXray.registerListenerController(dialerController)
            LibXray.setDNS(dialerController, "1.1.1.1:53")

            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", root.toString()))
            val response = JSONObject(LibXray.invoke(request.toString()))
            check(response.optBoolean("success")) {
                response.optString("error", "Xray failed to start")
            }
            running = true
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Xray tunnel is running"))
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, true).apply()
            sendState(true, "Xray tunnel is running")
        } catch (e: Exception) {
            running = false
            try { LibXray.resetDNS() } catch (_: Throwable) { }
            try { tun?.close() } catch (_: Exception) { }
            tun = null
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, false).apply()
            sendState(false, "Xray error: ${e.message ?: "startup failed"}")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Xray error: ${e.message ?: "startup failed"}"))
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun stopTunnel() {
        try {
            val request = JSONObject().put("apiVersion", 3).put("method", "stopXray")
                .put("payload", JSONObject())
            LibXray.invoke(request.toString())
        } catch (_: Throwable) { }
        try { LibXray.resetDNS() } catch (_: Throwable) { }
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        running = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, false).apply()
        sendState(false, "Xray disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendState(connected: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_CONNECTED, connected).putExtra(EXTRA_MESSAGE, message))
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "NovaVPN", NotificationManager.IMPORTANCE_LOW))
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NovaVPN")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(running || message.startsWith("Starting"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onRevoke() {
        executor.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        try { executor.execute { stopTunnel() } } catch (_: Exception) { }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.novavpn.app.XRAY_START"
        private const val ACTION_STOP = "com.novavpn.app.XRAY_STOP"
        private const val ACTION_IMPORT = "com.novavpn.app.XRAY_IMPORT"
        const val ACTION_STATE = "com.novavpn.app.XRAY_STATE"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_IMPORTED = "imported"
        private const val EXTRA_PROFILE_TEXT = "profile_text"
        private const val CHANNEL_ID = "novavpn_xray"
        private const val NOTIFICATION_ID = 7102
        const val PREFS = "novavpn_xray_state"
        const val KEY_CONNECTED = "xray_connected"

        fun importProfile(context: Context, text: String) {
            val intent = Intent(context, XrayVpnService::class.java)
                .setAction(ACTION_IMPORT).putExtra(EXTRA_PROFILE_TEXT, text)
            context.startService(intent)
        }
        fun start(context: Context) {
            val intent = Intent(context, XrayVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_STOP))
        }
        fun isConnected(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONNECTED, false)
    }
}
