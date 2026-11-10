package com.clipisle.app.bt

import android.util.Log
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.swig.libtorrent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 唯一接触 jlibtorrent 细节的适配层。
 *
 * 第一版只做三件事：
 *  1. 验证原生库能真正加载（不是只看依赖打包成功）
 *  2. 起一个最小会话，报告引擎版本
 *  3. 释放，确认没有崩溃
 *
 * 真正的任务调度、元数据获取、文件优先级都在后续阶段。
 * 这个类有意保持极薄 —— 它存在的意义是让 P0 能被真机证伪。
 */
object BtEngine {
    private const val TAG = "BtEngine"

    /** 原生库加载结果。null 表示还没试过。 */
    @Volatile
    private var loaded: Result<String>? = null

    private val initializing = AtomicBoolean(false)

    /**
     * 引擎信息。加载失败也返回可读结果，不抛异常 ——
     * 调用方（Dart 侧）需要据此展示「BT 不可用」而不是整个页面崩掉。
     */
    fun capabilities(): Map<String, Any> {
        val load = ensureLoaded()
        val base = mutableMapOf<String, Any>(
            "available" to load.isSuccess,
            "apiLevel" to android.os.Build.VERSION.SDK_INT,
            "supportedAbis" to android.os.Build.SUPPORTED_ABIS.toList(),
            "protocolVersion" to 1,
        )

        load.fold(
            onSuccess = { version ->
                base["engine"] = "jlibtorrent"
                base["libtorrentVersion"] = version
                base["jlibtorrentVersion"] = jlibtorrentVersion()
            },
            onFailure = { e ->
                base["engine"] = "unavailable"
                base["errorCode"] = classify(e)
                // 只给脱敏摘要，原始异常留在 logcat
                base["errorDetail"] = e.javaClass.simpleName +
                    (e.message?.take(120)?.let { ": $it" } ?: "")
            },
        )
        return base
    }

    /**
     * 加载原生库并取版本号。
     *
     * 用 synchronized 保证并发安全：多线程同时点「查看文件」时
     * 不能重复 dlopen。
     */
    @Synchronized
    private fun ensureLoaded(): Result<String> {
        loaded?.let { return it }

        val result = try {
            // System.loadLibrary 由 jlibtorrent 的静态块触发；
            // 显式触碰一次 libtorrent 类，把加载时机控制在这里。
            val version = libtorrent.version()
            Log.i(TAG, "libtorrent loaded, version=$version")
            Result.success(version)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError 都要接住，
            // 否则会变成整个 App 的崩溃。
            Log.e(TAG, "native load failed", t)
            Result.failure(t)
        }
        loaded = result
        return result
    }

    private fun jlibtorrentVersion(): String = try {
        // SessionManager 的包版本由 AAR manifest 提供，这里用常量兜底。
        "2.0.15.3"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun classify(e: Throwable): String = when (e) {
        is UnsatisfiedLinkError -> "ENGINE_UNAVAILABLE"
        is ExceptionInInitializerError -> "ENGINE_UNAVAILABLE"
        is NoClassDefFoundError -> "ENGINE_UNAVAILABLE"
        else -> "ENGINE_UNAVAILABLE"
    }

    /**
     * 最小冒烟测试：起会话 → 取版本 → 停会话。
     *
     * P0 用它证明「引擎真的能跑」，而不是「依赖打包成功」。
     */
    fun smokeTest(): Map<String, Any> {
        val caps = capabilities()
        if (caps["available"] != true) {
            return caps + mapOf("smoke" to "skipped", "reason" to "engine unavailable")
        }

        var session: SessionManager? = null
        val startedAt = System.currentTimeMillis()
        return try {
            // 冒烟测试不联网：关掉 DHT / LSD，避免在验证阶段产生 P2P 流量。
            //
            // 注意 API 位置：这些开关在 SettingsPack 上，不在 SessionParams 上。
            // 用 javap 核对过 2.0.15.3 的真实签名：
            //   SessionParams 只有 swig()/getSettings()/setSettings()/setPosixDiskIO()
            //   SettingsPack 才有 setEnableDht(boolean) / setEnableLsd(boolean)
            val settings = SettingsPack()
            settings.setEnableDht(false)
            settings.setEnableLsd(false)

            val params = SessionParams(settings)

            session = SessionManager()
            session.start(params)

            val elapsed = System.currentTimeMillis() - startedAt
            caps + mapOf(
                "smoke" to "ok",
                "startupMs" to elapsed,
                "sessionStarted" to true,
                "running" to session.isRunning(),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "smoke test failed", t)
            caps + mapOf(
                "smoke" to "failed",
                "reason" to (t.javaClass.simpleName + ": " + (t.message ?: "")),
            )
        } finally {
            try {
                session?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "session stop failed", t)
            }
        }
    }
}
