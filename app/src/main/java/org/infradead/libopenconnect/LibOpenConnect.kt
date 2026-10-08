/*
 * OpenConnect (SSL + DTLS) VPN client - JNI Bindings
 * Based on: https://gitlab.com/openconnect/openconnect/-/blob/master/java/src/org/infradead/libopenconnect/LibOpenConnect.java
 *
 * Copyright © 2013 Kevin Cernekee <cernekee@gmail.com>
 * LGPL v2.1
 */

package org.infradead.libopenconnect

abstract class LibOpenConnect {

    /* constants */
    companion object {
        const val OC_PROTO_PROXY = 1
        const val OC_PROTO_CSD = 2
        const val OC_PROTO_AUTH_CERT = 4
        const val OC_PROTO_AUTH_OTP = 8
        const val OC_PROTO_AUTH_STOKEN = 16

        const val OC_FORM_OPT_TEXT = 1
        const val OC_FORM_OPT_PASSWORD = 2
        const val OC_FORM_OPT_SELECT = 3
        const val OC_FORM_OPT_HIDDEN = 4
        const val OC_FORM_OPT_TOKEN = 5

        const val OC_FORM_OPT_IGNORE = 0x0001
        const val OC_FORM_OPT_NUMERIC = 0x0002

        const val OC_TOKEN_MODE_NONE = 0
        const val OC_TOKEN_MODE_STOKEN = 1
        const val OC_TOKEN_MODE_TOTP = 2
        const val OC_TOKEN_MODE_HOTP = 3

        const val OC_FORM_RESULT_ERR = -1
        const val OC_FORM_RESULT_OK = 0
        const val OC_FORM_RESULT_CANCELLED = 1
        const val OC_FORM_RESULT_NEWGROUP = 2

        const val PRG_ERR = 0
        const val PRG_INFO = 1
        const val PRG_DEBUG = 2
        const val PRG_TRACE = 3

        const val RECONNECT_INTERVAL_MIN = 10
        const val RECONNECT_INTERVAL_MAX = 100

        @JvmStatic external fun getVersion(): String?
        @JvmStatic external fun hasPKCS11Support(): Boolean
        @JvmStatic external fun hasTSSBlobSupport(): Boolean
        @JvmStatic external fun hasTSS2BlobSupport(): Boolean
        @JvmStatic external fun hasStokenSupport(): Boolean
        @JvmStatic external fun hasOATHSupport(): Boolean
        @JvmStatic external fun hasYubiOATHSupport(): Boolean
        @JvmStatic external fun getSupportedProtocols(): Array<VPNProto>?

        @JvmStatic @Synchronized external fun globalInit()

        init {
            try {
                System.loadLibrary("stoken")
                System.loadLibrary("openconnect")
                globalInit()
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("LibOpenConnect", "Failed to load native library: ${e.message}")
            }
        }
    }

    /* required callbacks */
    abstract fun onProcessAuthForm(authForm: AuthForm): Int
    abstract fun onProgress(level: Int, msg: String)

    /* optional callbacks */
    open fun onValidatePeerCert(msg: String): Int = 0
    open fun onWriteNewConfig(buf: ByteArray): Int = 0
    open fun onProtectSocket(fd: Int) {}
    open fun onStatsUpdate(stats: VPNStats) {}
    open fun onTokenLock(): Int = 0
    open fun onTokenUnlock(newToken: String): Int = 0
    open fun onSetupTun() {}
    open fun onReconnected() {}

    /* create/destroy library instances */
    constructor() {
        libctx = init("OpenConnect VPN Agent (Java)")
    }

    constructor(userAgent: String) {
        libctx = init(userAgent)
    }

    @Synchronized
    fun destroy() {
        if (libctx != 0L) {
            free()
            libctx = 0L
        }
    }

    /* async requests */
    fun cancel() {
        synchronized(asyncLock) {
            if (!canceled) {
                doCancel()
                canceled = true
            }
        }
    }

    fun isCanceled(): Boolean {
        synchronized(asyncLock) {
            return canceled
        }
    }

