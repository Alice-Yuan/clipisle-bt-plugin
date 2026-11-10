package com.clipisle.app.bt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Checks the default network used by native sockets, without rebinding the entire process. */
class BtNetworkPolicy(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    @Volatile private var blockedNetwork: Network? = null
    data class Event(val satisfied: Boolean, val epoch: Long)
    private val eventListeners = CopyOnWriteArraySet<(Event) -> Unit>()
    @Volatile var epoch: Long = 0
        private set

    /** 网络代次，用作 Tracker 健康度的隔离键。换网后旧结论作废。 */
    fun routeKey(): String = "net-$epoch"

    private var identity: String? = null

    @Volatile
    var unmeteredOnly: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            emit()
        }

    fun isSatisfied(): Boolean = try {
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        network != blockedNetwork && capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!unmeteredOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    } catch (_: Exception) {
        // Missing connectivity information must not allow a cellular transfer by default.
        false
    }

    fun isWifi(): Boolean = try {
        val network = manager?.activeNetwork
        network != null && manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    @Synchronized private fun emit() {
        val currentIdentity = runCatching {
            val network = manager?.activeNetwork
            val caps = network?.let { manager?.getNetworkCapabilities(it) }
            val transports = (0..8).filter { caps?.hasTransport(it) == true }
            "${network?.networkHandle}:$transports"
        }.getOrNull()
        if (identity != currentIdentity) { identity = currentIdentity; epoch++ }
        val satisfied = isSatisfied()
        for (listener in listeners) runCatching { listener(satisfied) }
        for (listener in eventListeners) runCatching { listener(Event(satisfied, epoch)) }
    }

    fun registerEvents(onChanged: (Event) -> Unit): AutoCloseable? {
        eventListeners.add(onChanged)
        val registration = register { } ?: run { eventListeners.remove(onChanged); return null }
        emit()
        return AutoCloseable { eventListeners.remove(onChanged); registration.close() }
    }

    fun register(onChanged: (Boolean) -> Unit): AutoCloseable? {
        val connectivity = manager ?: return null
        val closed = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (!closed.get()) emit() }
            override fun onLost(network: Network) { if (!closed.get()) emit() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!closed.get()) emit()
            }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!closed.get()) {
                    if (blocked) blockedNetwork = network
                    else if (blockedNetwork == network) blockedNetwork = null
                    emit()
                }
            }
        }
        return try {
            listeners.add(onChanged)
            connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            onChanged(isSatisfied())
            AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    listeners.remove(onChanged)
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                }
            }
        } catch (e: Exception) {
            listeners.remove(onChanged)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            Log.w("BtNetwork", "Default network observer unavailable: ${e.javaClass.simpleName}")
            null
        }
    }
}
