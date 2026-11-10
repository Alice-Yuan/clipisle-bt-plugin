package com.clipisle.app.bt

import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * BT 文件路径的安全检查。
 *
 * 任务书 6.2 的硬要求：
 *  - 拒绝绝对路径、盘符、`..`、NUL、越界路径、符号链接 / 硬链接
 *  - 归一化后冲突要检测（外置存储可能大小写不敏感）
 *  - 不能把多个危险名称清洗成同一个文件然后覆盖
 *  - 元数据解析、实际路径、打开/导出/删除都必须落在任务根目录内
 *
 * 这是安全边界，不是格式化工具：**校验失败就拒绝整个任务**，
 * 不做「尽力修复」。
 */
object BtPathGuard {

    /** 路径嵌套层数上限（任务书建议 32） */
    const val MAX_DEPTH = 32

    /** 单个文件数上限（任务书建议 20000） */
    const val MAX_FILES = 20_000

    sealed class Result {
        data class Ok(val relativePath: String) : Result()
        data class Reject(val code: String, val reason: String) : Result()
    }

    /**
     * 归一化并校验一个种子内部的相对路径。
     *
     * 返回的 relativePath 使用 `/` 分隔，可以直接拼到任务根目录下。
     */
    fun sanitize(relative: String): Result {
        if (relative.isEmpty()) return Result.Reject("UNSAFE_PATH", "文件名为空")
        if (relative.toByteArray(Charsets.UTF_8).size > 4096) return Result.Reject("UNSAFE_PATH", "路径过长")

        // NUL 及其他控制字符
        if (relative.any { it.code < 32 || it.code == 127 }) {
            return Result.Reject("UNSAFE_PATH", "路径包含控制字符")
        }

        // Never publish a different path from the one libtorrent writes.
        // Renaming would require an explicit engine rename operation.
        if ('\\' in relative) return Result.Reject("UNSAFE_PATH", "路径包含不支持的分隔符")
        val unified = relative

        // 绝对路径 / 盘符 / UNC
        if (unified.startsWith("/")) {
            return Result.Reject("UNSAFE_PATH", "不允许绝对路径")
        }
        if (Regex("^[A-Za-z]:").containsMatchIn(unified)) {
            return Result.Reject("UNSAFE_PATH", "不允许盘符路径")
        }
        if (unified.startsWith("//")) {
            return Result.Reject("UNSAFE_PATH", "不允许 UNC 路径")
        }

        val segments = unified.split('/')
        if (segments.isEmpty()) return Result.Reject("UNSAFE_PATH", "路径为空")
        if (segments.size > MAX_DEPTH) {
            return Result.Reject("UNSAFE_PATH", "目录层级超过 $MAX_DEPTH")
        }

        val cleaned = ArrayList<String>(segments.size)
        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") {
                return Result.Reject("UNSAFE_PATH", "路径包含上级引用")
            }
            if (seg.toByteArray(Charsets.UTF_8).size > 255 || seg.any { it in ":*?\"<>|" }) {
                return Result.Reject("UNSAFE_PATH", "文件名过长或包含不支持的字符")
            }
            // Windows 保留名（外置存储可能被挂到 Windows 上）
            if (isWindowsReserved(seg)) {
                return Result.Reject("UNSAFE_PATH", "路径使用了保留名称")
            }
            // 尾部的点和空格在 Windows 上会被吞掉，可能导致同名覆盖
            val trimmed = seg.trimEnd('.', ' ')
            if (trimmed != seg) {
                return Result.Reject("UNSAFE_PATH", "文件名不能以点或空格结尾")
            }
            cleaned.add(seg)
        }

        if (cleaned.isEmpty()) return Result.Reject("UNSAFE_PATH", "路径为空")
        return Result.Ok(cleaned.joinToString("/"))
    }

    /**
     * 批量校验文件列表，并检测归一化冲突。
     *
     * 冲突判定用 NFC + 小写比较：外置存储可能是大小写不敏感的
     * exFAT/FAT32，`a.txt` 与 `A.txt` 会互相覆盖。
     */
    fun sanitizeAll(paths: List<String>): AllResult {
        if (paths.size > MAX_FILES) {
            return AllResult.Reject("UNSAFE_PATH", "文件数超过 $MAX_FILES")
        }

        val out = ArrayList<String>(paths.size)
        val seen = HashMap<String, String>(paths.size)
        val directories = HashSet<String>()
        for ((index, p) in paths.withIndex()) {
            when (val r = sanitize(p)) {
                is Result.Ok -> {
                    val key = normalizeForKey(r.relativePath)
                    if (key in directories) {
                        return AllResult.Reject("UNSAFE_PATH", "文件路径与目录冲突")
                    }
                    var parent = key.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        if (parent in seen) {
                            return AllResult.Reject("UNSAFE_PATH", "目录路径与文件冲突")
                        }
                        directories.add(parent)
                        parent = parent.substringBeforeLast('/', "")
                    }
                    val prev = seen.put(key, r.relativePath)
                    if (prev != null && prev != r.relativePath) {
                        return AllResult.Reject(
                            "UNSAFE_PATH",
                            "第 ${index + 1} 个文件与「$prev」归一化后冲突",
                        )
                    }
                    if (prev != null && prev == r.relativePath) {
                        return AllResult.Reject(
                            "UNSAFE_PATH",
                            "第 ${index + 1} 个文件路径重复",
                        )
                    }
                    out.add(r.relativePath)
                }
                is Result.Reject -> return AllResult.Reject(r.code, r.reason)
            }
        }
        return AllResult.Ok(out)
    }

    sealed class AllResult {
        data class Ok(val paths: List<String>) : AllResult()
        data class Reject(val code: String, val reason: String) : AllResult()
    }

    /**
     * 确认 target 仍在 root 之内（防符号链接逃逸）。
     *
     * 用 canonicalPath 比较，能识破 `../` 与符号链接。
     * 注意：调用前 root 应已存在。
     */
    fun isInside(root: File, target: File): Boolean {
        return try {
            val rootPath = root.canonicalPath
            val targetPath = target.canonicalPath
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 运行前的链接检查：如果目标路径上已经存在符号链接，拒绝。
     *
     * 任务书要求「运行前检查预先存在的链接，清理时不跟随链接递归」。
     */
    fun hasSymlinkOnPath(root: File, relative: String): Boolean {
        if (sanitize(relative) is Result.Reject) return true
        var cur = root
        if (isSymlink(root)) return true
        val segs = relative.split('/').filter { it.isNotEmpty() }
        for ((i, seg) in segs.withIndex()) {
            cur = File(cur, seg)
            if (isSymlink(cur)) return true
            // File.exists() follows links and misses dangling symlinks.
            if (!Files.exists(cur.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.isRegularFile(cur.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                // Android's Unix provider exposes link counts. Writing an
                // existing hardlink would modify data outside our owned root.
                val links = try {
                    Files.getAttribute(cur.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number
                } catch (_: UnsupportedOperationException) {
                    null // FAT/host test providers may not expose Unix attrs.
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: java.io.IOException) {
                    return true
                }
                if (links != null && links.toLong() > 1) return true
            } else if (!Files.isDirectory(cur.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return true
            }
            if (i == segs.size - 1) break
            if (!cur.isDirectory) return true
        }
        return false
    }

    private fun isSymlink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        true
    }

    private fun normalizeForKey(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun isWindowsReserved(seg: String): Boolean {
        val base = seg.substringBefore('.').uppercase(Locale.ROOT)
        return base in WINDOWS_RESERVED
    }

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
}
