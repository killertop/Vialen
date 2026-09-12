package io.nekohasekai.sagernet.fmt.tuic


fun parseTuic(url: String): TuicBean =
    io.nekohasekai.sagernet.fmt.CoreProxyParser.parse(url) as TuicBean

fun TuicBean.toUri(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )
