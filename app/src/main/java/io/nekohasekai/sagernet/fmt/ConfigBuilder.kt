package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProxyEntity

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "selected"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "direct"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false): ConfigBuildResult {
    val snapshot = ConfigSnapshot.capture(proxy, forTest, forExport)
    val output = snapshot.generate()
    if (output.warnings.any { it.second == "DNS_RULE_NOT_PROJECTED" }) {
        Toast.makeText(SagerNet.application, R.string.route_dns_not_projected, Toast.LENGTH_LONG).show()
    }
    for ((id, code) in output.warnings) {
        val name = snapshot.ruleNames[id].orEmpty()
        val message = when (code) {
            "PACKAGE_REQUIRES_VPN" -> SagerNet.application.getString(R.string.route_need_vpn, name)
            "MISSING_RULE_OUTBOUND" -> "Warning: $name: A non-existent outbound was specified."
            else -> continue
        }
        Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
    }
    return output.result
}
