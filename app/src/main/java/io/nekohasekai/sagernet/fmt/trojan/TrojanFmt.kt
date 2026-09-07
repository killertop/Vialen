package io.nekohasekai.sagernet.fmt.trojan


fun parseTrojan(server: String): TrojanBean =
    io.nekohasekai.sagernet.fmt.RustProxyParser.parse(server) as TrojanBean
