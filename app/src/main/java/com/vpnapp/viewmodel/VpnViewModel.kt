package com.vpnapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vpnapp.data.*
import com.vpnapp.vpn.VpnConnectionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import android.util.Log 
import java.net.HttpURLConnection
import java.net.URL
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import com.vpnapp.vpn.singbox.ProbeEndpoint
import com.vpnapp.util.ProbeHttp

class VpnViewModel @JvmOverloads constructor(application: Application,
    private val subscriptionFetcher: (String, String, String?) -> Pair<VpnSubscription, List<VpnProfile>>? = V2RayLinkParser::fetchSubscriptionOrThrow,
    private val latencyTester: suspend (Application, VpnProfile) -> Int = { app, profile ->
        com.vpnapp.vpn.singbox.SingBoxDelayTester.testProfileDelay(app, profile)
    }
) : AndroidViewModel(application) {
    
    val localAddresses = MutableStateFlow(com.vpnapp.util.LocalNetworkInfo.addresses(application).joinToString(", "))
    val alwaysOn = MutableStateFlow(false)
    val killSwitch = MutableStateFlow(false)
    fun refreshLocalAddresses() { localAddresses.value=com.vpnapp.util.LocalNetworkInfo.addresses(getApplication(), preferredNetwork=originalNetwork).joinToString(", ") }

    private val profileRepository = ProfileRepository.getInstance(application)
    
    private val _connectionInfo = MutableStateFlow(VpnConnectionInfo())
    val connectionInfo: StateFlow<VpnConnectionInfo> = _connectionInfo.asStateFlow()
    
    private val _profiles = MutableStateFlow<List<VpnProfile>>(emptyList())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()
    
    private val _subscriptions = MutableStateFlow<List<VpnSubscription>>(emptyList())
    val subscriptions: StateFlow<List<VpnSubscription>> = _subscriptions.asStateFlow()
    private val _selectedSubscriptionId = MutableStateFlow<String?>(null)
    val selectedSubscriptionId: StateFlow<String?> = _selectedSubscriptionId.asStateFlow()
    private val _refreshingSubscriptions = MutableStateFlow<Set<String>>(emptySet())
    val refreshingSubscriptions: StateFlow<Set<String>> = _refreshingSubscriptions.asStateFlow()
    private val subscriptionRefreshJobs = mutableMapOf<String, Job>()
    private val refreshSlots = Semaphore(3)
    private var appInForeground = false
    private val _subscriptionImport = MutableStateFlow(SubscriptionImportState())
    val subscriptionImport: StateFlow<SubscriptionImportState> = _subscriptionImport.asStateFlow()
    private var subscriptionImportJob: Job? = null
    private var subscriptionImportGeneration = 0L
    private val _profileSort = MutableStateFlow(ProfileSort.stored(application.getSharedPreferences("vpn_app_prefs", 0)
        .getString(ProfileSort.PREFERENCE_KEY, null)))
    val profileSort: StateFlow<ProfileSort> = _profileSort.asStateFlow()
    fun setProfileSort(sort: ProfileSort) {
        _profileSort.value = sort
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0).edit().putString(ProfileSort.PREFERENCE_KEY, sort.name).apply()
    }
    fun clearSubscriptionImportResult() { if (!_subscriptionImport.value.loading) _subscriptionImport.value = SubscriptionImportState() }
    fun cancelSubscriptionImport() { subscriptionImportGeneration++; subscriptionImportJob?.cancel(); _subscriptionImport.value = SubscriptionImportState() }

    
    private val _selectedProfile = MutableStateFlow<VpnProfile?>(null)
    val selectedProfile: StateFlow<VpnProfile?> = _selectedProfile.asStateFlow()
    
    private val _logs = MutableStateFlow<List<ConnectionLog>>(emptyList())
    val logs: StateFlow<List<ConnectionLog>> = _logs.asStateFlow()

    private val _bypassIran = MutableStateFlow(true)
    val bypassIran: StateFlow<Boolean> = _bypassIran.asStateFlow()

    private val _bypassPrivateIps = MutableStateFlow(true)
    val bypassPrivateIps: StateFlow<Boolean> = _bypassPrivateIps.asStateFlow()

    private val _forceXhttpStreamUp = MutableStateFlow(application.getSharedPreferences("vpn_app_prefs", 0)
        .getBoolean(XHttpModePolicy.PREFERENCE_KEY, true))
    val forceXhttpStreamUp: StateFlow<Boolean> = _forceXhttpStreamUp.asStateFlow()
    
    // Split Tunneling - excluded apps
    private val _excludedApps = MutableStateFlow<List<String>>(emptyList())
    val excludedApps: StateFlow<List<String>> = _excludedApps.asStateFlow()
    
    private val _latencyTestState = MutableStateFlow(LatencyTestState())
    val latencyTestState: StateFlow<LatencyTestState> = _latencyTestState.asStateFlow()
    private var latencyBatchJob: Job? = null
    @Volatile private var latencyTestGeneration = 0L

    private var statsJob: kotlinx.coroutines.Job? = null
    private var originalIpJob: Job? = null
    private var geoInfoJob: Job? = null
    private var pingJob: Job? = null
    private var routingSettingsJob: Job? = null
    private var disconnectConfirmationJob: Job? = null
    private var smartConnectionJob: Job? = null
    private var smartRequestGeneration = 0L
    private val _smartPreparing = MutableStateFlow(false)
    val smartPreparing: StateFlow<Boolean> = _smartPreparing.asStateFlow()
    @Volatile private var connectionGeneration = 0L
    private var originalNetwork: Network? = null
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshOrigin()
        override fun onLost(network: Network) = refreshOrigin()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshOrigin()
        private fun refreshOrigin() { viewModelScope.launch { fetchOriginalIp() } }
    }
    
    private var probeNetwork: Network? = null
    private val tunnelNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshProbes()
        override fun onLost(network: Network) = refreshProbes()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshProbes()
        private fun refreshProbes() {
            viewModelScope.launch {
                if (_connectionInfo.value.state != ConnectionState.CONNECTED) return@launch
                if (usesCoreProbe()) return@launch
                val current = tunnelNetwork()
                if (current == probeNetwork) return@launch
                probeNetwork = current
                connectionGeneration++
                _connectionInfo.update { it.copy(stats = it.stats.copy(publicIp = "Checking...", ping = -1)) }
                fetchGeoInfo()
                val profile = _connectionInfo.value.profile ?: _selectedProfile.value
                checkPing(profile?.id ?: "", profile?.name ?: "System")
            }
        }
    }

    private fun tunnelNetwork(): Network? {
        fun isTunnel(network: Network): Boolean = connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return connectivity.activeNetwork?.takeIf(::isTunnel)
    }

    private suspend fun awaitTunnelNetwork(): Network? {
        repeat(30) {
            coroutineContext.ensureActive()
            if (_connectionInfo.value.state != ConnectionState.CONNECTED) return null
            tunnelNetwork()?.let { return it }
            delay(200)
        }
        return null
    }

    private fun usesCoreProbe(): Boolean = _connectionInfo.value.profile?.protocol in listOf(
        VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2)

    private inner class ProbeTransport(val core: ProbeEndpoint?, val network: Network?) {
        fun open(endpoint: String, resources: com.vpnapp.util.ProbeResources): ProbeHttp {
            val register: (java.net.Socket) -> Unit = { socket -> resources.register { socket.close() } }
            return ProbeHttp.open(URL(endpoint), registerSocket = register) { host, port ->
                core?.connect(host, port, register) ?: connectThroughDefaultVpn(host, port, register)
            }
        }

        private fun connectThroughDefaultVpn(host: String, port: Int, register: (java.net.Socket) -> Unit): java.net.Socket {
            val vpn = checkNotNull(network)
            fun requireCurrent() = check(connectivity.activeNetwork == vpn && tunnelNetwork() == vpn) {
                "VPN network changed during probe"
            }
            requireCurrent()
            // Android may reject explicit binding to a retiring VPN netId with EPERM.
            // An ordinary socket follows this UID's default VPN. Verify its identity
            // before and after connecting, and retain generation guards for results.
            val addresses = vpn.getAllByName(host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            var failure: Exception? = null
            for (address in addresses.take(3)) {
                requireCurrent()
                val socket = java.net.Socket()
                try {
                    register(socket)
                    socket.soTimeout = 3000
                    socket.tcpNoDelay = true
                    socket.connect(java.net.InetSocketAddress(address, port), 3000)
                    requireCurrent()
                    return socket
                } catch (error: Exception) {
                    socket.close()
                    failure = error
                }
            }
            throw failure ?: java.io.IOException("VPN DNS returned no addresses")
        }
    }

    private suspend fun awaitProbeTransport(): ProbeTransport? {
        if (!usesCoreProbe()) return awaitTunnelNetwork()?.let { ProbeTransport(null, it) }
        repeat(30) {
            coroutineContext.ensureActive()
            if (_connectionInfo.value.state != ConnectionState.CONNECTED) return null
            ProbeEndpoint.active?.let { return ProbeTransport(it, null) }
            delay(200)
        }
        return null
    }

    private fun probeIsCurrent(transport: ProbeTransport?): Boolean = transport != null &&
        if (transport.core != null) ProbeEndpoint.active == transport.core else tunnelNetwork() == transport.network

    init {
        // Load saved profiles from SharedPreferences
        val loadedProfiles = profileRepository.loadProfiles()
        val savedProfiles = loadedProfiles.map { XHttpModePolicy.apply(it, _forceXhttpStreamUp.value) }
        if (savedProfiles != loadedProfiles) profileRepository.saveProfiles(savedProfiles)
        val savedSubscriptions = profileRepository.loadSubscriptions()
        _subscriptions.value = savedSubscriptions
        _selectedSubscriptionId.value = profileRepository.getSelectedSubscriptionId()
            ?.takeIf { id -> savedSubscriptions.any { it.id == id } }
        profileRepository.setSelectedSubscriptionId(_selectedSubscriptionId.value)

        // Load settings
        val prefs = getApplication<Application>().getSharedPreferences("vpn_app_prefs", android.content.Context.MODE_PRIVATE)
        _bypassIran.value = prefs.getBoolean("bypass_iran", true)
        _bypassPrivateIps.value = prefs.getBoolean("bypass_private_ips", true)
        val savedExcludedApps = prefs.getStringSet("excluded_apps", emptySet())?.toList() ?: emptyList()
        _excludedApps.value = savedExcludedApps
        Log.d("VpnViewModel", "Loaded ${savedExcludedApps.size} excluded apps from preferences")
        
        if (savedProfiles.isEmpty()) {
            // Clean install for production/publishing - no pre-seeded default profiles
            Log.d("VpnViewModel", "Clean start: 0 profiles")
            _profiles.value = emptyList()
            _selectedProfile.value = null
        } else {
            // Restore saved profiles
            Log.d("VpnViewModel", "Loaded ${savedProfiles.size} saved profiles")
            _profiles.value = savedProfiles
            
            // Restore selected profile
            val selectedId = profileRepository.getSelectedProfileId()
            _selectedProfile.value = savedProfiles.find { it.id == selectedId } ?: savedProfiles.firstOrNull()
        }
        
        // Load persistent logs from disk
        loadPersistentLogs()
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
            physicalNetworkCallback
        )
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), tunnelNetworkCallback
        )
        fetchOriginalIp()
    }

    private fun fetchOriginalIp(force: Boolean = false) {
        refreshLocalAddresses()
        val network = physicalNetwork()
        if (!force && network == originalNetwork && (originalIpJob?.isActive == true || _connectionInfo.value.stats.originalIp.isNotEmpty())) return
        originalIpJob?.cancel()
        originalNetwork = network
        _connectionInfo.update { it.copy(stats = it.stats.copy(originalIp = "")) }
        if (network == null) return
        originalIpJob = viewModelScope.launch(Dispatchers.IO) {
            val endpoints = listOf(
                "https://api.ipify.org",
                "https://ifconfig.me/ip",
                "https://icanhazip.com/"
            )
            for (ep in endpoints) {
                coroutineContext.ensureActive()
                var conn: HttpURLConnection? = null
                try {
                    // Bind both DNS and HTTP to a physical network, never the current VPN.
                    val resp = com.vpnapp.util.cancellableProbe { resources ->
                        val connection = network.openConnection(URL(ep)) as HttpURLConnection
                        conn = connection
                        resources.register { connection.disconnect() }
                        connection.connectTimeout = 3000
                        connection.readTimeout = 3000
                        connection.useCaches = false
                        connection.setRequestProperty("Connection", "close")
                        connection.inputStream.bufferedReader().use { it.readText() }
                    }
                    val ip = resp.trim()
                    coroutineContext.ensureActive()
                    if (originalNetwork != network || physicalNetwork() != network) return@launch
                    if (com.vpnapp.util.IpAddressValidator.isValid(ip)) {
                        _connectionInfo.update { it.copy(stats = it.stats.copy(originalIp = ip)) }
                        break
                    }
                } catch (e: Exception) {
                    // ignore and try next
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }

    private fun physicalNetwork(): Network? {
        val networks = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return networks.firstOrNull { it == connectivity.activeNetwork }
            ?: networks.sortedByDescending { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 2 else 0) +
                    (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0)
            }.firstOrNull()
    }

    // Settings Management
    fun setForceXhttpStreamUp(enabled: Boolean) {
        _forceXhttpStreamUp.value = enabled
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0).edit()
            .putBoolean(XHttpModePolicy.PREFERENCE_KEY, enabled).apply()
        _profiles.update { profiles -> profiles.map { XHttpModePolicy.apply(it, enabled) } }
        profileRepository.saveProfiles(_profiles.value)
        _selectedProfile.value?.id?.let { id ->
            _profiles.value.find { it.id == id }?.let(::selectProfile)
        }
        // The active service keeps its current configuration until the next connection.
    }

    fun setBypassIran(enabled: Boolean) {
        _bypassIran.value = enabled
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("bypass_iran", enabled)
                .apply()
        applyRoutingSettings()
    }

    fun setBypassPrivateIps(enabled: Boolean) {
        _bypassPrivateIps.value = enabled
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("bypass_private_ips", enabled)
                .apply()
        applyRoutingSettings()
    }

    private fun applyRoutingSettings() {
        routingSettingsJob?.cancel()
        routingSettingsJob = viewModelScope.launch {
            delay(350) // Coalesce rapid switch changes; send the latest pair together.
            if (_connectionInfo.value.state in listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING)) {
                val context = getApplication<Application>()
                context.startService(android.content.Intent(context, VpnConnectionService::class.java).apply {
                    action = VpnConnectionService.ACTION_UPDATE_ROUTING
                    com.vpnapp.vpn.singbox.TunnelDnsSettings(_tunnelDnsUrl.value,_useFakeIp.value).writeTo(this)
                    putExtra("bypass_iran", _bypassIran.value)
                    putExtra("bypass_private_ips", _bypassPrivateIps.value)
                })
            }
        }
    }
    
    private fun clearLatencies(ids: Set<String>) {
        _profiles.update { list -> list.map { if (it.id in ids) it.copy(latency = null) else it } }
        _selectedProfile.update { it?.let { p -> if (p.id in ids) p.copy(latency = null) else p } }
    }

    private fun cancelLatencyTests() {
        latencyTestGeneration++
        latencyBatchJob?.cancel()
        latencyBatchJob = null
        pingJob?.cancel()
        _latencyTestState.value = LatencyTestState()
    }

    /** Keep completed measurements; discard queued work and ignore late results from this generation. */
    fun stopLatencyTests() {
        cancelLatencyTests()
        _connectionInfo.update { info ->
            if (info.stats.ping == -1L) info.copy(stats = info.stats.copy(ping = 0)) else info
        }
    }

    private fun setProfileLatency(id: String, latency: Int) {
        _profiles.update { list -> list.map { if (it.id == id) it.copy(latency = latency) else it } }
        _selectedProfile.update { it?.let { p -> if (p.id == id) p.copy(latency = latency) else p } }
    }

    fun measureLatency(profile: VpnProfile) {
        if (_connectionInfo.value.state == ConnectionState.CONNECTED) {
            if (_connectionInfo.value.profile?.id == profile.id) testCurrentLatency()
            return
        }
        if (_connectionInfo.value.state in listOf(ConnectionState.CONNECTING, ConnectionState.DISCONNECTING)) return
        startLatencyBatch(listOf(profile))
    }

    fun measureAllLatencies() {
        val visible = _profiles.value.filter { _selectedSubscriptionId.value == null || it.subscriptionId == _selectedSubscriptionId.value }
        if (_connectionInfo.value.state == ConnectionState.CONNECTED) {
            if (visible.any { it.id == _connectionInfo.value.profile?.id }) {
                clearLatencies(setOfNotNull(_connectionInfo.value.profile?.id))
                testCurrentLatency()
            }
            return
        }
        if (_connectionInfo.value.state in listOf(ConnectionState.CONNECTING, ConnectionState.DISCONNECTING)) return
        startLatencyBatch(visible)
    }

    private fun startLatencyBatch(profiles: List<VpnProfile>) {
        cancelLatencyTests()
        val generation = latencyTestGeneration
        val snapshot = profiles.map { it.copy(latency = null) }
        val ids = snapshot.map { it.id }.toSet()
        clearLatencies(ids)
        _latencyTestState.value = LatencyTestState(pendingIds = ids, total = ids.size)
        latencyBatchJob = viewModelScope.launch {
            try {
                val slots = Semaphore(LatencyTestState.MAX_CONCURRENT_PROBES)
                coroutineScope {
                    snapshot.map { profile -> launch {
                        slots.withPermit {
                            coroutineContext.ensureActive()
                            if (generation != latencyTestGeneration || _connectionInfo.value.state !in listOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return@withPermit
                            if (_profiles.value.none { it.id == profile.id }) {
                                _latencyTestState.update { it.copy(pendingIds = it.pendingIds - profile.id) }
                                return@withPermit
                            }
                            _latencyTestState.update { it.copy(activeIds = it.activeIds + profile.id) }
                            try {
                                val result = try { withContext(Dispatchers.IO) { latencyTester(getApplication(), profile) } }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { -1 }
                                coroutineContext.ensureActive()
                                if (generation != latencyTestGeneration) return@withPermit
                                if (_profiles.value.any { it.id == profile.id && it.copy(latency = null) == profile }) {
                                    setProfileLatency(profile.id, result)
                                }
                            } finally {
                                if (generation == latencyTestGeneration) _latencyTestState.update {
                                    it.copy(pendingIds = it.pendingIds - profile.id, activeIds = it.activeIds - profile.id)
                                }
                            }
                        }
                    } }.joinAll()
                }
            } finally {
                if (generation == latencyTestGeneration) _latencyTestState.value = LatencyTestState()
            }
        }
    }

    // Profile Management
    fun addProfile(profile: VpnProfile) {
        _profiles.update { it + XHttpModePolicy.apply(profile, _forceXhttpStreamUp.value) }
        profileRepository.saveProfiles(_profiles.value)
        addLog(profile.id, profile.name, LogLevel.INFO, "Profile '${profile.name}' created")
        if (_selectedProfile.value == null) {
            selectProfile(_profiles.value.first { it.id == profile.id })
        }
    }
    
    fun updateProfile(profile: VpnProfile) {
        if (_smartPreparing.value && _selectedProfile.value?.id == profile.id) cancelSmartConnection()
        _profiles.update { profiles ->
            profiles.map { if (it.id == profile.id) {
                val edited = if (XHttpSmartPolicy.sameConnection(it, profile)) profile else profile.copy(v2raySmartXhttpMode = null)
                XHttpModePolicy.apply(edited, _forceXhttpStreamUp.value)
            } else it }
        }
        profileRepository.saveProfiles(_profiles.value)
        if (_selectedProfile.value?.id == profile.id) {
            selectProfile(_profiles.value.first { it.id == profile.id })
        }
        addLog(profile.id, profile.name, LogLevel.INFO, "Profile '${profile.name}' updated")
    }
    
    fun deleteProfile(profileId: String) {
        if (_smartPreparing.value && _selectedProfile.value?.id == profileId) cancelSmartConnection()
        val profile = _profiles.value.find { it.id == profileId }
        _profiles.update { it.filter { p -> p.id != profileId } }
        profileRepository.saveProfiles(_profiles.value)
        if (_selectedProfile.value?.id == profileId) {
            _selectedProfile.value = _profiles.value.firstOrNull()
            profileRepository.setSelectedProfileId(_selectedProfile.value?.id)
        }
        profile?.let {
            addLog(it.id, it.name, LogLevel.INFO, "Profile '${it.name}' deleted")
        }
    }
    
    fun selectProfile(profile: VpnProfile) {
        if (_smartPreparing.value && _selectedProfile.value?.id != profile.id) cancelSmartConnection()
        val selected = _profiles.value.find { it.id == profile.id } ?: XHttpModePolicy.apply(profile, _forceXhttpStreamUp.value)
        _selectedProfile.value = selected
        _connectionInfo.update { if (it.state in listOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) it.copy(profile = selected) else it }
        profileRepository.setSelectedProfileId(selected.id)
    }
    
    // Restore profile selection by name (for when app resumes and VPN is connected)
    fun restoreActiveProfile(profileName: String, profileId: String?, protocolName: String?) {
        val profile = if (profileId != null) _profiles.value.find { it.id == profileId }
            else _selectedProfile.value?.takeIf { it.name == profileName }
                ?: _profiles.value.find { it.name == profileName }
        val active = profile?.copy(name = profileName) ?: VpnProfile(
            id = profileId ?: "active-session", name = profileName, serverAddress = "",
            protocol = VpnProtocol.values().find { it.name == protocolName } ?: VpnProtocol.OPENCONNECT
        )
        // A removed/renamed subscription entry must not hide the actual running session.
        _connectionInfo.update { it.copy(profile = active) }
    }
    
    // Connection Management
    fun prepareConnection(onReady: (VpnProfile) -> Unit) {
        if (_connectionInfo.value.state !in listOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return
        val profile = _selectedProfile.value ?: return
        TunnelEncryptionPolicy.blockingReason(profile)?.let { reason ->
            rejectInsecureProfile(profile, reason)
            return
        }
        connect()
        if (!XHttpModePolicy.isXHttp(profile) || profile.v2rayXhttpModeOverride != "smart") {
            onReady(profile)
            return
        }
        val request = ++smartRequestGeneration
        _smartPreparing.value = true
        smartConnectionJob = viewModelScope.launch {
            try {
                check(connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
                    "Disconnect the other VPN before running a Smart check."
                }
                val working = kotlinx.coroutines.withTimeout(45_000) {
                    var result: VpnProfile? = null
                    for (mode in XHttpSmartPolicy.candidates(profile)) {
                        coroutineContext.ensureActive()
                        if (request != smartRequestGeneration) break
                        val candidate = XHttpSmartPolicy.candidate(profile, mode)
                        addLog(profile.id, profile.name, LogLevel.INFO, "Smart XHTTP: checking $mode traffic...")
                        for (endpoint in listOf("https://www.google.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
                            val latency = com.vpnapp.vpn.singbox.SingBoxDelayTester.testProfileDelay(
                                getApplication(), candidate, targetUrl = endpoint, quickCheck = true)
                            coroutineContext.ensureActive()
                            if (latency > 0) { result = candidate.copy(v2raySmartXhttpMode = mode); break }
                        }
                        if (result != null) break
                    }
                    result
                } ?: error("No XHTTP mode passed the traffic check. Check the server or network, or choose a manual mode.")
                if (request != smartRequestGeneration || _selectedProfile.value?.id != profile.id ||
                    _connectionInfo.value.state != ConnectionState.CONNECTING) return@launch
                _profiles.update { list -> list.map { if (it.id == working.id) working else it } }
                _selectedProfile.value = working
                _connectionInfo.update { it.copy(profile = working) }
                profileRepository.saveProfiles(_profiles.value)
                addLog(profile.id, profile.name, LogLevel.SUCCESS, "Smart XHTTP: ${working.v2rayType} passed; starting VPN.")
                onReady(working)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (request == smartRequestGeneration) _connectionInfo.update {
                    it.copy(state = ConnectionState.ERROR, errorMessage = "Smart check timed out. Try a manual XHTTP mode.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == smartRequestGeneration) {
                    _connectionInfo.update { it.copy(state = ConnectionState.ERROR, errorMessage = e.message) }
                    addLog(profile.id, profile.name, LogLevel.WARNING, "Smart XHTTP: ${e.message}")
                }
            } finally {
                if (request == smartRequestGeneration) _smartPreparing.value = false
            }
        }
    }

    fun rejectInsecureProfile(profile: VpnProfile, reason: String) {
        if (_connectionInfo.value.state !in listOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return
        _connectionInfo.update { it.copy(state = ConnectionState.ERROR, profile = profile, errorMessage = reason) }
        addLog(profile.id, profile.name, LogLevel.ERROR, reason)
    }

    fun cancelSmartConnection() {
        if (!_smartPreparing.value) return
        smartRequestGeneration++
        smartConnectionJob?.cancel()
        smartConnectionJob = null
        _smartPreparing.value = false
        _connectionInfo.update { it.copy(state = ConnectionState.DISCONNECTED, errorMessage = null) }
    }

    fun toggleConnection() {
        when (_connectionInfo.value.state) {
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> connect()
            ConnectionState.CONNECTED -> disconnect()
            else -> {} // Ignore if connecting/disconnecting
        }
    }
    
    private fun connect() {
        refreshLocalAddresses()
        cancelLatencyTests()
        val profile = _selectedProfile.value ?: return
        
        // Set state to connecting - Service will handle the rest and broadcast updates
        // IMPORTANT: Clear previous GeoInfo to prevent showing stale data
        _connectionInfo.update { 
            it.copy(
                state = ConnectionState.CONNECTING,
                profile = profile,
                errorMessage = null,
                stats = ConnectionStats(originalIp = it.stats.originalIp) // Preserve original IP!
            )
        }
        
        addLog(profile.id, profile.name, LogLevel.INFO, "Initiating connection to ${profile.serverAddress}:${profile.port}")
    }
    
    private fun workerAlive(pid: Int): Boolean = try { android.system.Os.kill(pid, 0); true }
        catch (_: android.system.ErrnoException) { false }

    private fun confirmWorkerExit(pid: Int) {
        if (pid <= 0 || disconnectConfirmationJob?.isActive == true) return
        disconnectConfirmationJob = viewModelScope.launch {
            if (com.vpnapp.vpn.VpnDisconnectConfirmation.awaitExit(pid, ::workerAlive))
                updateConnectionStatus("DISCONNECTED", null, null)
            else updateConnectionStatus("ERROR", null, null,
                message = "VPN service shutdown could not be confirmed. Please retry.")
        }
    }

    // Called by MainActivity when Broadcast received
    fun updateConnectionStatus(status: String?, privateIp: String?, serverIp: String?, mtu: Int = 1400, message: String? = null, closedWorkerPid: Int = 0) {
        val profile = _connectionInfo.value.profile ?: _selectedProfile.value
            ?: VpnProfile(id = "system", name = "System", serverAddress = "")
        
        when (status) {
            "CONNECTED" -> {
                if (_connectionInfo.value.state == ConnectionState.DISCONNECTING) return
                disconnectConfirmationJob?.cancel()
                if (_connectionInfo.value.state == ConnectionState.CONNECTED) return
                probeNetwork = tunnelNetwork()
                trafficRates.reset()
                connectionGeneration++
                geoInfoJob?.cancel()
                pingJob?.cancel()
                val pIp = privateIp ?: "Unknown"
                val sIp = serverIp ?: profile.serverAddress
                
                _connectionInfo.update { 
                    it.copy(
                        state = ConnectionState.CONNECTED,
                        stats = ConnectionStats(privateIp = pIp, publicIp = "Checking...", mtu = mtu, originalIp = it.stats.originalIp)
                    )
                }
                
                addLog(profile.id, profile.name, LogLevel.SUCCESS, "Connected! IP: $pIp, MTU: $mtu")
                
                // Update last connected time
                _profiles.update { profiles ->
                    profiles.map { 
                        if (it.id == profile.id) it.copy(lastConnected = System.currentTimeMillis())
                        else it
                    }
                }
                
                // Fetch geo info through VPN tunnel
                fetchGeoInfo()
                checkPing(profile.id, profile.name)
            }
            "DISCONNECTED" -> {
                // Never use the device-wide VPN network here: it may belong to another app.
                if (com.vpnapp.vpn.VpnDisconnectConfirmation.requiresWait(closedWorkerPid, ::workerAlive)) {
                    _connectionInfo.update { it.copy(state = ConnectionState.DISCONNECTING) }
                    confirmWorkerExit(closedWorkerPid)
                    return
                }
                disconnectConfirmationJob?.cancel()
                disconnectConfirmationJob = null
                probeNetwork = null
                connectionGeneration++
                geoInfoJob?.cancel()
                pingJob?.cancel()
                statsJob?.cancel()
                _connectionInfo.update { 
                    it.copy(
                        state = ConnectionState.DISCONNECTED,
                        stats = ConnectionStats(originalIp = it.stats.originalIp)
                    )
                }
                fetchOriginalIp(force = true)
                addLog(profile.id, profile.name, LogLevel.INFO, "Disconnected")
            }
            "ERROR" -> {
                connectionGeneration++
                geoInfoJob?.cancel()
                pingJob?.cancel()
                statsJob?.cancel()
                _connectionInfo.update { 
                    it.copy(
                        state = ConnectionState.ERROR,
                        errorMessage = message
                    )
                }
                addLog(profile.id, profile.name, LogLevel.ERROR, "Error: $message")
            }
            "CONNECTING" -> {
                 disconnectConfirmationJob?.cancel()
                 _connectionInfo.update { it.copy(state = ConnectionState.CONNECTING) }
            }
            "DISCONNECTING" -> {
                connectionGeneration++
                geoInfoJob?.cancel()
                pingJob?.cancel()
                _connectionInfo.update { it.copy(state = ConnectionState.DISCONNECTING) }
                // A forced worker exit may not get a final broadcast; observe its PID from the start.
                confirmWorkerExit(closedWorkerPid)
            }
            "LOG" -> {
                 val msg = message ?: return
                 addLog(profile.id, profile.name, LogLevel.DEBUG, msg)
            }
        }
    }
    
    private fun disconnect() {
        _connectionInfo.update { it.copy(state = ConnectionState.DISCONNECTING) }
        val profile = _selectedProfile.value
        profile?.let {
            addLog(it.id, it.name, LogLevel.INFO, "Disconnecting...")
        }
        // Service will handle actual disconnect and broadcast
    }
    
    private val trafficRates = com.vpnapp.data.TrafficRateTracker(android.os.SystemClock::elapsedRealtime)

    fun updateTrafficStats(totalRx: Long, totalTx: Long, transport: String = "TCP", connectionTimeSeconds: Long = 0L) {
        if (_connectionInfo.value.state != ConnectionState.CONNECTED) trafficRates.reset()
        val (downSpeed, upSpeed) = trafficRates.sample(totalRx, totalTx)

        // Use connection time from service (in milliseconds format for consistency)
        val connectedTimeMs = connectionTimeSeconds * 1000
        
        _connectionInfo.update { info ->
            info.copy(
                stats = info.stats.copy(
                    uploadSpeed = upSpeed,
                    downloadSpeed = downSpeed,
                    totalUploaded = totalTx,
                    totalDownloaded = totalRx,
                    connectedTime = connectedTimeMs,
                    transportProtocol = transport
                )
            )
        }
        
        // Log update once every 10 seconds to confirm stats are flowing
        if (connectionTimeSeconds % 10 == 0L) {
             Log.d("VpnViewModel", "Stats Flowing: RX=$totalRx, TX=$totalTx, Time=${connectionTimeSeconds}s")
        }
    }
    
    // Removed startStatsUpdate (Simulation)
    
    // Global Performance Settings
    private val _bufferSize = MutableStateFlow(application.getSharedPreferences("vpn_app_prefs", 0)
        .getInt("buffer_size", 2 * 1024 * 1024).takeIf { it in 65536..8388608 } ?: (2 * 1024 * 1024))
    val bufferSize: StateFlow<Int> = _bufferSize
    
    private val _tcpNoDelay = MutableStateFlow(application.getSharedPreferences("vpn_app_prefs", 0)
        .getBoolean("tcp_no_delay", true))
    val tcpNoDelay: StateFlow<Boolean> = _tcpNoDelay
    
    private val _mtuSize = MutableStateFlow(application.getSharedPreferences("vpn_app_prefs", 0)
        .getInt("mtu_size", 1400).takeIf { it in 1200..1500 } ?: 1400)
    val mtuSize: StateFlow<Int> = _mtuSize
    
    private val _tunnelDnsUrl = MutableStateFlow(application.getSharedPreferences("vpn_app_prefs", 0)
        .getString(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY, com.vpnapp.vpn.singbox.TunnelDns.DEFAULT_URL)
        ?: com.vpnapp.vpn.singbox.TunnelDns.DEFAULT_URL)
    val tunnelDnsUrl: StateFlow<String> = _tunnelDnsUrl
    private val _useFakeIp = MutableStateFlow(application.getSharedPreferences("vpn_app_prefs", 0)
        .getBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY, true))
    val useFakeIp: StateFlow<Boolean> = _useFakeIp

    fun setTunnelDnsUrl(url: String) {
        val validated = com.vpnapp.vpn.singbox.TunnelDns.validateUrl(url)
        _tunnelDnsUrl.value = validated
        // Saving a resolver means using its answers, not leaving A/AAAA queries on FakeIP.
        _useFakeIp.value = false
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0).edit()
            .putString(com.vpnapp.vpn.singbox.TunnelDns.URL_KEY, validated)
            .putBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY, false).apply()
        applyRoutingSettings()
    }

    fun setUseFakeIp(enabled: Boolean) {
        _useFakeIp.value = enabled
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0).edit()
            .putBoolean(com.vpnapp.vpn.singbox.TunnelDns.FAKE_IP_KEY, enabled).apply()
        applyRoutingSettings()
    }

    fun setBufferSize(size: Int) {
        require(size in 65536..8388608)
        _bufferSize.value = size
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0).edit()
            .putInt("buffer_size", size).apply()
        android.util.Log.d("VpnViewModel", "Buffer size set to: $size")
    }
    
    fun setTcpNoDelay(enabled: Boolean) {
        _tcpNoDelay.value = enabled
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0).edit()
            .putBoolean("tcp_no_delay", enabled).apply()
        android.util.Log.d("VpnViewModel", "TCP_NODELAY set to: $enabled")
    }
    
    fun setMtuSize(mtu: Int) {
        require(mtu in 1200..1500)
        _mtuSize.value = mtu
        getApplication<Application>().getSharedPreferences("vpn_app_prefs", 0)
            .edit().putInt("mtu_size", mtu).apply()
        android.util.Log.d("VpnViewModel", "MTU set to: $mtu")
    }
    
    // Split Tunneling - update excluded apps
    
    fun setExcludedApps(apps: List<String>) {
        _excludedApps.value = apps
        android.util.Log.d("VpnViewModel", "Excluded apps updated: ${apps.size} apps")
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().getSharedPreferences("vpn_app_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putStringSet("excluded_apps", apps.toSet())
                .apply()
        }
    }
    
    // Fetch Country Info from VPN exit point
    private fun fetchGeoInfo() {
        val currentProfile = _connectionInfo.value.profile
        val profileId = currentProfile?.id ?: ""
        val profileName = currentProfile?.name ?: "System"
        
        Log.d("VpnViewModel", "Fetching Geolocation info...")
        
        val generation = connectionGeneration
        geoInfoJob?.cancel()
        geoInfoJob = viewModelScope.launch(Dispatchers.IO) {
            // Wait for tunnel and routes to stabilize
            // Reduced to 2s so the UI updates faster for the user
            kotlinx.coroutines.delay(300L)
            val transport = awaitProbeTransport()
            
            addLog(profileId, profileName, LogLevel.INFO, "GeoInfo: Identifying tunnel exit...")
            
            // Providers to try (ordered by reliability/accuracy)
            // Using HTTPS-only providers to avoid blocking issues
            val providers = listOf(
                "https://api.ip.sb/geoip",     // Best for v2rayNG compatibility
                "https://ipinfo.io/json",      // Very reliable, returns countryCode as "country"
                "https://ipwho.is/",           // Good backup
                "https://ipapi.co/json/",      // Alternative
                "https://freeipapi.com/api/json"
            )

            for (providerUrl in if (transport != null) providers + providers else emptyList()) {
                coroutineContext.ensureActive()
                try {
                    val result = com.vpnapp.util.cancellableProbe { resources ->
                        transport!!.open(providerUrl, resources).use { it.get() }
                    }
                    if (result.status == 200) {
                        val response = result.body
                        
                        // Parse JSON (simple parsing - supports multiple formats)
                        // Different services use different field names:
                        // - ipinfo.io: "country" (2-letter code), "ip"
                        // - ipwho.is: "country_code", "ip"
                        // - ip-api.com: "countryCode", "query"
                        val countryCodeRegex = "\"country_code\"\\s*:\\s*\"([A-Z]{2})\"".toRegex()
                        val countryCodeAltRegex = "\"countryCode\"\\s*:\\s*\"([A-Z]{2})\"".toRegex()
                        // ipinfo.io returns "country": "US" (2-letter code directly)
                        val countryShortRegex = "\"country\"\\s*:\\s*\"([A-Z]{2})\"".toRegex()
                        val countryNameRegex = "\"country\"\\s*:\\s*\"([A-Za-z ]{3,})\"".toRegex()
                        val ipRegex = "\"ip\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                        val queryRegex = "\"query\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                        
                        // Try to get country code (prioritize 2-letter codes)
                        val countryCode = countryCodeRegex.find(response)?.groupValues?.get(1) 
                                         ?: countryCodeAltRegex.find(response)?.groupValues?.get(1)
                                         ?: countryShortRegex.find(response)?.groupValues?.get(1)
                        val countryName = countryNameRegex.find(response)?.groupValues?.get(1) ?: ""
                        val publicIp = ipRegex.find(response)?.groupValues?.get(1)
                                      ?: queryRegex.find(response)?.groupValues?.get(1)
                        
                        coroutineContext.ensureActive()
                        if (generation != connectionGeneration || _connectionInfo.value.state != ConnectionState.CONNECTED || !probeIsCurrent(transport)) return@launch
                        if (publicIp != null && com.vpnapp.util.IpAddressValidator.isValid(publicIp)) {
                            _connectionInfo.update { info ->
                                info.copy(
                                    stats = info.stats.copy(
                                        countryCode = countryCode ?: "",
                                        countryName = countryName ?: "",
                                        publicIp = publicIp ?: info.stats.publicIp
                                    )
                                )
                            }
                            addLog(profileId, profileName, LogLevel.SUCCESS, "GeoInfo: Tunnel exit in $countryCode ($publicIp)")
                            
                            // SYNC SERVICE: Send detected info to VpnConnectionService for Notification & Persistence
                            try {
                                val context = getApplication<android.app.Application>()
                                val updateIntent = android.content.Intent(context, com.vpnapp.vpn.VpnConnectionService::class.java).apply {
                                    action = "com.vpnapp.UPDATE_GEO_INFO"
                                    putExtra("public_ip", publicIp)
                                    putExtra("country_code", countryCode)
                                }
                                context.startService(updateIntent)
                            } catch (e: Exception) {
                                Log.w("VpnViewModel", "Could not update service with GeoInfo: ${e.message}")
                            }
                            
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    Log.e("VpnViewModel", "GeoInfo failed for $providerUrl: ${e.message}")
                    delay(300)
                }
            }
            
            coroutineContext.ensureActive()
            if (generation == connectionGeneration && _connectionInfo.value.state == ConnectionState.CONNECTED) {
                _connectionInfo.update { it.copy(stats = it.stats.copy(publicIp = "Unavailable")) }
                addLog(profileId, profileName, LogLevel.WARNING, "GeoInfo: Exit IP unavailable.")
            }
        }
    }
    private fun checkPing(profileId: String, profileName: String) {
        val generation = connectionGeneration
        pingJob?.cancel()
        val testGeneration = ++latencyTestGeneration
        latencyBatchJob?.cancel()
        clearLatencies(setOf(profileId))
        _connectionInfo.update { it.copy(stats = it.stats.copy(ping = -1)) }
        _latencyTestState.value = LatencyTestState(setOf(profileId), setOf(profileId), 1)
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
            val transport = awaitProbeTransport()
            var bestPing = Long.MAX_VALUE
            val endpoints = listOf("https://www.google.com/generate_204",
                "https://cp.cloudflare.com/generate_204")
            for (endpoint in if (transport != null) endpoints else emptyList()) {
                coroutineContext.ensureActive()
                try {
                    com.vpnapp.util.cancellableProbe { resources ->
                    transport!!.open(endpoint, resources).use { session ->
                        // Establish SOCKS/TCP/TLS and warm up the remote path before timing.
                        check(session.get(keepAlive = true).status in listOf(200, 204))
                        repeat(2) {
                            coroutineContext.ensureActive()
                            val start = android.os.SystemClock.elapsedRealtime()
                            val response = session.get(keepAlive = true)
                            check(response.status in listOf(200, 204))
                            bestPing = minOf(bestPing, android.os.SystemClock.elapsedRealtime() - start)
                        }
                    }
                    }
                    if (bestPing != Long.MAX_VALUE) break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d("VpnViewModel", "Latency endpoint failed: ${e.message}")
                }
            }
            coroutineContext.ensureActive()
            if (generation != connectionGeneration || _connectionInfo.value.state != ConnectionState.CONNECTED || (transport != null && !probeIsCurrent(transport))) return@launch
            val ping = if (bestPing == Long.MAX_VALUE) 0L else bestPing.coerceAtLeast(1L)
            if (testGeneration != latencyTestGeneration) return@launch
            setProfileLatency(profileId, if (ping > 0) ping.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else -1)
            _connectionInfo.update { it.copy(stats = it.stats.copy(ping = ping)) }
            addLog(profileId, profileName, if (ping > 0) LogLevel.SUCCESS else LogLevel.WARNING,
                if (ping > 0) "Ping: ${ping}ms (warm HTTPS response)" else "Ping failed")
            } finally {
                if (testGeneration == latencyTestGeneration) _latencyTestState.value = LatencyTestState()
            }
        }
    }

    fun testCurrentLatency() {
        val selected = _connectionInfo.value.profile ?: return
        if (_connectionInfo.value.state == ConnectionState.CONNECTED) {
            // Indicate loading in UI by setting ping to -1 or similar temporarily
            _connectionInfo.update { it.copy(stats = it.stats.copy(ping = -1)) }
            checkPing(selected.id, selected.name)
            if (!com.vpnapp.util.IpAddressValidator.isValid(_connectionInfo.value.stats.publicIp)) fetchGeoInfo()
        }
    }

    
    private fun loadPersistentLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logFile = java.io.File("/data/data/com.vpnapp/files/logs/vpn_crash_log.txt")
                if (logFile.exists()) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    val lines = logFile.readLines().takeLast(100)
                    val loadedLogs = lines.mapNotNull { line ->
                        try {
                            // Extract [2025-12-18 23:15:15.218] message
                            val bracketStart = line.indexOf('[')
                            val bracketEnd = line.indexOf(']')
                            if (bracketStart == -1 || bracketEnd == -1 || bracketEnd <= bracketStart) return@mapNotNull null
                            
                            val timeStr = line.substring(bracketStart + 1, bracketEnd)
                            
                            // Check if there is content after the bracket
                            val message = if (line.length > bracketEnd + 2) {
                                line.substring(bracketEnd + 2).trim()
                            } else if (line.length > bracketEnd + 1) {
                                line.substring(bracketEnd + 1).trim()
                            } else {
                                ""
                            }
                            
                            val ts = try { sdf.parse(timeStr)?.time } catch (e: Exception) { null } ?: System.currentTimeMillis()
                            
                            ConnectionLog(
                                profileId = "history",
                                profileName = "System",
                                timestamp = ts,
                                level = if (line.contains("ERROR", true) || line.contains("CRASH", true)) LogLevel.ERROR else LogLevel.INFO,
                                message = message
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (loadedLogs.isNotEmpty()) {
                        _logs.update { (loadedLogs + it).distinctBy { "${it.timestamp}:${it.message}" }.takeLast(500) }
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnViewModel", "Failed to load persistent logs", e)
            }
        }
    }
    
    // Logging logic remains same...
    private fun addLog(profileId: String, profileName: String, level: LogLevel, message: String) {
        val log = ConnectionLog(
            profileId = profileId,
            profileName = profileName,
            level = level,
            message = message
        )
        _logs.update { listOf(log) + it }
        if (_logs.value.size > 500) {
            _logs.update { it.take(500) }
        }
    }
    
    
    fun clearLogs() { _logs.value = emptyList() }
    
    fun getLogsForProfile(profileId: String): List<ConnectionLog> {
        return _logs.value.filter { it.profileId == profileId }
    }

    // Subscription Management
    fun addSubscription(url: String, name: String? = null) {
        if (_subscriptionImport.value.loading) return
        val generation = ++subscriptionImportGeneration
        _subscriptionImport.value = SubscriptionImportState(loading = true)
        subscriptionImportJob = viewModelScope.launch {
            try {
                val subscriptionId = java.util.UUID.randomUUID().toString()
                val result = withContext(Dispatchers.IO) {
                    subscriptionFetcher(LinkImportInput.normalize(url), subscriptionId, name)
                } ?: throw SubscriptionImportException("Could not download the subscription. Check your connection and try again")
                coroutineContext.ensureActive()
                val (subscription, incoming) = result
                if (incoming.isEmpty()) throw SubscriptionImportException("No supported VPN configurations were found in this subscription")
                val profiles = incoming.map { XHttpModePolicy.apply(it.copy(subscriptionId = subscription.id), _forceXhttpStreamUp.value) }
                _subscriptions.update { it + subscription }
                profileRepository.saveSubscriptions(_subscriptions.value)
                _profiles.update { it + profiles }
                profileRepository.saveProfiles(_profiles.value)
                if (_selectedProfile.value == null) selectProfile(profiles.first())
                selectSubscription(subscription.id)
                _subscriptionImport.value = SubscriptionImportState(imported = profiles.size)
                addLog(subscription.id, subscription.name, LogLevel.SUCCESS, "Subscription added with ${profiles.size} profiles")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != subscriptionImportGeneration) return@launch
                val message = (e as? SubscriptionImportException)?.message ?: "Could not import subscription. Check your connection and try again"
                _subscriptionImport.value = SubscriptionImportState(error = message)
                addLog("subscription_error", "System", LogLevel.ERROR, message)
            } finally {
                if (generation == subscriptionImportGeneration && _subscriptionImport.value.loading) _subscriptionImport.value = SubscriptionImportState()
            }
        }
    }

    fun updateSubscription(sub: VpnSubscription) {
        _subscriptions.update { current ->
            current.map { if (it.id == sub.id) sub else it }
        }
        profileRepository.saveSubscriptions(_subscriptions.value)
    }

    fun refreshAllSubscriptions() {
        val subs = _subscriptions.value
        if (subs.isEmpty()) return
        subs.forEach { refreshSubscription(it.id) }
    }

    fun onAppForeground() {
        if (appInForeground) return
        appInForeground = true
        refreshAllSubscriptions()
    }

    fun onAppBackground() { appInForeground = false; trafficRates.reset() }

    fun selectSubscription(id: String?) {
        val valid = id?.takeIf { selected -> _subscriptions.value.any { it.id == selected } }
        if (_selectedSubscriptionId.value != valid) cancelLatencyTests()
        _selectedSubscriptionId.value = valid
        profileRepository.setSelectedSubscriptionId(valid)
    }

    fun refreshSubscription(subscriptionId: String) {
        if (subscriptionRefreshJobs[subscriptionId]?.isActive == true) return
        _refreshingSubscriptions.update { it + subscriptionId }
        subscriptionRefreshJobs[subscriptionId] = viewModelScope.launch {
            try {
            val sub = _subscriptions.value.find { it.id == subscriptionId } ?: return@launch
            addLog(sub.id, sub.name, LogLevel.INFO, "Refreshing subscription '${sub.name}'...")

            val result = refreshSlots.withPermit {
                withContext(Dispatchers.IO) { subscriptionFetcher(sub.url, subscriptionId, sub.name) }
            }
            val latest = _subscriptions.value.find { it.id == subscriptionId } ?: return@launch
            // Ignore results fetched for a URL that was edited or deleted while downloading.
            if (latest.url != sub.url) return@launch
            if (result != null) {
                val (subscription, profiles) = result
                
                // Update subscription list
                _subscriptions.update { current ->
                    current.map { if (it.id == subscriptionId) subscription.copy(name = latest.name) else it }
                }
                profileRepository.saveSubscriptions(_subscriptions.value)
                
                // Replace profiles
                _profiles.update { current ->
                    SubscriptionProfiles.merge(current, profiles, subscriptionId, _forceXhttpStreamUp.value)
                }
                profileRepository.saveProfiles(_profiles.value)
                
                // If selected profile was deleted, select first available
                val selectedId = _selectedProfile.value?.id
                val refreshedSelection = _profiles.value.find { it.id == selectedId }
                if (refreshedSelection != null) {
                    selectProfile(refreshedSelection)
                } else {
                    val fallback = _profiles.value.firstOrNull { it.subscriptionId == _selectedSubscriptionId.value }
                        ?: _profiles.value.firstOrNull()
                    if (fallback != null) {
                        selectProfile(fallback)
                    } else {
                        _selectedProfile.value = null
                        profileRepository.setSelectedProfileId(null)
                    }
                }
                
                addLog(subscription.id, subscription.name, LogLevel.SUCCESS, "Subscription '${subscription.name}' refreshed successfully with ${profiles.size} profiles")
                // Refreshing subscriptions must not start a full ping batch or restart the VPN.
            } else {
                addLog(sub.id, sub.name, LogLevel.ERROR, "Could not refresh subscription; saved profiles kept")
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog(subscriptionId, "Subscription", LogLevel.ERROR,
                    (e as? SubscriptionImportException)?.message ?: "Could not refresh subscription; saved profiles kept")
            } finally {
                _refreshingSubscriptions.update { it - subscriptionId }
                subscriptionRefreshJobs.remove(subscriptionId)
            }
        }
    }

    fun deleteSubscription(subscriptionId: String) {
        subscriptionRefreshJobs[subscriptionId]?.cancel()
        if (_selectedSubscriptionId.value == subscriptionId) selectSubscription(null)
        val sub = _subscriptions.value.find { it.id == subscriptionId }
        _subscriptions.update { current ->
            current.filter { it.id != subscriptionId }
        }
        profileRepository.saveSubscriptions(_subscriptions.value)
        
        // Remove associated profiles
        _profiles.update { current ->
            current.filter { it.subscriptionId != subscriptionId }
        }
        profileRepository.saveProfiles(_profiles.value)
        
        // If selected profile was deleted, select first available
        val selectedId = _selectedProfile.value?.id
        val stillExists = _profiles.value.any { it.id == selectedId }
        if (!stillExists) {
            val fallback = _profiles.value.firstOrNull()
            if (fallback != null) {
                selectProfile(fallback)
            } else {
                _selectedProfile.value = null
                profileRepository.setSelectedProfileId(null)
            }
        }
        
        sub?.let {
            addLog(it.id, it.name, LogLevel.INFO, "Subscription '${it.name}' deleted")
        }
    }
    
    override fun onCleared() {
        connectivity.unregisterNetworkCallback(physicalNetworkCallback)
        connectivity.unregisterNetworkCallback(tunnelNetworkCallback)
        super.onCleared()
        statsJob?.cancel()
    }
}


