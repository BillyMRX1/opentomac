package dev.opentomac.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val availableNetworks = mutableSetOf<Network>()

    private val mutableWifiAvailable = MutableStateFlow(hasWifiOrEthernetNow())
    val wifiAvailable: StateFlow<Boolean> = mutableWifiAvailable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val hasAny = synchronized(availableNetworks) {
                availableNetworks += network
                availableNetworks.isNotEmpty()
            }
            mutableWifiAvailable.value = hasAny
        }

        override fun onLost(network: Network) {
            val hasAny = synchronized(availableNetworks) {
                availableNetworks -= network
                availableNetworks.isNotEmpty()
            }
            mutableWifiAvailable.value = hasAny
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun hasWifiOrEthernetNow(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
