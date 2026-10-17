package dev.wifibridge.protocol

/**
 * Parses the standard Wi-Fi QR payload `WIFI:T:WPA;S:<ssid>;P:<pw>;H:<bool>;;`.
 * Keys are case-insensitive; `\` escapes the next character inside values.
 * Returns null when the text is not a Wi-Fi payload or has no SSID.
 */
object WifiQr {
    fun parse(text: String): WifiNetwork? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        for (segment in splitUnescaped(trimmed.substring(5), ';')) {
            val colon = segment.indexOf(':')
            if (colon <= 0) continue
            val key = segment.substring(0, colon).uppercase()
            if (key !in fields) fields[key] = unescape(segment.substring(colon + 1))
        }
        val ssid = fields["S"]
        if (ssid.isNullOrEmpty()) return null
        val rawPassword = fields["P"] ?: ""
        val security = when (fields["T"]?.uppercase()) {
            null, "" -> if (rawPassword.isNotEmpty()) Security.WPA else Security.NOPASS
            "NOPASS" -> Security.NOPASS
            "WEP" -> Security.WEP
            "SAE", "WPA3" -> Security.SAE
            else -> Security.WPA // WPA, WPA2, WPA/WPA2 and anything else that carries a PSK
        }
        val password = if (security == Security.NOPASS) "" else rawPassword
        val hidden = fields["H"].equals("true", ignoreCase = true)
        return WifiNetwork(ssid = ssid, security = security, password = password, hidden = hidden)
    }

    /** Builds a payload that [parse] maps back to [network]. */
    fun format(network: WifiNetwork): String = buildString {
        append("WIFI:T:").append(network.security)
        append(";S:").append(escape(network.ssid))
        if (network.security != Security.NOPASS) append(";P:").append(escape(network.password))
        if (network.hidden) append(";H:true")
        append(";;")
    }

    /** Splits on [sep] where it is not preceded by an escaping backslash. Segments keep their escapes. */
    internal fun splitUnescaped(text: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    cur.append(c).append(text[i + 1]); i += 2; continue
                }
                c == sep -> {
                    out += cur.toString(); cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    internal fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                out.append(value[i + 1]); i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }
}
