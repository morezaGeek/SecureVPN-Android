package com.vpnapp.vpn.singbox

import android.content.Context
import android.util.Log

/**
 * Provides comprehensive lists of Iranian IP CIDRs and Domains for routing and DNS bypass.
 * Loads 1,800+ merged CIDRs from RIPE NCC and 57,500+ domains from bootmortis/iran-hosted-domains.
 */
object IranIps {

    private const val TAG = "IranIps"

    @Volatile
    private var cachedCidrs: List<String>? = null

    @Volatile
    private var cachedDomains: List<String>? = null

    /**
     * Iranian public IPv4 CIDRs. Private destinations are governed by the LAN switch.
     * Loads from assets/iran_cidrs.txt with memory caching.
     */
    fun getCidrs(context: Context?): List<String> {
        cachedCidrs?.let { return it }
        synchronized(this) {
            cachedCidrs?.let { return it }
            if (context != null) {
                try {
                    context.assets.open("iran_cidrs.txt").bufferedReader().useLines { lines ->
                        val list = lines.map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && BypassRouting.isPublicIpv4Cidr(it) }
                            .toList()
                        if (list.isNotEmpty()) {
                            Log.i(TAG, "Loaded ${list.size} Iranian CIDRs from assets")
                            cachedCidrs = list
                            return list
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load iran_cidrs.txt from assets: ${e.message}")
                }
            }
            cachedCidrs = fallbackCidrs
            return fallbackCidrs
        }
    }

    /**
     * Get the complete list of non-.ir Iranian domains (e.g. .com, .org, .net, .ai, .taxi).
     * (.ir domains are handled by TLD suffix match).
     * Loads from assets/iran_domains.txt with memory caching.
     */
    fun getDomains(context: Context?): List<String> {
        cachedDomains?.let { return it }
        synchronized(this) {
            cachedDomains?.let { return it }
            if (context != null) {
                try {
                    context.assets.open("iran_domains.txt").bufferedReader().useLines { lines ->
                        val list = lines.map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .toList()
                        if (list.isNotEmpty()) {
                            Log.i(TAG, "Loaded ${list.size} Iranian domains from assets")
                            cachedDomains = list
                            return list
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load iran_domains.txt from assets: ${e.message}")
                }
            }
            cachedDomains = fallbackDomains
            return fallbackDomains
        }
    }

    /**
     * Backward-compatible property accessor for callers without Context.
     */
    val cidrs: List<String>
        get() = cachedCidrs ?: fallbackCidrs

    /**
     * Backward-compatible property accessor for callers without Context.
     */
    val domains: List<String>
        get() = cachedDomains ?: fallbackDomains

    // Fallback list of major Iranian CIDR blocks + government private networks
    private val fallbackCidrs = listOf(
        // Major Iranian Public Supernets
        "2.144.0.0/14",
        "2.176.0.0/12",
        "5.22.0.0/16",
        "5.52.0.0/15",
        "5.72.0.0/14",
        "5.104.0.0/14",
        "5.112.0.0/12",
        "5.144.0.0/13",
        "5.160.0.0/13",
        "5.190.0.0/15",
        "5.200.0.0/13",
        "5.208.0.0/13",
        "5.216.0.0/13",
        "5.232.0.0/13",
        "31.2.0.0/15",
        "31.7.0.0/16",
        "31.24.0.0/14",
        "31.40.0.0/14",
        "31.130.0.0/15",
        "31.170.0.0/15",
        "31.184.0.0/15",
        "31.214.0.0/15",
        "37.32.0.0/15",
        "37.44.0.0/15",
        "37.98.0.0/15",
        "37.114.0.0/15",
        "37.128.0.0/14",
        "37.148.0.0/14",
        "37.152.0.0/13",
        "37.191.0.0/16",
        "37.202.0.0/15",
        "37.221.0.0/16",
        "37.235.0.0/16",
        "37.254.0.0/15",
        "45.8.0.0/14",
        "45.80.0.0/12",
        "45.132.0.0/14",
        "45.144.0.0/13",
        "45.156.0.0/14",
        "77.36.0.0/15",
        "77.42.0.0/15",
        "77.77.0.0/16",
        "77.104.0.0/15",
        "77.237.0.0/16",
        "77.238.0.0/15",
        "78.38.0.0/15",
        "78.109.0.0/16",
        "78.110.0.0/15",
        "78.154.0.0/15",
        "78.157.0.0/16",
        "78.158.0.0/15",
        "79.127.0.0/16",
        "79.132.0.0/15",
        "79.170.0.0/15",
        "79.174.0.0/15",
        "80.66.0.0/15",
        "80.75.0.0/16",
        "80.191.0.0/16",
        "80.253.0.0/16",
        "81.12.0.0/15",
        "81.28.0.0/15",
        "81.90.0.0/15",
        "82.99.0.0/16",
        "82.102.0.0/16",
        "82.115.0.0/16",
        "83.120.0.0/13",
        "84.47.0.0/16",
        "84.241.0.0/16",
        "85.9.0.0/16",
        "85.15.0.0/16",
        "85.133.0.0/16",
        "85.185.0.0/16",
        "85.198.0.0/16",
        "86.55.0.0/16",
        "86.57.0.0/16",
        "86.104.0.0/14",
        "87.107.0.0/16",
        "87.236.0.0/15",
        "87.247.0.0/16",
        "87.248.0.0/15",
        "89.144.0.0/13",
        "89.165.0.0/16",
        "89.196.0.0/14",
        "89.221.0.0/16",
        "91.92.0.0/14",
        "91.98.0.0/15",
        "91.108.0.0/16",
        "91.133.0.0/16",
        "91.184.0.0/14",
        "91.206.0.0/15",
        "91.212.0.0/14",
        "91.222.0.0/15",
        "91.232.0.0/13",
        "91.240.0.0/14",
        "91.244.0.0/14",
        "92.42.0.0/16",
        "92.50.0.0/15",
        "92.61.0.0/16",
        "92.114.0.0/15",
        "92.242.0.0/15",
        "93.88.0.0/15",
        "93.110.0.0/15",
        "93.113.0.0/16",
        "93.114.0.0/15",
        "93.117.0.0/16",
        "93.118.0.0/15",
        "94.24.0.0/15",
        "94.54.0.0/15",
        "94.74.0.0/15",
        "94.101.0.0/16",
        "94.139.0.0/16",
        "94.182.0.0/15",
        "94.232.0.0/15",
        "95.38.0.0/15",
        "95.80.0.0/14",
        "95.130.0.0/15",
        "95.160.0.0/15",
        "95.170.0.0/15",
        "109.95.0.0/16",
        "109.109.0.0/16",
        "109.122.0.0/15",
        "109.125.0.0/16",
        "109.162.0.0/15",
        "109.201.0.0/16",
        "109.230.0.0/15",
        "149.54.0.0/16",
        "151.232.0.0/13",
        "151.240.0.0/13",
        "158.58.0.0/16",
        "164.138.0.0/15",
        "176.101.0.0/16",
        "176.102.0.0/15",
        "178.131.0.0/16",
        "178.239.0.0/16",
        "178.252.0.0/15",
        "185.4.0.0/14",
        "185.8.0.0/13",
        "185.16.0.0/12",
        "185.37.0.0/16",
        "185.45.0.0/16",
        "185.50.0.0/15",
        "185.55.0.0/16",
        "185.80.0.0/14",
        "185.88.0.0/13",
        "185.96.0.0/11",
        "185.128.0.0/12",
        "185.144.0.0/13",
        "185.160.0.0/12",
        "185.190.0.0/15",
        "185.200.0.0/13",
        "185.208.0.0/13",
        "185.216.0.0/13",
        "185.224.0.0/11",
        "188.121.0.0/16",
        "188.136.0.0/15",
        "188.158.0.0/15",
        "188.208.0.0/13",
        "188.245.0.0/16",
        "188.253.0.0/16",
        "212.16.64.0/19",
        "212.33.192.0/19",
        "212.80.0.0/19",
        "212.86.64.0/19",
        "212.120.192.0/19",
        "213.176.0.0/17",
        "213.195.0.0/17",
        "213.207.192.0/18",
        "213.233.160.0/19",
        "217.218.0.0/15"
    )

    // Fallback list of prominent non-.ir Iranian services
    private val fallbackDomains = listOf(
        "digikala.com", "aparat.com", "filimo.com", "namava.ir", "telewebion.com",
        "varzesh3.com", "shaparak.ir", "samanepay.com", "behpardakht.com", "sadadpsp.ir",
        "mrbilit.com", "snapp.taxi", "snapp.market", "zarinpal.com", "arzdigital.com",
        "paziresh24.com", "rubika.ir", "eitaa.com", "bale.ai", "bankpasargad.com",
        "sheypoor.com", "torob.com", "tgju.org", "tasnimnews.com", "mehrnews.com",
        "asriran.com", "khabarfoori.com", "zoomit.ir", "digiato.com", "ninisite.com",
        "rokna.net", "beytoote.com", "alopeyk.com", "snapptrip.com", "otaghak.com",
        "jabama.com", "jajiga.com", "nobitex.ir", "wallex.ir", "tabnak.ir"
    )
}
