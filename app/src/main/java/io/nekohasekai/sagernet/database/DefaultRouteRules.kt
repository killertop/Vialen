package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R

/** Ordered product defaults. Existing user rules are never identified by their display names. */
internal object DefaultRouteRules {
    const val GOOGLE_SOURCE = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/google.srs"

    fun google(name: String, direction: String = "destination") =
        RouteRuleSet(name, GOOGLE_SOURCE, match = direction).validate()

    fun create(text: (Int) -> String): List<RuleEntity> = listOf(
        RuleEntity(
            name = text(R.string.route_default_ads), enabled = true, outbound = -2,
            ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official(
                "geosite", "category-ads-all", text(R.string.route_set_ads))))),
        RuleEntity(
            name = text(R.string.route_default_google), enabled = true,
            ruleSets = RouteRuleSet.encode(listOf(google(text(R.string.route_set_google))))),
        RuleEntity(
            name = text(R.string.route_default_cn_domains), enabled = true, outbound = -1,
            ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official(
                "geosite", "cn", text(R.string.route_set_cn_domain))))),
        RuleEntity(
            name = text(R.string.route_default_cn_ip), enabled = true, outbound = -1,
            ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official(
                "geoip", "cn", text(R.string.route_set_cn_ip)))))
    ).onEachIndexed { index, rule -> rule.userOrder = index + 1L }
}