    external fun pause()
    external fun requestStats()
    external fun setLogLevel(level: Int)

    /* control operations */
    @Synchronized external fun parseURL(url: String): Int
    @Synchronized external fun obtainCookie(): Int
    @Synchronized external fun clearCookie()
    @Synchronized external fun resetSSL()
    @Synchronized external fun makeCSTPConnection(): Int
    @Synchronized external fun setupTunDevice(vpncScript: String, IFName: String): Int
    @Synchronized external fun setupTunScript(tunScript: String): Int
    @Synchronized external fun setupTunFD(tunFD: Int): Int
    @Synchronized external fun setupDTLS(attemptPeriod: Int): Int
    @Synchronized external fun mainloop(reconnectTimeout: Int, reconnectInterval: Int): Int

    /* connection settings */
    @Synchronized external fun passphraseFromFSID(): Int
    @Synchronized external fun setCertExpiryWarning(seconds: Int)
    @Synchronized external fun setDPD(minSeconds: Int)
    @Synchronized external fun setTrojanInterval(seconds: Int)
    @Synchronized external fun setPassTOS(isEnabled: Boolean)
    @Synchronized external fun setProxyAuth(methods: String): Int
    @Synchronized external fun setHTTPProxy(proxy: String): Int
    @Synchronized external fun setSNI(sni: String)
    @Synchronized external fun setXMLSHA1(hash: String)
    @Synchronized external fun setHostname(hostname: String)
    @Synchronized external fun setVersionString(version: String)
    @Synchronized external fun setUseragent(useragent: String)
    @Synchronized external fun setUrlpath(urlpath: String)
    @Synchronized external fun setLocalName(localName: String)
    @Synchronized external fun setCAFile(caFile: String)
    @Synchronized external fun setReportedOS(os: String)
    @Synchronized external fun setMobileInfo(mobilePlatformVersion: String, mobileDeviceType: String, mobileDeviceUniqueID: String)
    @Synchronized external fun setTokenMode(tokenMode: Int, tokenString: String): Int
    @Synchronized external fun setCSDWrapper(wrapper: String, TMPDIR: String, PATH: String)
    @Synchronized external fun setXMLPost(isEnabled: Boolean)
    @Synchronized external fun setReqMTU(mtu: Int)
    @Synchronized external fun setPFS(isEnabled: Boolean)
    @Synchronized external fun setAllowInsecureCrypto(isEnabled: Boolean): Int
    @Synchronized external fun setSystemTrust(isEnabled: Boolean)
    @Synchronized external fun setProtocol(protocol: String): Int
    @Synchronized external fun disableDTLS(): Int
    @Synchronized external fun disableIPv6(): Int
    @Synchronized external fun setCookie(cookie: String)

    /* client certificate(s) */
    @Synchronized external fun setClientCert(cert: String, sslKey: String): Int
    @Synchronized external fun setKeyPassword(password: String): Int
    @Synchronized external fun setMCACert(cert: String, sslKey: String): Int
    @Synchronized external fun setMCAKeyPassword(password: String): Int

    /* connection info */
    @Synchronized external fun getConnectUrl(): String?
    @Synchronized external fun getHostname(): String?
    @Synchronized external fun getDNSName(): String?
    @Synchronized external fun getUrlpath(): String?
    @Synchronized external fun getPort(): Int
    @Synchronized external fun getCookie(): String?
    @Synchronized external fun getIFName(): String?
    @Synchronized external fun getIPInfo(): IPInfo?
    @Synchronized external fun getCSTPCipher(): String?
    @Synchronized external fun getDTLSCipher(): String?
    @Synchronized external fun getCSTPCompression(): String?
    @Synchronized external fun getDTLSCompression(): String?
    @Synchronized external fun getProtocol(): String?
    @Synchronized external fun getIdleTimeout(): Int

