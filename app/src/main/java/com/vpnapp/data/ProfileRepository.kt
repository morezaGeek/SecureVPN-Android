package com.vpnapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for persisting VPN profiles to SharedPreferences.
 * Profiles survive app restarts and updates.
 */
class ProfileRepository(context: Context) {
    
    companion object {
        private const val TAG = "ProfileRepository"
        private const val PREFS_NAME = "vpn_profiles"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val KEY_SUBSCRIPTIONS = "subscriptions_json"
        private const val KEY_SELECTED_SUBSCRIPTION = "selected_subscription_id"
        
        @Volatile
        private var INSTANCE: ProfileRepository? = null
        
        fun getInstance(context: Context): ProfileRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfileRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Save all profiles to SharedPreferences
     */
    fun saveProfiles(profiles: List<VpnProfile>) {
        try {
            val jsonArray = JSONArray()
            profiles.forEach { profile ->
                jsonArray.put(profileToJson(profile))
            }
            prefs.edit().putString(KEY_PROFILES, jsonArray.toString()).apply()
            Log.d(TAG, "Saved ${profiles.size} profiles")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving profiles", e)
        }
    }
    
    /**
     * Load all profiles from SharedPreferences
     */
    fun loadProfiles(): List<VpnProfile> {
        try {
            val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
            val jsonArray = JSONArray(json)
            val profiles = mutableListOf<VpnProfile>()
            
            for (i in 0 until jsonArray.length()) {
                val profileJson = jsonArray.getJSONObject(i)
                profiles.add(jsonToProfile(profileJson))
            }
            
            Log.d(TAG, "Loaded ${profiles.size} profiles")
            return profiles
        } catch (e: Exception) {
            Log.e(TAG, "Error loading profiles", e)
            return emptyList()
        }
    }
    
    /**
     * Get the ID of the last selected profile
     */
    fun getSelectedProfileId(): String? {
        return prefs.getString(KEY_SELECTED_PROFILE_ID, null)
    }
    
    /**
     * Save the ID of the currently selected profile
     */
    fun setSelectedProfileId(id: String?) {
        prefs.edit().putString(KEY_SELECTED_PROFILE_ID, id).apply()
    }

    fun getSelectedSubscriptionId(): String? = prefs.getString(KEY_SELECTED_SUBSCRIPTION, null)

    fun setSelectedSubscriptionId(id: String?) {
        prefs.edit().putString(KEY_SELECTED_SUBSCRIPTION, id).apply()
    }
    
    /**
     * Save all subscriptions to SharedPreferences
     */
    fun saveSubscriptions(subscriptions: List<VpnSubscription>) {
        try {
            val jsonArray = JSONArray()
            subscriptions.forEach { sub ->
                jsonArray.put(JSONObject().apply {
                    put("id", sub.id)
                    put("name", sub.name)
                    put("url", sub.url)
                    put("upload", sub.upload)
                    put("download", sub.download)
                    put("total", sub.total)
                    put("expire", sub.expire)
                    put("lastUpdated", sub.lastUpdated)
                })
            }
            prefs.edit().putString(KEY_SUBSCRIPTIONS, jsonArray.toString()).apply()
            Log.d(TAG, "Saved ${subscriptions.size} subscriptions")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving subscriptions", e)
        }
    }
    
    /**
     * Load all subscriptions from SharedPreferences
     */
    fun loadSubscriptions(): List<VpnSubscription> {
        try {
            val json = prefs.getString(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
            val jsonArray = JSONArray(json)
            val subscriptions = mutableListOf<VpnSubscription>()
            
            for (i in 0 until jsonArray.length()) {
                val subJson = jsonArray.getJSONObject(i)
                subscriptions.add(VpnSubscription(
                    id = subJson.optString("id", java.util.UUID.randomUUID().toString()),
                    name = subJson.optString("name", ""),
                    url = subJson.optString("url", ""),
                    upload = subJson.optLong("upload", 0L),
                    download = subJson.optLong("download", 0L),
                    total = subJson.optLong("total", 0L),
                    expire = subJson.optLong("expire", 0L),
                    lastUpdated = subJson.optLong("lastUpdated", System.currentTimeMillis())
                ))
            }
            
            Log.d(TAG, "Loaded ${subscriptions.size} subscriptions")
            return subscriptions
        } catch (e: Exception) {
            Log.e(TAG, "Error loading subscriptions", e)
            return emptyList()
        }
    }
    
    // --- JSON Serialization ---
    
    private fun profileToJson(profile: VpnProfile): JSONObject {
        return JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("serverAddress", profile.serverAddress)
            put("protocol", profile.protocol.name)
            put("port", profile.port)
            put("username", profile.username)
            put("password", profile.password)
            put("authType", profile.authType.name)
            put("skipCertificateVerification", profile.skipCertificateVerification)
            put("useNative", profile.useNative)
            put("disableDtls", profile.disableDtls)
            put("mtu", profile.mtu)
            put("countryCode", profile.countryCode)
            put("isDefault", profile.isDefault)
            put("lastConnected", profile.lastConnected)
            put("createdAt", profile.createdAt)
            // V2ray fields
            put("v2rayUuid", profile.v2rayUuid)
            put("v2rayAlterId", profile.v2rayAlterId)
            put("v2raySecurity", profile.v2raySecurity)
            put("vlessEncryption", profile.vlessEncryption)
            put("v2rayNetwork", profile.v2rayNetwork)
            put("v2rayType", profile.v2rayType)
            put("v2rayHost", profile.v2rayHost)
            put("v2rayPath", profile.v2rayPath)
            put("v2rayTls", profile.v2rayTls)
            put("v2raySni", profile.v2raySni)
            put("v2rayAlpn", profile.v2rayAlpn)
            put("v2rayFp", profile.v2rayFp)
            put("v2rayFlow", profile.v2rayFlow)
            put("v2rayPbk", profile.v2rayPbk)
            put("v2rayShortId", profile.v2rayShortId)
            put("v2rayXhttpExtra", profile.v2rayXhttpExtra)
            profile.v2rayOriginalXhttpMode?.let { put("v2rayOriginalXhttpMode", it) }
            profile.v2rayXhttpModeOverride?.let { put("v2rayXhttpModeOverride", it) }
            profile.v2raySmartXhttpMode?.let { put("v2raySmartXhttpMode", it) }
            put("subscriptionId", profile.subscriptionId ?: "")
            put("hysteriaObfs", profile.hysteriaObfs)
            put("hysteriaObfsPassword", profile.hysteriaObfsPassword)
        }
    }
    
    private fun jsonToProfile(json: JSONObject): VpnProfile {
        return VpnProfile(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            serverAddress = json.optString("serverAddress", ""),
            protocol = try { 
                VpnProtocol.valueOf(json.optString("protocol", "OPENCONNECT"))
            } catch (e: Exception) { 
                VpnProtocol.OPENCONNECT 
            },
            port = json.optInt("port", 443),
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            authType = try {
                AuthType.valueOf(json.optString("authType", "PASSWORD"))
            } catch (e: Exception) {
                AuthType.PASSWORD
            },
            skipCertificateVerification = json.optBoolean("skipCertificateVerification", true),
            useNative = json.optBoolean("useNative", true),
            disableDtls = json.optBoolean("disableDtls", false),
            mtu = json.optInt("mtu", 1400),
            countryCode = json.optString("countryCode", ""),
            isDefault = json.optBoolean("isDefault", false),
            lastConnected = json.optLong("lastConnected", 0L),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            // V2ray fields
            v2rayUuid = json.optString("v2rayUuid", ""),
            v2rayAlterId = json.optInt("v2rayAlterId", 0),
            v2raySecurity = json.optString("v2raySecurity", ""),
            vlessEncryption = json.optString("vlessEncryption", "none").trim().ifEmpty { "none" },
            v2rayNetwork = json.optString("v2rayNetwork", "tcp"),
            v2rayType = json.optString("v2rayType", "none"),
            v2rayHost = json.optString("v2rayHost", ""),
            v2rayPath = json.optString("v2rayPath", ""),
            v2rayTls = json.optString("v2rayTls", "none"),
            v2raySni = json.optString("v2raySni", ""),
            v2rayAlpn = json.optString("v2rayAlpn", ""),
            v2rayFp = json.optString("v2rayFp", ""),
            v2rayFlow = json.optString("v2rayFlow", ""),
            v2rayPbk = json.optString("v2rayPbk", ""),
            v2rayShortId = json.optString("v2rayShortId", ""),
            v2rayXhttpExtra = json.optString("v2rayXhttpExtra", ""),
            v2rayOriginalXhttpMode = if (json.has("v2rayOriginalXhttpMode") && !json.isNull("v2rayOriginalXhttpMode"))
                json.getString("v2rayOriginalXhttpMode") else null,
            v2rayXhttpModeOverride = if (json.has("v2rayXhttpModeOverride") && !json.isNull("v2rayXhttpModeOverride"))
                json.getString("v2rayXhttpModeOverride") else null,
            v2raySmartXhttpMode = if (json.has("v2raySmartXhttpMode") && !json.isNull("v2raySmartXhttpMode"))
                json.getString("v2raySmartXhttpMode") else null,
            hysteriaObfs = json.optString("hysteriaObfs", ""),
            hysteriaObfsPassword = json.optString("hysteriaObfsPassword", ""),
            subscriptionId = json.optString("subscriptionId", "").ifEmpty { null }
        )
    }
}
