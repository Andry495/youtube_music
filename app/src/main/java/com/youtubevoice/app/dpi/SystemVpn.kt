package com.youtubevoice.app.dpi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Detects an already-active system / third-party VPN (conflicts with in-app DpiVpnService). */
object SystemVpn {
    fun isActive(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }
}
