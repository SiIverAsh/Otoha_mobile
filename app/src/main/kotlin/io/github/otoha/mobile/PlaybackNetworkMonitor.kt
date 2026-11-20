package io.github.otoha.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread

internal class PlaybackNetworkMonitor(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetwork: Network? = null
    private var online: Boolean? = null

    @MainThread
    fun start() {
        if (callback != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback === this) defaultNetwork = network
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (callback === this && network == defaultNetwork) update(capabilities)
            }

            override fun onLost(network: Network) {
                if (callback === this && network == defaultNetwork) {
                    defaultNetwork = null
                    update(null)
                }
            }
        }
        callback = listener
        connectivity.registerDefaultNetworkCallback(listener, mainHandler)
        // Events are queued on this same looper. Register first so changes during the snapshot are delivered.
        defaultNetwork = connectivity.activeNetwork
        update(defaultNetwork?.let(connectivity::getNetworkCapabilities))
    }

    private fun update(capabilities: NetworkCapabilities?) {
        val available = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (online == available) return
        online = available
        onChanged(available)
    }

    @MainThread
    fun close() {
        val previous = callback ?: return
        callback = null
        connectivity.unregisterNetworkCallback(previous)
        defaultNetwork = null
        online = null
    }
}
