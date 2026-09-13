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
            decode(row.ruleSets)
            for (raw in listOf(row.domains, row.ip, row.source)) {
                require(raw.split(',', '\n').none { val v = it.trim(); v.startsWith("geoip:") || v.startsWith("geosite:") || v.startsWith("geoip-") || v.startsWith("geosite-") || v.contains("://") }) {
                    "Use the rule-set selector instead of GeoIP/Geosite shorthand or URLs in address fields"
                }
            }
            for (raw in listOf(row.port, row.sourcePort)) {
                raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty).forEach { part ->
                    val bounds = part.split(':')
                    require(bounds.size in 1..2) { "Invalid port: $part" }
                    fun port(s: String, default: Int?): Int = (if (s.isEmpty()) default else s.toIntOrNull())?.takeIf { it in 0..65535 } ?: error("Invalid port: $part")
                    if (bounds.size == 1) port(bounds[0], null)
                    else require(port(bounds[0], 0) <= port(bounds[1], 65535)) { "Reversed port range: $part" }
                }
            }
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
