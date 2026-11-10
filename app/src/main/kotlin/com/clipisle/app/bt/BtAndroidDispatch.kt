package com.clipisle.app.bt

import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask

/**
 * Keeps owner teardown and the next user start in Android's main-loop order.
 * Callers are native workers; service callbacks only post to Runtime and never wait for it.
 */
internal object BtAndroidDispatch {
    private val main = Handler(Looper.getMainLooper())

    fun <T> call(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = FutureTask(action)
        check(main.post(result)) { "Android main loop is unavailable" }
        return try { result.get() } catch (e: InterruptedException) {
            result.cancel(false)
            Thread.currentThread().interrupt()
            throw e
        }
    }
}
