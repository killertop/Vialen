package io.nekohasekai.sagernet.database

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.io.File

/** Native sing-box rule-set reference. No GeoIP database or shorthand expansion. */
data class RouteRuleSet(
    val name: String,
    val source: String,
    val format: String = "binary",
    val match: String = "destination",
) {
    fun validate(): RouteRuleSet = apply {
        require(name.isNotBlank()) { "Rule-set name is required" }
        require(format in setOf("binary", "source")) { "Invalid rule-set format" }
        require(match in setOf("destination", "source", "rule")) { "Invalid rule-set match direction" }
        val uri = if (source.startsWith('/')) File(source).toURI() else URI(source)
        val imported = source.matches(Regex("rule-sets/[A-Za-z0-9][A-Za-z0-9._-]*"))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null ||
            source.startsWith("/") || imported) { "Use an HTTPS rule-set URL or an imported local file" }
        require(!uri.path.orEmpty().endsWith(".db", true)) { "GeoIP databases are not supported; use .srs" }
        io.nekohasekai.sagernet.core.CoreClient.validateRuleMatch(null, listOf(validationMetadata("editor")))
    }

    /** Metadata-only projection: no asset extraction, download or rule-file opening. */
    private fun validationMetadata(id: String) = JsonObject().apply {
        addProperty("id", id)
        addProperty("format", format)
        val remote = source.startsWith("https://")
        addProperty("type", if (remote) "remote" else "local")
        val location = if (source.startsWith("rule-sets/"))
            File(io.nekohasekai.sagernet.SagerNet.application.filesDir, source).absolutePath else source
        addProperty(if (remote) "url" else "path", location)
    }

    fun json() = JsonObject().apply {
        addProperty("name", name)
        addProperty("source", source)
        addProperty("format", format)
        addProperty("match", match)
    }

    // Resolve the current Android user's files directory only at snapshot time.
    // Stored imported references remain portable across device backup/restore.
    fun snapshotJson(filesDir: () -> File) = json().apply {
        if (source.startsWith("rule-sets/")) addProperty("source", File(filesDir(), source).absolutePath)
        if (source.startsWith("https://")) {
            val root = filesDir()
            val initial = RuleSetDownloads.file(root, this@RouteRuleSet).takeIf { it.isFile }
                ?: BundledRuleSets.prepare(root, this@RouteRuleSet) {
                    io.nekohasekai.sagernet.SagerNet.application.assets.open(it)
                }
            initial?.let {
                addProperty("initial_path", it.absolutePath)
            }
        }
    }

    companion object {
        fun validateRule(row: RuleEntity) {
            val refs = decode(row.ruleSets)
            refs.filter { !it.source.startsWith("https://") }.forEach {
                val file = if (it.source.startsWith('/')) File(it.source)
                    else File(io.nekohasekai.sagernet.SagerNet.application.filesDir, it.source)
                require(file.isFile && file.canRead()) { "规则文件不存在，请重新导入" }
            }
            val uids = if (row.packages.isEmpty()) emptyList() else {
                val packages = io.nekohasekai.sagernet.utils.PackageCache.snapshot()
                row.packages.map {
                    requireNotNull(packages.packageMap[it]) { "应用已卸载，请重新选择" }
                        .also { uid -> require(uid >= 0) { "应用信息无效，请重新选择" } }
                }
            }
            for (raw in listOf(row.domains, row.ip, row.source)) {
                require(raw.split(',', '\n').none { val v = it.trim(); v.startsWith("geoip:") || v.startsWith("geosite:") || v.startsWith("geoip-") || v.startsWith("geosite-") || v.contains("://") }) {
                    "Use the rule-set selector instead of GeoIP/Geosite shorthand or URLs in address fields"
                }
            }
            val match = io.nekohasekai.sagernet.fmt.ConfigSnapshot.match(row, uids)
            match.add("rule_set_ids", com.google.gson.Gson().toJsonTree(refs.indices.map { "set-$it" }))
            io.nekohasekai.sagernet.core.CoreClient.validateRuleMatch(match,
                refs.mapIndexed { index, ref -> ref.validationMetadata("set-$index") })
        }
        fun decode(raw: String): List<RouteRuleSet> {
            if (raw.isBlank()) return emptyList()
            return JsonParser.parseString(raw).asJsonArray.map {
                val o = it.asJsonObject
                require(o.keySet() == setOf("name", "source", "format", "match")) { "Invalid rule-set reference" }
                RouteRuleSet(o["name"].asString, o["source"].asString, o["format"].asString, o["match"].asString).validate()
            }
        }

        fun encode(refs: List<RouteRuleSet>): String = JsonArray().apply {
            refs.distinct().forEach { add(it.validate().json()) }
        }.toString()

        fun official(kind: String, category: String, name: String = category, match: String = "destination"): RouteRuleSet {
            require(kind in setOf("geoip", "geosite"))
            require(category.matches(Regex("[a-z0-9][a-z0-9@!._-]*"))) { "Invalid rule-set category" }
            return RouteRuleSet(name, "https://raw.githubusercontent.com/SagerNet/sing-$kind/rule-set/$kind-$category.srs", match = match)
        }
    }
}
