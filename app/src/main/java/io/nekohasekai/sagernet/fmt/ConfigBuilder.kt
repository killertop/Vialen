package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs

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
    internal var tunMtu: Int? = null
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false): ConfigBuildResult {
    val snapshot = ConfigSnapshot.capture(proxy, forTest, forExport)
    val output = snapshot.generate()
    if (output.warnings.any { it.second == "DNS_RULE_NOT_PROJECTED" }) {
        // Informational compiler fallback, not a failed DNS request. Starting,
        // testing or exporting a config must not interrupt the user with a Toast.
        Logs.i("DNS_RULE_NOT_PROJECTED: routing conditions remain connection-only; configured DNS policy retained")
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
    return output.result.also { it.tunMtu = snapshot.tunMtu }
}
