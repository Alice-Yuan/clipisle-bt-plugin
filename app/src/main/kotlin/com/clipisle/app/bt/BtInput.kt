package com.clipisle.app.bt

import java.security.MessageDigest
import java.util.Locale
import java.net.URLDecoder

/**
 * 磁力 / BT 的输入分类与规范化。
 *
 * 任务书要求：
 *  - 保留重复的 xt / tr 参数（多个 hash 别名、多个 tracker 都是有效的）
 *  - 不破坏编码后的 magnet（不能用 HTTP 那套尾部标点清理）
 *  - 支持 v1 btih 的十六进制与 Base32、v2 btmh、hybrid 多 hash
 *  - Dart 只做快速分类，**最终身份由引擎校验**
 */
object BtInput {

    /** 磁力输入上限。任务书建议 64 KB。 */
    const val MAX_MAGNET_BYTES = 64 * 1024

    /** 解析结果。 */
    data class MagnetInfo(
        /** 规范化后的磁力链接（原样保留，不改写） */
        val magnet: String,
        /** v1 hash，小写十六进制；没有则 null */
        val infoHashV1Hex: String?,
        /** v1 hash 的 Base32 形式；没有则 null */
        val infoHashV1Base32: String?,
        /** v2 hash，小写十六进制；没有则 null */
        val infoHashV2Hex: String?,
        /** 显示名 dn 参数 */
        val displayName: String,
        val trackers: List<String>,
        /** 用于去重的稳定键：优先 v1，其次 v2 */
        val dedupeKey: String,
        val isHybrid: Boolean,
    ) {
        val hasV1: Boolean get() = infoHashV1Hex != null
        val hasV2: Boolean get() = infoHashV2Hex != null

        /** Every supplied hash must match, including when metadata came from a cache. */
        fun matchesHashes(actualV1: String?, actualV2: String?): Boolean =
            (hasV1 || hasV2) &&
                (!hasV1 || infoHashV1Hex == actualV1) &&
                (!hasV2 || infoHashV2Hex == actualV2)
    }

    sealed class ParseResult {
        data class Ok(val info: MagnetInfo) : ParseResult()
        data class Fail(val code: String, val message: String) : ParseResult()
    }

    /**
     * 从一段文本里提取磁力链接。
     *
     * 分享文案常是「看看这个 magnet:?xt=... 很好」这种，
     * 所以要能找到磁力本体并保留到结束。
     */
    fun extractMagnets(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // 磁力以 magnet:? 开头，后面是非空白字符。
        // 用非贪婪边界：遇到空白、引号、中文标点停止。
        val regex = Regex(
            """magnet:\?[^\s"'<>，。、；：！？）】」』]+""",
            RegexOption.IGNORE_CASE,
        )
        for (m in regex.findAll(text)) {
            val raw = m.value.trim()
            if (raw.length > 8) out.add(raw)
        }
        return out.toList()
    }

    /** 是否是磁力链接（快速判断） */
    fun looksLikeMagnet(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("magnet:?", ignoreCase = true) ||
            extractMagnets(t).isNotEmpty()
    }

    /** 解析单条磁力。 */
    fun parse(magnet: String): ParseResult {
        val raw = magnet.trim()
        if (raw.isEmpty()) return ParseResult.Fail("INVALID_MAGNET", "磁力链接是空的")
        if (!raw.startsWith("magnet:?", ignoreCase = true)) {
            return ParseResult.Fail("INVALID_MAGNET", "不是磁力链接")
        }
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MAGNET_BYTES) {
            return ParseResult.Fail("INVALID_MAGNET", "磁力链接过长")
        }
        if (raw.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) {
            return ParseResult.Fail("INVALID_MAGNET", "磁力链接包含未编码的空白或控制字符")
        }

