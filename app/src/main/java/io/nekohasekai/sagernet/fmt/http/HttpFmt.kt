package io.nekohasekai.sagernet.fmt.http

import io.nekohasekai.sagernet.core.CoreClient
import io.nekohasekai.sagernet.fmt.CoreProxyParser
import io.nekohasekai.sagernet.fmt.ProfileAdapter

fun parseHttp(link: String): HttpBean = CoreProxyParser.parse(link) as HttpBean

fun HttpBean.toUri(): String = CoreClient.exportURI(ProfileAdapter.fromBean(this))
