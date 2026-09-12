package moe.matsuri.nb4a.proxy.anytls


fun AnyTLSBean.toUri(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )

fun parseAnytls(url: String): AnyTLSBean =
    io.nekohasekai.sagernet.fmt.CoreProxyParser.parse(url) as AnyTLSBean
