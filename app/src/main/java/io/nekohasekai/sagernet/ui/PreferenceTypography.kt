package io.nekohasekai.sagernet.ui

import androidx.annotation.StyleRes
import io.nekohasekai.sagernet.R

/** Natural-language inputs are the default; technical character sequences opt in. */
internal object PreferenceTypography {
    private val technicalKeys = setOf(
        "serverAddress", "localAddress", "host", "path", "sni", "alpn",
        "privateKey", "peerPublicKey", "peerPreSharedKey", "realityPubKey", "realityShortId",
        "certificates", "echConfig", "uuid", "serverUUID", "serverCertificates",
        "remoteDns", "directDns", "routeDomain", "routeIP", "routeSource", "routeSourcePort",
        "routePort", "routeProtocol", "routeNetwork", "routeSSID", "routeBSSID"
    )

    @StyleRes
    fun inputAppearance(key: String?): Int = if (key in technicalKeys) {
        R.style.TextAppearance_Vialen_Input_Technical
    } else {
        R.style.TextAppearance_Vialen_Input
    }
}
