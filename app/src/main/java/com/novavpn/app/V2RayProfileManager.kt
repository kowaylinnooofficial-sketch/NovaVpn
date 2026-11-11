package com.novavpn.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/** Imports legacy vmess:// Base64(JSON) and Xray/V2Ray JSON profiles.
 * Parsing/import is implemented here; starting Xray requires the native libXray AAR
 * and a VpnService TUN bridge (documented in README), and is intentionally not faked.
 */
object V2RayProfileManager {
    private const val FILE = "v2ray-profile.json"

    fun importText(context: Context, input: String): String {
        val text = input.trim()
        require(text.isNotEmpty()) { "Paste a VMess link or V2Ray JSON first." }
        val config = when {
            text.startsWith("vmess://", ignoreCase = true) -> vmessToXray(text)
            text.startsWith("{") -> validateXrayJson(text)
            else -> error("Unsupported format. Use vmess:// or Xray JSON.")
        }
        File(context.filesDir, FILE).writeText(config)
        return JSONObject(config).optJSONArray("outbounds")?.optJSONObject(0)
            ?.optString("tag", "V2Ray profile") ?: "V2Ray profile"
    }

    fun hasProfile(context: Context): Boolean = File(context.filesDir, FILE).exists()

    fun readProfile(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    private fun validateXrayJson(raw: String): String {
        val root = JSONObject(raw)
        val outbounds = root.optJSONArray("outbounds") ?: error("JSON has no 'outbounds' array.")
        require(outbounds.length() > 0) { "No outbound server found in JSON." }
        val found = (0 until outbounds.length()).any {
            val p = outbounds.getJSONObject(it).optString("protocol")
            p in setOf("vmess", "vless", "shadowsocks", "trojan")
        }
        require(found) { "No supported VMess/V2Ray outbound found in JSON." }
        return root.toString(2)
    }

    private fun vmessToXray(link: String): String {
        val encoded = link.removePrefix("vmess://").trim()
        val bytes = try { Base64.decode(padBase64(encoded), Base64.DEFAULT) }
            catch (_: Exception) { error("Invalid VMess Base64 link.") }
        val vmess = try { JSONObject(String(bytes, Charsets.UTF_8)) }
            catch (_: Exception) { error("VMess link does not contain valid JSON.") }

        val address = vmess.optString("add").trim()
        val port = vmess.optString("port").toIntOrNull() ?: 0
        val id = vmess.optString("id").trim()
        require(address.isNotEmpty() && port in 1..65535 && id.isNotEmpty()) {
            "VMess profile needs a valid server address, port and UUID."
        }
        val network = vmess.optString("net", "tcp").ifBlank { "tcp" }
        val tls = vmess.optString("tls").equals("tls", true)
        val security = vmess.optString("scy", "auto").ifBlank { "auto" }
        val user = JSONObject().put("id", id).put("alterId", vmess.optInt("aid", 0)).put("security", security)
        val vnext = JSONObject().put("address", address).put("port", port)
            .put("users", JSONArray().put(user))
        val outbound = JSONObject().put("tag", vmess.optString("ps", "VMess").ifBlank { "VMess" })
            .put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))

        val stream = JSONObject().put("network", network).put("security", if (tls) "tls" else "none")
        when (network) {
            "ws" -> {
                val headers = JSONObject()
                vmess.optString("host").takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                stream.put("wsSettings", JSONObject().put("path", vmess.optString("path", "/")).put("headers", headers))
            }
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", vmess.optString("path")))
            "tcp" -> if (vmess.optString("type") == "http") {
                stream.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http")))
            }
        }
        if (tls) {
            val tlsSettings = JSONObject().put("allowInsecure", false)
            vmess.optString("sni").takeIf { it.isNotBlank() }?.let { tlsSettings.put("serverName", it) }
            stream.put("tlsSettings", tlsSettings)
        }
        outbound.put("streamSettings", stream)
        val root = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
        return root.toString(2)
    }

    private fun padBase64(value: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        return normalized + "=".repeat((4 - normalized.length % 4) % 4)
    }
}
