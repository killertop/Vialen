package io.nekohasekai.sagernet.fmt.socks



fun parseSOCKS(link: String): SOCKSBean {
    val bean = io.nekohasekai.sagernet.fmt.CoreProxyParser.parse(link)
    require(bean is SOCKSBean) { "URI does not describe a SOCKS profile" }
    return bean
}

fun SOCKSBean.toUri(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )

fun SOCKSBean.toV2rayN(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )
