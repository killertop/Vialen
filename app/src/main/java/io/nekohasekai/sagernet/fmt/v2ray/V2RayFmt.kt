package io.nekohasekai.sagernet.fmt.v2ray

fun StandardV2RayBean.isTLS(): Boolean {
    return security == "tls"
}

fun StandardV2RayBean.setTLS(boolean: Boolean) {
    security = if (boolean) "tls" else ""
}

fun parseV2Ray(link: String): StandardV2RayBean =
    io.nekohasekai.sagernet.fmt.CoreProxyParser.parse(link) as VMessBean

fun VMessBean.toV2rayN(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )

fun StandardV2RayBean.toUriVMessVLESSTrojan(isTrojan: Boolean): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )
