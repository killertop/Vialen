package io.nekohasekai.sagernet.oracle

// Frozen Kotlin parser oracle from f784dc7. Compiled only into tests.
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.nekohasekai.sagernet.fmt.trojan.*

fun parseTrojan(server: String): TrojanBean {

    val link = server.replace("trojan://", "https://").toHttpUrlOrNull()
        ?: error("invalid trojan link $server")

    return TrojanBean().apply {
        parseDuckSoft(link)
        link.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        link.queryParameter("peer")?.apply { if (this.isNotBlank()) sni = this }
    }

}
