package com.app.remotesync.network

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.app.remotesync.webrtc.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Android Network Service Discovery (NSD / mDNS) for LAN peer discovery.
 *
 * HOST side: call [registerService] to advertise the signaling WebSocket server.
 * CLIENT side: call [startDiscovery] to scan for available hosts.
 *
 * Discovered devices are exposed via [discoveredDevices].
 */
@Singleton
class NsdDiscoveryManager @Inject constructor(
    private val nsdManager: NsdManager,
    private val wifiManager: WifiManager
) {

    companion object {
        const val SERVICE_TYPE = "_remotesync._tcp."
        const val SERVICE_NAME = "RemoteSync"
    }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    // Multicast lock — required on some devices for mDNS to work
    private var multicastLock: WifiManager.MulticastLock? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Serialise concurrent resolve calls (NsdManager allows only one active at a time)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // ─────────────────────────────────────────────────────────────────────────
    // HOST — register service
    // ─────────────────────────────────────────────────────────────────────────

    fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Timber.i("NSD registered: ${info.serviceName} on port $port")
                _isRegistered.value = true
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Timber.i("NSD unregistered: ${info.serviceName}")
                _isRegistered.value = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Timber.w(e, "NSD unregisterService error")
            }
            registrationListener = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT — discover services
    // ─────────────────────────────────────────────────────────────────────────

    fun startDiscovery() {
        acquireMulticastLock()
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.i("NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Timber.d("NSD found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    enqueueResolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.d("NSD lost: ${serviceInfo.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value
                    .filterNot { it.name == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.i("NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("NSD start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("NSD stop discovery failed: $errorCode")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Timber.w(e, "NSD stopDiscovery error")
            }
            discoveryListener = null
        }
        releaseMulticastLock()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolve queue (serialised to satisfy NsdManager single-resolve constraint)
    // ─────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.addLast(serviceInfo)
        if (!resolving) processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (resolveQueue.isEmpty()) {
            resolving = false
            return
        }
        resolving = true
        val next = resolveQueue.removeFirst()
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.w("NSD resolve failed for ${info.serviceName}: $errorCode")
                processNextResolve()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                Timber.i("NSD resolved: ${info.serviceName} @ ${info.host?.hostAddress}:${info.port}")
                val host = info.host?.hostAddress ?: run {
                    processNextResolve()
                    return
                }
                val device = DiscoveredDevice(
                    name = info.serviceName,
                    host = host,
                    port = info.port
                )
                val current = _discoveredDevices.value.toMutableList()
                if (current.none { it.name == device.name }) {
                    current.add(device)
                    _discoveredDevices.value = current
                }
                processNextResolve()
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multicast lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("remotesync_nsd").apply {
                setReferenceCounted(true)
                acquire()
            }
            Timber.d("Multicast lock acquired")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
            multicastLock = null
            Timber.d("Multicast lock released")
        }
    }
}