        // 注意：不能用 Uri.getQueryParameters。
        //
        // magnet:?xt=... 是 opaque URI（没有 authority / path），
        // Android 的 Uri 对它返回空的 query parameters —— 真机实测
        // 会直接判成「缺少 xt 参数」。这里像 Dart 侧一样手工解析查询串，
        // 也能原样保留重复的 xt / tr。
        val qIndex = raw.indexOf('?')
        val query = if (qIndex < 0) "" else raw.substring(qIndex + 1).substringBefore('#')
        val pairs = LinkedHashMap<String, MutableList<String>>()
        for (seg in query.split('&')) {
            if (seg.isEmpty()) continue
            val eq = seg.indexOf('=')
            if (eq <= 0) continue
            val k: String
            val v: String
            try {
                k = URLDecoder.decode(seg.substring(0, eq), "UTF-8").lowercase(Locale.ROOT)
                v = URLDecoder.decode(seg.substring(eq + 1), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return ParseResult.Fail("INVALID_MAGNET", "磁力链接的编码不正确")
            }
            pairs.getOrPut(k) { ArrayList() }.add(v)
        }

        val xts = pairs["xt"]?.filter { it.isNotEmpty() } ?: emptyList()
        if (xts.isEmpty()) {
            return ParseResult.Fail("INVALID_MAGNET", "磁力缺少 xt（内容哈希）参数")
        }

        var v1Hex: String? = null
        var v1Base32: String? = null
        var v2Hex: String? = null

        for (xt in xts) {
            val lower = xt.lowercase(Locale.ROOT)
            when {
                lower.startsWith("urn:btih:") -> {
                    val body = xt.substring("urn:btih:".length)
                    val hash = when {
                        body.length == 40 && isHex(body) -> body.lowercase(Locale.ROOT)
                        body.length == 32 -> base32ToHex(body)
                        else -> null
                    } ?: return ParseResult.Fail("INVALID_MAGNET", "磁力里的 v1 内容哈希不正确")
                    if (v1Hex != null && v1Hex != hash) {
                        return ParseResult.Fail("INVALID_MAGNET", "磁力包含互相冲突的 v1 内容哈希")
                    }
                    v1Hex = hash
                    if (body.length == 32) v1Base32 = body.uppercase(Locale.ROOT)
                }
                lower.startsWith("urn:btmh:") -> {
                    // v2 是 multihash：0x12 0x20 <32 字节 sha256>
                    val body = xt.substring("urn:btmh:".length)
                    // BEP-52 requires a SHA-256 multihash (1220 + 32 bytes),
                    // not a bare 64-character digest or non-hex payload.
                    if (body.length != 68 || !body.startsWith("1220", true) || !isHex(body)) {
                        return ParseResult.Fail("INVALID_MAGNET", "磁力里的 v2 内容哈希不正确")
                    }
                    val hash = body.substring(4).lowercase(Locale.ROOT)
                    if (v2Hex != null && v2Hex != hash) {
                        return ParseResult.Fail("INVALID_MAGNET", "磁力包含互相冲突的 v2 内容哈希")
                    }
                    v2Hex = hash
                }
            }
        }

        if (v1Hex == null && v2Hex == null) {
            return ParseResult.Fail("INVALID_MAGNET", "磁力里的内容哈希无法识别")
        }

        val dn = (pairs["dn"]?.firstOrNull() ?: "")
            .filterNot { it.code < 32 || it.code == 127 }.take(300)
        val trackers = pairs["tr"]?.toList() ?: emptyList()

        val dedupeKey = v1Hex ?: "v2:$v2Hex"

        return ParseResult.Ok(
            MagnetInfo(
                magnet = raw,
                infoHashV1Hex = v1Hex,
                infoHashV1Base32 = v1Base32,
                infoHashV2Hex = v2Hex,
                displayName = dn,
                trackers = trackers,
                dedupeKey = dedupeKey,
                isHybrid = v1Hex != null && v2Hex != null,
            ),
        )
    }

    /** 两条磁力是否指向同一内容（任一侧 hash 命中即可） */
    fun sameContent(a: MagnetInfo, b: MagnetInfo): Boolean {
        if (a.infoHashV1Hex != null && a.infoHashV1Hex == b.infoHashV1Hex) return true
        if (a.infoHashV2Hex != null && a.infoHashV2Hex == b.infoHashV2Hex) return true
        return false
    }

    private fun isHex(s: String): Boolean =
        s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * Base32 → 十六进制。
     *
     * RFC 4648 Base32 字母表（A–Z, 2–7）。BT 的 Base32 编码是
     * 20 字节 SHA-1 的标准 Base32，无需 padding 时长度 32。
     */
    private fun base32ToHex(input: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val s = input.uppercase(Locale.ROOT).trimEnd('=')
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in s) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) return null
            buffer = (buffer shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size != 20) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 短标识：用于任务目录名，取 dedupeKey 的前 8 位 */
    fun shortId(key: String): String {
        val h = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return h.substring(0, 8)
    }
}
