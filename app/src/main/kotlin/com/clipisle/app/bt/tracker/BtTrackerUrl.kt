package com.clipisle.app.bt.tracker

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Syntax and literal-address checks only. Actual network DNS must be checked separately. */
object BtTrackerUrl {
    const val MAX_LINE_BYTES = 2048
    enum class Kind { udp, http, https }
    data class Parsed(val raw: String, val kind: Kind, val host: String, val isIpv6: Boolean,
                      val port: Int, val pathAndQuery: String, val canonicalKey: String)
    sealed class Result {
        data class Ok(val value: Parsed) : Result()
        data class Reject(val reason: String) : Result()
    }

    fun parse(rawInput: String): Result {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return Result.Reject("地址为空")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return Result.Reject("地址过长")
        if (raw.any { it <= ' ' || it == '\u007f' || it == '\\' }) return Result.Reject("地址包含非法字符")
        val uri = try { URI(raw) } catch (_: Exception) { return Result.Reject("地址格式不合法") }
        val kind = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "udp" -> Kind.udp
            "http" -> Kind.http
            "https" -> Kind.https
            else -> return Result.Reject("不支持的协议")
        }
        if (uri.isOpaque || uri.rawFragment != null || uri.rawUserInfo != null) return Result.Reject("地址不应包含用户信息或 fragment")
        val authority = uri.rawAuthority ?: return Result.Reject("缺少主机")
        if (authority.contains('@') || authority.contains('%')) return Result.Reject("主机格式不合法")
        val ipv6 = authority.startsWith('[')
        val host: String
        val portText: String?
        if (ipv6) {
            val end = authority.indexOf(']')
            if (end < 0) return Result.Reject("IPv6 地址不合法")
            host = authority.substring(1, end).lowercase(Locale.ROOT)
            if (!host.contains(':') || numericAddress(host) == null) return Result.Reject("IPv6 地址不合法")
            val suffix = authority.substring(end + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return Result.Reject("端口格式不合法")
            portText = if (suffix.isEmpty()) null else suffix.substring(1)
        } else {
            if (authority.count { it == ':' } > 1) return Result.Reject("IPv6 必须使用方括号")
            val parts = authority.split(':', limit = 2)
            host = try { IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
                catch (_: Exception) { return Result.Reject("主机名不合法") }
            if (!validHostname(host)) return Result.Reject("主机名不合法")
            portText = parts.getOrNull(1)
        }
        val defaultPort = when (kind) { Kind.http -> 80; Kind.https -> 443; Kind.udp -> 0 }
        if (kind == Kind.udp && portText == null) return Result.Reject("UDP Tracker 需要显式端口")
        if (portText != null && (portText.isEmpty() || !portText.all { it in '0'..'9' })) return Result.Reject("端口不是数字")
        val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: 0
        if (port !in 1..65535) return Result.Reject("端口超出 1–65535")
        val path = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostPart = if (ipv6) "[$host]" else host
        val key = "${kind.name}://$hostPart${if (port == defaultPort) "" else ":$port"}$path"
        return Result.Ok(Parsed(raw, kind, host, ipv6, port, path, key))
    }

    fun validateSubscriptionUrl(raw: String): Result = when (val parsed = parse(raw)) {
        is Result.Reject -> parsed
        is Result.Ok -> when {
            parsed.value.kind != Kind.https -> Result.Reject("订阅地址必须使用 HTTPS")
            !isPublicHost(parsed.value.host) -> Result.Reject("订阅地址不是公网地址")
            else -> parsed
        }
    }
    private fun validHostname(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.all { it.isDigit() || it == '.' }) return strictIpv4(host)
        if (host.startsWith("0x", true)) return false
        return host.split('.').all { it.isNotEmpty() && it.length <= 63 &&
            !it.startsWith('-') && !it.endsWith('-') && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }
    }
    private fun strictIpv4(host: String): Boolean = host.split('.').let { parts ->
        parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } &&
            (p.length == 1 || p[0] != '0') && (p.toIntOrNull() ?: -1) in 0..255 }
    }
    private fun numericAddress(host: String): InetAddress? {
        if (!host.contains(':') && !strictIpv4(host)) return null
        if (host.contains('%')) return null
        return try { InetAddress.getByName(host) } catch (_: Exception) { null }
    }

    /** The bridge must additionally gate fixture injection on the debuggable flag. */
    @Volatile private var testLoopbackAllowList: Set<String> = emptySet()
    fun allowForTest(hosts: Collection<String>) {
        require(hosts.all { it == "127.0.0.1" || it == "::1" })
        testLoopbackAllowList = hosts.toSet()
    }
    fun clearTestAllowList() { testLoopbackAllowList = emptySet() }
    internal fun isTestAddress(address: InetAddress): Boolean = testLoopbackAllowList.any {
        numericAddress(it)?.address?.contentEquals(address.address) == true
    }

    fun isPublicHost(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if (value in testLoopbackAllowList) return true
        val address = numericAddress(value)
        if (address != null) return isPublicAddress(address)
        if (!validHostname(value) || !value.contains('.')) return false
        return listOf("localhost", "local", "lan", "internal", "home.arpa", "i2p", "onion")
            .none { value == it || value.endsWith(".$it") }
    }
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val c = b[1]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                a == 100 && c in 64..127 || a == 169 && c == 254 || a == 172 && c in 16..31 ||
                a == 192 && (c == 168 || c == 0 && (b[2] == 0 || b[2] == 2)) ||
                a == 198 && (c == 18 || c == 19 || c == 51 && b[2] == 100) ||
                a == 203 && c == 0 && b[2] == 113)
        }
        if (b.size != 16 || (b[0] and 0xe0) != 0x20) return false
        if (b[0] == 0x20 && b[1] == 0x02) return false
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8)) return false
        return true
    }

    data class TextParseResult(val valid: List<Parsed>, val duplicates: List<String>, val invalid: List<InvalidLine>)
    data class InvalidLine(val line: Int, val text: String, val reason: String)
    fun parseText(text: String, requirePublicHost: Boolean = false): TextParseResult {
        val valid = ArrayList<Parsed>(); val duplicate = ArrayList<String>(); val invalid = ArrayList<InvalidLine>()
        val seen = HashSet<String>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith('#')) when (val result = parse(line)) {
                is Result.Reject -> invalid.add(InvalidLine(index + 1, line.take(2048), result.reason))
                is Result.Ok -> when {
                    requirePublicHost && !isPublicHost(result.value.host) -> invalid.add(InvalidLine(index + 1, line, "不是公网地址"))
                    !seen.add(result.value.canonicalKey) -> duplicate.add(line)
                    else -> valid.add(result.value)
                }
            }
        }
        return TextParseResult(valid, duplicate, invalid)
    }
    fun displayHost(value: Parsed): String {
        val host = if (value.isIpv6) "[${value.host}]" else value.host
        return host + if (value.kind == Kind.https && value.port == 443 || value.kind == Kind.http && value.port == 80) "" else ":${value.port}"
    }
}
