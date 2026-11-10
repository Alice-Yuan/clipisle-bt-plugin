package com.clipisle.btplugin

import com.clipisle.app.bt.BtInput
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Third-party hints only; local torrent metadata remains authoritative. */
internal object PreviewLookup {
    private const val ENDPOINT = "https://whatslink.info/api/v1/link?url="
    private val requests = PreviewRequests()
    private val http = PreviewHttpTransport()

    sealed interface Preview {
        val input: String
        data class Known(
            override val input: String,
            val type: String,
            val fileType: String,
            val name: String,
            val sizeBytes: Long?,
            val fileCount: Int?,
            val screenshots: List<String>,
        ) : Preview
        data class Unknown(override val input: String) : Preview
        data class Failed(override val input: String, val reason: String) : Preview
    }

    fun lookup(input: String, requestId: String): Preview {
        val session = requests.begin(requestId)
            ?: return Preview.Failed(input, "预览请求过多，请稍后重试")
        return try {
            lookup(input) { address -> http.get(address, session) }
        } finally {
            requests.finish(requestId, session)
        }
    }

    fun cancel(requestId: String): Boolean = requests.cancel(requestId)
    fun cancelAll() = requests.cancelAll()

    /** Injection keeps parser and privacy tests offline. Never forward private magnet parameters. */
    fun lookup(input: String, transport: (String) -> String): Preview {
        val target = input.trim()
        val identity = publicIdentity(target)
            ?: return Preview.Failed(target, "请输入有效的磁力链接")
        val payload = try {
            transport(ENDPOINT + URLEncoder.encode(identity, "UTF-8"))
        } catch (e: PreviewTransportException) {
            return Preview.Failed(target, e.safeMessage)
        } catch (_: Exception) {
            return Preview.Failed(target, "暂时无法获取预览，可以直接选择文件")
        }
        return parse(target, payload)
    }

    fun publicIdentity(input: String): String? {
        val info = (BtInput.parse(input) as? BtInput.ParseResult.Ok)?.info ?: return null
        return "magnet:?" + buildList {
            info.infoHashV1Hex?.let { add("xt=urn:btih:$it") }
            info.infoHashV2Hex?.let { add("xt=urn:btmh:1220$it") }
        }.joinToString("&")
    }

    fun parse(target: String, payload: String): Preview {
        if (payload.toByteArray(Charsets.UTF_8).size > PreviewHttpTransport.MAX_BYTES) {
            return Preview.Failed(target, "预览服务返回的数据无效")
        }
        val json = try { JSONObject(payload) } catch (_: Exception) {
            return Preview.Failed(target, "预览服务返回的数据无效")
        }
        if (json.optString("error", "").isNotEmpty()) {
            return Preview.Failed(target, "暂时无法获取预览，可以直接选择文件")
        }
        val type = json.optString("type", "UNKNOWN").uppercase(Locale.ROOT)
        val fileType = json.optString("file_type", "").lowercase(Locale.ROOT)
        val name = (json.opt("name") as? String).orEmpty().filterNot { it.code < 32 || it.code == 127 }.trim().take(500)
        if (type !in setOf("FILE", "FOLDER") || name.isEmpty()) return Preview.Unknown(target)
        val size = nonnegativeInteger(json.opt("size"), Long.MAX_VALUE)
        val count = nonnegativeInteger(json.opt("count"), Int.MAX_VALUE.toLong())?.toInt()
        val shots = json.optJSONArray("screenshots")
        val screenshots = LinkedHashSet<String>()
        if (shots != null) for (i in 0 until shots.length()) {
            if (screenshots.size >= 5) break
            val url = shots.optJSONObject(i)?.optString("screenshot", "") ?: continue
            val parsed = allowedUrl(url)
            if (parsed != null && parsed.encodedPath.startsWith("/image/") && parsed.encodedPath.length > 7) {
                screenshots.add(parsed.toString())
            }
        }
        return Preview.Known(target, type, fileType, name, size, count, screenshots.toList())
    }

    private fun nonnegativeInteger(value: Any?, maximum: Long): Long? {
        val text = value?.toString() ?: return null
        if (!text.matches(Regex("[0-9]{1,19}"))) return null
        return text.toLongOrNull()?.takeIf { it <= maximum }
    }

    fun allowedUrl(address: String): HttpUrl? {
        if (address.length > 4096) return null
        val url = address.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.scheme == "https" && it.host == "whatslink.info" && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        }
    }
}
