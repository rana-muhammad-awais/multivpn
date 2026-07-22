package com.multivpn.app

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A single VPN Gate relay server.
 */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,
    val speedBps: Long,
    val countryName: String,
    val countryCode: String,
    val sessions: Int,
    val configBase64: String
) {
    val speedMbps: Double get() = speedBps / 1_000_000.0

    /** Decodes the embedded base64 .ovpn profile shipped by the VPN Gate API. */
    fun decodeConfig(): String =
        String(Base64.decode(configBase64, Base64.DEFAULT), Charsets.UTF_8)
}

/**
 * A country grouping of servers, sorted best-first.
 */
data class Country(
    val code: String,
    val name: String,
    val servers: List<VpnServer>
)

/**
 * Client for the public VPN Gate server-list API.
 * The API returns a CSV where the last column is a base64-encoded OpenVPN config.
 */
object VpnGateApi {

    private val ENDPOINTS = listOf(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/"
    )

    /** Fetches the raw CSV from VPN Gate, trying HTTPS first, then HTTP. */
    @Throws(IOException::class)
    fun fetchRaw(): String {
        var lastError: Exception? = null
        for (endpoint in ENDPOINTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (MultiVPN Android)")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.contains("#HostName")) {
                    return text
                }
                lastError = IOException("Unexpected response from $endpoint")
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }
        throw IOException("Could not load the VPN Gate server list", lastError)
    }

    /**
     * Parses the VPN Gate CSV.
     * Columns: HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
     *          Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
     * The leading columns never contain commas; the base64 config is always the last column,
     * so we read it from the end of the line to be safe against commas in Operator/Message.
     */
    fun parse(csv: String): List<VpnServer> {
        val servers = ArrayList<VpnServer>()
        for (line in csv.lineSequence()) {
            if (line.isBlank() || line.startsWith("*") || line.startsWith("#")) continue
            val fields = line.split(",")
            if (fields.size < 15) continue
            val base64 = line.substringAfterLast(",").trim()
            if (base64.isEmpty()) continue
            val code = fields[6].trim().uppercase()
            if (code.length != 2) continue
            servers.add(
                VpnServer(
                    hostName = fields[0].trim(),
                    ip = fields[1].trim(),
                    score = fields[2].toLongOrNull() ?: 0L,
                    pingMs = fields[3].toIntOrNull() ?: -1,
                    speedBps = fields[4].toLongOrNull() ?: 0L,
                    countryName = fields[5].trim(),
                    countryCode = code,
                    sessions = fields[7].toIntOrNull() ?: 0,
                    configBase64 = base64
                )
            )
        }
        return servers
    }

    /** Groups servers by country (alphabetical), servers inside sorted best-score-first. */
    fun groupByCountry(servers: List<VpnServer>): List<Country> =
        servers.groupBy { it.countryCode }
            .map { (code, list) ->
                Country(
                    code = code,
                    name = list.first().countryName,
                    servers = list.sortedByDescending { it.score }
                )
            }
            .sortedBy { it.name }
}
