package com.tvware.launcher.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class NetworkStatus(
    val isWifiConnected: Boolean = false,
    val wifiLevel: Int = 0,
    val isEthernetConnected: Boolean = false,
    val isVpnActive: Boolean = false,
)

class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val wifiNetworks = ConcurrentHashMap<Network, Int>()
    private val ethernetNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val vpnNetworks = ConcurrentHashMap.newKeySet<Network>()
    private var registered = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm?.getNetworkCapabilities(network)
            wifiNetworks[network] = rssiToLevel(caps?.signalStrength ?: Int.MIN_VALUE)
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            wifiNetworks[network] = rssiToLevel(caps.signalStrength)
            publish()
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            publish()
        }
    }

    private val ethernetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ethernetNetworks.add(network)
            publish()
        }

        override fun onLost(network: Network) {
            ethernetNetworks.remove(network)
            publish()
        }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            vpnNetworks.add(network)
            publish()
        }

        override fun onLost(network: Network) {
            vpnNetworks.remove(network)
            publish()
        }
    }

    fun start() {
        if (registered || cm == null) return
        registered = true

        cm.activeNetwork?.let { active ->
            cm.getNetworkCapabilities(active)?.let { caps ->
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks[active] = rssiToLevel(caps.signalStrength)
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethernetNetworks.add(active)
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnNetworks.add(active)
                }
            }
        }
        publish()

        val wifiReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val ethReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val vpnReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        runCatching { cm.registerNetworkCallback(wifiReq, wifiCallback) }
        runCatching { cm.registerNetworkCallback(ethReq, ethernetCallback) }
        runCatching { cm.registerNetworkCallback(vpnReq, vpnCallback) }
    }

    fun stop() {
        if (!registered || cm == null) return
        registered = false
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(ethernetCallback) }
        runCatching { cm.unregisterNetworkCallback(vpnCallback) }
        wifiNetworks.clear()
        ethernetNetworks.clear()
        vpnNetworks.clear()
    }

    private fun publish() {
        val bestWifi = wifiNetworks.values.maxOrNull() ?: 0
        _status.update {
            NetworkStatus(
                isWifiConnected = wifiNetworks.isNotEmpty(),
                wifiLevel = bestWifi,
                isEthernetConnected = ethernetNetworks.isNotEmpty(),
                isVpnActive = vpnNetworks.isNotEmpty(),
            )
        }
    }

    private fun rssiToLevel(rssi: Int): Int = when {
        rssi == Int.MIN_VALUE -> 4
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
}
