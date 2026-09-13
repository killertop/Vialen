package io.nekohasekai.sagernet.database

import java.io.File
import java.io.InputStream

/** Public, verified bootstrap data; downloaded updates always take precedence. */
internal object BundledRuleSets {
    private val names = mapOf(
        RouteRuleSet.official("geosite", "category-ads-all").source to "ads.srs",
        DefaultRouteRules.GOOGLE_SOURCE to "google.srs",
        RouteRuleSet.official("geosite", "cn").source to "cn.srs",
        RouteRuleSet.official("geoip", "cn").source to "cn-ip.srs",
    )

    fun contains(ref: RouteRuleSet) = ref.format == "binary" && ref.source in names

    @Synchronized
    fun prepare(root: File, ref: RouteRuleSet, open: (String) -> InputStream): File? {
        if (ref.format != "binary") return null
        val name = names[ref.source] ?: return null
        // Keep bootstrap separate from the downloaded cache and its last-success metadata.
        val target = File(root, "bundled-rule-sets/$name")
        // Compare against the current APK, including after upgrades or damaged local copies.
        val bundled = open("rule-sets/$name").use { it.readBytes() }
        check(bundled.isNotEmpty()) { "内置规则为空，请重新安装应用" }
        if (target.isFile && runCatching { target.readBytes().contentEquals(bundled) }.getOrDefault(false)) return target
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val stage = File.createTempFile("seed-", ".tmp", target.parentFile)
        try {
            stage.writeBytes(bundled)
            check(stage.length() > 0 && stage.renameTo(target)) { "无法保存内置规则，请检查存储空间" }
        } finally { stage.delete() }
        return target
    }
}
