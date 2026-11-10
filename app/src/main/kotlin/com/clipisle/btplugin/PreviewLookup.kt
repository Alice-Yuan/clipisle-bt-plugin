package com.clipisle.btplugin

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Remote metadata probe used to show what a magnet or direct link contains
 * before the user commits to a download.
 *
 * The service only ever exposes torrent-level facts: name, total size, file
 * count and up to five extracted frames. It has no file listing, so this class
 * never reports a per-file breakdown. Anything the service cannot describe
 * comes back as [Preview.Unknown] and the UI must say so instead of guessing.
 */
internal object PreviewLookup {
    private const val ENDPOINT = "https://whatslink.info/api/v1/link?url="
    private const val TIMEOUT_MS = 20_000
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_SCREENSHOTS = 5

    sealed interface Preview {
        val input: String

        data class Known(
            override val input: String,
            val type: String,
            val fileType: String,
            val name: String,
            val sizeBytes: Long,
            val fileCount: Int,
            val screenshots: List<String>,
        ) : Preview

        /** The service answered but could not identify the content. */
        data class Unknown(override val input: String) : Preview

        /** Transport or protocol failure; distinct from "we do not know this". */
        data class Failed(override val input: String, val reason: String) : Preview
    }

    fun lookup(input: String, transport: (String) -> String = ::httpGet): Preview {
        val target = input.trim()
        if (target.isEmpty() || target.length > 4096) {
            return Preview.Failed(target, "请输入有效的链接")
        }
        val payload = try {
            transport(ENDPOINT + URLEncoder.encode(target, "UTF-8"))
        } catch (e: Exception) {
            return Preview.Failed(target, e.message ?: "网络请求失败")
        }
        return parse(target, payload)
    }

    /** Split out so the wire format can be tested without a network. */
    fun parse(target: String, payload: String): Preview {
        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return Preview.Failed(target, "服务返回了无法识别的数据")
        }
        val declaredError = json.optString("error", "")
        if (declaredError.isNotEmpty()) return Preview.Failed(target, declaredError)

        val type = json.optString("type", "UNKNOWN").uppercase()
        val fileType = json.optString("file_type", "").lowercase()
        val name = json.optString("name", "").trim()
        if (type == "UNKNOWN" || name.isEmpty()) return Preview.Unknown(target)

        val size = json.optLong("size", 0L).coerceAtLeast(0L)
        val count = json.optInt("count", 0).coerceAtLeast(0)
        val shots = json.optJSONArray("screenshots")
        val screenshots = ArrayList<String>(MAX_SCREENSHOTS)
        if (shots != null) {
            for (i in 0 until shots.length()) {
                if (screenshots.size >= MAX_SCREENSHOTS) break
                val url = shots.optJSONObject(i)?.optString("screenshot", "") ?: ""
                if (url.startsWith("https://")) screenshots.add(url)
            }
        }
        return Preview.Known(target, type, fileType, name, size, count, screenshots)
    }

    private fun httpGet(address: String): String {
        var url = URL(address)
        repeat(4) {
            require(url.protocol == "https") { "预览服务必须使用 HTTPS" }
            require(url.host.lowercase() == "whatslink.info") { "预览服务域名不符" }
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ClipIsle-BT-Plugin")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location") ?: error("重定向缺少目标")
                    url = URL(url, next)
                    return@repeat
                }
                if (code != 200) error("预览服务返回 HTTP $code")
                return connection.inputStream.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        require(out.size() + read <= MAX_BYTES) { "预览响应过大" }
                        out.write(buffer, 0, read)
                    }
                    out.toString("UTF-8")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("预览服务重定向次数过多")
    }
}
