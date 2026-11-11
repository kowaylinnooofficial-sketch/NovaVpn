package com.novavpn.app

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Uses the official WireGuard Android userspace backend.
 * A real, valid .conf from a reachable WireGuard server is required.
 */
object WireGuardController {
    private const val CONFIG_FILE = "active-wireguard.conf"
    private const val TUNNEL_NAME = "NovaVPN"
    @Volatile private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    @Synchronized
    private fun backend(context: Context): GoBackend {
        return backend ?: GoBackend(context.applicationContext).also { backend = it }
    }

    fun hasConfig(context: Context): Boolean =
        File(context.filesDir, CONFIG_FILE).exists()

    fun importConfig(context: Context, raw: ByteArray) {
        require(raw.isNotEmpty()) { "The selected file is empty." }
        // Parse before saving, so an invalid file never replaces a working profile.
        Config.parse(ByteArrayInputStream(raw))
        File(context.filesDir, CONFIG_FILE).writeBytes(raw)
    }

    fun connect(context: Context) {
        val file = File(context.filesDir, CONFIG_FILE)
        check(file.exists()) { "Import a WireGuard .conf file first." }
        val parsed = file.inputStream().use { Config.parse(it) }
        backend(context).setState(tunnel, Tunnel.State.UP, parsed)
    }

    fun disconnect(context: Context) {
        backend(context).setState(tunnel, Tunnel.State.DOWN, null)
    }

    fun isConnected(context: Context): Boolean = try {
        backend(context).getState(tunnel) == Tunnel.State.UP
    } catch (_: Exception) {
        false
    }
}
