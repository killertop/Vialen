package io.nekohasekai.sagernet.database

/** One-time database upgrade only. Runtime input does not accept these old forms. */
internal object RouteRulesMigration {
    fun migrate(row: RuleEntity): RuleEntity {
        val refs = RouteRuleSet.decode(row.ruleSets).toMutableList()
        fun convert(raw: String, source: Boolean, domain: Boolean): String = raw.split(',', '\n')
            .map(String::trim).filter(String::isNotEmpty).mapNotNull { item ->
                val kind = when {
                    item.startsWith("geosite:") || item.startsWith("geosite-") -> "geosite"
                    item.startsWith("geoip:") || item.startsWith("geoip-") -> "geoip"
                    else -> null
                }
                when {
                    kind != null -> {
                        require(!source || kind == "geoip") { "Rule ${row.id}: source requires an IP rule-set" }
                        val category = item.substring(kind.length + 1).lowercase()
                        if (kind == "geoip" && category == "private") {
                            if (source) row.sourceIpIsPrivate = true else row.ipIsPrivate = true
                        } else refs += RouteRuleSet.official(kind, category, "$kind-$category", if (source) "source" else "destination")
                        null
                    }
                    item.startsWith("https://") || item.startsWith("http://") -> {
                        // Arbitrary sets can contain multiple/inverted rules. Never guess their
                        // interaction with sibling native conditions during the one-time upgrade.
                        require(raw.split(',', '\n').count { it.isNotBlank() } == 1 &&
                            (if (domain) row.ip.isBlank() else row.domains.isBlank())) {
                            "Rule ${row.id}: separate the custom rule-set from mixed address conditions before upgrading"
                        }
                        require(item.startsWith("https://")) { "Rule ${row.id}: replace the HTTP rule-set URL with HTTPS" }
                        val base = item.substringBefore('?').substringAfterLast('/')
                        refs += RouteRuleSet(base, item, if (base.endsWith(".srs")) "binary" else "source", if (source) "source" else "rule").validate()
                        null
                    }
                    else -> item
                }
            }.joinToString("\n")
        // Validate ambiguous URL combinations against the ORIGINAL fields before any mutation.
        if (listOf(row.domains, row.ip, row.source).any { it.contains("://") } &&
            (listOf(row.domains, row.ip, row.source).count { it.isNotBlank() } > 1 ||
                listOf(row.port, row.sourcePort, row.network, row.protocol, row.config).any { it.isNotBlank() } || row.packages.isNotEmpty())) {
            throw IllegalArgumentException("Rule ${row.id}: mixed custom URL conditions require review")
        }
        row.domains = convert(row.domains, source = false, domain = true)
        row.ip = convert(row.ip, source = false, domain = false)
        row.source = convert(row.source, source = true, domain = false)
        row.ruleSets = RouteRuleSet.encode(refs)
        return row
    }
}