    /* peer certificate info */
    @Synchronized external fun checkPeerCertHash(hash: String): Int
    @Synchronized external fun getPeerCertHash(): String?
    @Synchronized external fun getPeerCertDetails(): String?
    @Synchronized external fun getPeerCertDER(): ByteArray?
    @Synchronized external fun getPeerCertChain(): Array<ByteArray>?

    /* data structures */

    class FormOpt {
        @JvmField var type: Int = 0
        @JvmField var name: String? = null
        @JvmField var label: String? = null
        @JvmField var flags: Long = 0
        @JvmField var choices: ArrayList<FormChoice> = ArrayList()
        @JvmField var value: String? = null
        @JvmField var userData: Any? = null

        fun addChoice(fc: FormChoice) {
            choices.add(fc)
        }
    }

    class FormChoice {
        @JvmField var name: String? = null
        @JvmField var label: String? = null
        @JvmField var authType: String? = null
        @JvmField var overrideName: String? = null
        @JvmField var overrideLabel: String? = null
        @JvmField var userData: Any? = null
    }

    class AuthForm {
        @JvmField var banner: String? = null
        @JvmField var message: String? = null
        @JvmField var error: String? = null
        @JvmField var authID: String? = null
        @JvmField var method: String? = null
        @JvmField var action: String? = null
        @JvmField var opts: ArrayList<FormOpt> = ArrayList()
        @JvmField var authgroupOpt: FormOpt? = null
        @JvmField var authgroupSelection: Int = 0
        @JvmField var userData: Any? = null

        fun addOpt(isAuthgroup: Boolean): FormOpt {
            val fo = FormOpt()
            opts.add(fo)
            if (isAuthgroup) {
                authgroupOpt = fo
            }
            return fo
        }

        fun getOptValue(name: String): String? {
            for (fo in opts) {
                if (fo.name == name) {
                    return fo.value
                }
            }
            return null
        }
    }

    class IPInfo {
        @JvmField var addr: String? = null
        @JvmField var netmask: String? = null
        @JvmField var addr6: String? = null
        @JvmField var netmask6: String? = null
        @JvmField var DNS: ArrayList<String> = ArrayList()
        @JvmField var NBNS: ArrayList<String> = ArrayList()
        @JvmField var domain: String? = null
        @JvmField var proxyPac: String? = null
        @JvmField var gatewayAddr: String? = null
        @JvmField var MTU: Int = 0

        @JvmField var splitDNS: ArrayList<String> = ArrayList()
        @JvmField var splitIncludes: ArrayList<String> = ArrayList()
        @JvmField var splitExcludes: ArrayList<String> = ArrayList()
        @JvmField var CSTPOptions: HashMap<String, String> = HashMap()
        @JvmField var DTLSOptions: HashMap<String, String> = HashMap()
        @JvmField var userData: Any? = null

        fun addDNS(arg: String) { DNS.add(arg) }
        fun addNBNS(arg: String) { NBNS.add(arg) }
        fun addSplitDNS(arg: String) { splitDNS.add(arg) }
        fun addSplitInclude(arg: String) { splitIncludes.add(arg) }
        fun addSplitExclude(arg: String) { splitExcludes.add(arg) }
        fun addCSTPOption(key: String, value: String) { CSTPOptions[key] = value }
        fun addDTLSOption(key: String, value: String) { DTLSOptions[key] = value }
    }

    class VPNStats {
        @JvmField var txPkts: Long = 0
        @JvmField var txBytes: Long = 0
        @JvmField var rxPkts: Long = 0
        @JvmField var rxBytes: Long = 0
        @JvmField var userData: Any? = null
    }

    class VPNProto {
        @JvmField var name: String? = null
        @JvmField var prettyName: String? = null
        @JvmField var description: String? = null
        @JvmField var flags: Int = 0
    }

    /* Optional storage for caller's data */
    @JvmField var userData: Any? = null

    /* Internals */
    @JvmField var libctx: Long = 0
    @JvmField var canceled: Boolean = false
    @JvmField var asyncLock: Any = Object()

    @Synchronized external fun init(useragent: String): Long
    @Synchronized external fun free()
    external fun doCancel()
}
