package com.vpnapp.vpn.singbox

import com.vpnapp.data.VpnProfile
import org.json.JSONObject

/** Translate share-link extras into the released libbox XHTTP schema. */
internal object XHttpOptions {
    fun build(profile: VpnProfile): JSONObject = JSONObject().apply {
        put("type", "xhttp")
        put("mode", profile.v2rayType.takeUnless { it.isEmpty() || it == "none" } ?: "auto")
        put("path", profile.v2rayPath.ifEmpty { "/" })
        val host = profile.v2rayHost.ifEmpty { profile.v2raySni.ifEmpty { profile.serverAddress } }
        if (host.isNotEmpty()) put("host", host)
        // Packet-up paces individual writes. Avoid adding 30ms to small interactive writes.
        // Explicit subscription values below override this client default. Streamed modes ignore it.
        put("sc_min_posts_interval_ms", "1-1")
        if (profile.v2rayXhttpExtra.isNotBlank()) {
            val extra = JSONObject(profile.v2rayXhttpExtra)
            val ranges = mapOf("xPaddingBytes" to "x_padding_bytes",
                "scMaxEachPostBytes" to "sc_max_each_post_bytes",
                "scMinPostsIntervalMs" to "sc_min_posts_interval_ms")
            for ((camel, snake) in ranges) {
                val value = if (extra.has(camel)) extra.get(camel) else extra.opt(snake)
                if (value != null && value != JSONObject.NULL) {
                    val range = value.toString()
                    require(Regex("\\d+(?:-\\d+)?").matches(range)) { "Invalid XHTTP $camel range" }
                    put(snake, range)
                }
            }
            for ((camel, snake) in mapOf("noGRPCHeader" to "no_grpc_header", "noSSEHeader" to "no_sse_header")) {
                if (extra.has(camel)) put(snake, extra.getBoolean(camel))
                else if (extra.has(snake)) put(snake, extra.getBoolean(snake))
            }
            extra.optJSONObject("headers")?.let { put("headers", it) }
            val strings = mapOf("sessionPlacement" to "session_placement", "sessionKey" to "session_key",
                "seqPlacement" to "seq_placement", "seqKey" to "seq_key",
                "uplinkDataPlacement" to "uplink_data_placement", "uplinkDataKey" to "uplink_data_key",
                "uplinkHTTPMethod" to "uplink_http_method", "xPaddingKey" to "x_padding_key",
                "xPaddingHeader" to "x_padding_header", "xPaddingPlacement" to "x_padding_placement",
                "xPaddingMethod" to "x_padding_method", "uplinkChunkSize" to "uplink_chunk_size")
            for ((camel, snake) in strings) {
                if (extra.has(camel)) put(snake, extra.get(camel).toString())
                else if (extra.has(snake)) put(snake, extra.get(snake).toString())
            }
            if (extra.has("xPaddingObfsMode")) put("x_padding_obfs_mode", extra.getBoolean("xPaddingObfsMode"))
            else if (extra.has("x_padding_obfs_mode")) put("x_padding_obfs_mode", extra.getBoolean("x_padding_obfs_mode"))
            extra.optJSONObject("xmux")?.let { original ->
                val mux = JSONObject()
                for ((camel, snake) in mapOf("maxConcurrency" to "max_concurrency",
                    "maxConnections" to "max_connections", "cMaxReuseTimes" to "c_max_reuse_times",
                    "hMaxRequestTimes" to "h_max_request_times", "hMaxReusableSecs" to "h_max_reusable_secs",
                    "hKeepAlivePeriod" to "h_keep_alive_period")) {
                    if (original.has(camel)) mux.put(snake, original.get(camel))
                    else if (original.has(snake)) mux.put(snake, original.get(snake))
                }
                put("xmux", mux)
            }
        }
    }
}
