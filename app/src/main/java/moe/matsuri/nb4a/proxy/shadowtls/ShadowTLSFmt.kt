package moe.matsuri.nb4a.proxy.shadowtls


/** ShadowTLS has no interoperable share URI; the core returns a specific unsupported error. */
fun ShadowTLSBean.toUri(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )
