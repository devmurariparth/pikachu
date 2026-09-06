package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.AssistantLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NetworkConnectivityManager {
    private const val TAG = "NetworkManager"

    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val _networkType = MutableStateFlow("Unknown")
    val networkType: StateFlow<String> = _networkType.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false

    fun init(context: Context) {
        if (isRegistered) return
        val appContext = context.applicationContext
        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (connectivityManager == null) {
            AssistantLogger.w(TAG, "ConnectivityManager is unavailable on this device.")
            return
        }

        // Initial check
        checkInitialNetworkState()

        try {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val capabilities = connectivityManager?.getNetworkCapabilities(network)
                    val type = determineNetworkType(capabilities)
                    _isNetworkAvailable.value = true
                    _networkType.value = type
                    AssistantLogger.i(TAG, "Network available ($type). Online services enabled.")
                }

                override fun onLost(network: Network) {
                    _isNetworkAvailable.value = false
                    _networkType.value = "None"
                    AssistantLogger.w(TAG, "Network connection lost. Activating offline fallbacks.")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    } else true

                    val available = hasInternet && isValidated
                    val type = determineNetworkType(networkCapabilities)
                    _isNetworkAvailable.value = available
                    _networkType.value = type
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            }
            isRegistered = true
            AssistantLogger.i(TAG, "Network callback registered successfully.")
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Failed to register network callback: ${e.message}", e)
            checkInitialNetworkState()
        }
    }

    private fun checkInitialNetworkState() {
        val cm = connectivityManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                val isOnline = capabilities?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } ?: false
                _isNetworkAvailable.value = isOnline
                _networkType.value = determineNetworkType(capabilities)
            } else {
                @Suppress("DEPRECATION")
                val activeInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                val isConnected = activeInfo != null && activeInfo.isConnected
                _isNetworkAvailable.value = isConnected
                _networkType.value = if (isConnected) "Active" else "None"
            }
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "Exception during initial network check: ${e.message}")
            _isNetworkAvailable.value = true // Assume optimistic
        }
    }

    private fun determineNetworkType(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "None"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }
    }
}
