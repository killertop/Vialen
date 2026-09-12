package io.nekohasekai.sagernet.database

import android.content.Context
import android.content.Intent
import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.profile.*
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity

@Entity(
    tableName = "proxy_entities", indices = [Index("groupId", name = "groupId")]
)
class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
    var userOrder: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
    var status: Int = 0,
    var ping: Int = 0,
    var sourceKey: String = "",
    var error: String? = null,
    document: String = "",
) : Serializable() {

    @Ignore private var projection: AbstractBean? = null

    /** Room reads this getter at every write, including edits to requireBean(). */
    var document: String = document
        get() {
            projection?.let { field = ProfileDocument.encode(documentFromBean(it, field)) }
            return field
        }
        set(value) { field = value; projection = null }

    @get:Ignore val socksBean get() = requireBean() as? SOCKSBean
    @get:Ignore val httpBean get() = requireBean() as? HttpBean
    @get:Ignore val ssBean get() = requireBean() as? ShadowsocksBean
    @get:Ignore val vmessBean get() = requireBean() as? VMessBean
    @get:Ignore val trojanBean get() = requireBean() as? TrojanBean
    @get:Ignore val hysteriaBean get() = requireBean() as? HysteriaBean
    @get:Ignore val tuicBean get() = requireBean() as? TuicBean
    @get:Ignore val wgBean get() = requireBean() as? WireGuardBean
    @get:Ignore val anyTLSBean get() = requireBean() as? AnyTLSBean
    @get:Ignore val chainBean get() = requireBean() as? ChainBean
    @get:Ignore val configBean get() = requireBean() as? ConfigBean

    fun putProfile(profile: io.nekohasekai.sagernet.core.Profile): ProxyEntity {
        val identified = if (profile.id.isBlank()) profile.copy(id = java.util.UUID.randomUUID().toString()) else profile
        type = ProfileDocument.profileType(identified)
        document = ProfileDocument.encode(ProfileDocument(kind = "node", profile = identified))
        return this
    }

    fun requireProfile(): io.nekohasekai.sagernet.core.Profile =
        requireNotNull(ProfileDocument.decode(document).profile) { "Document is not a node" }

    private fun documentFromBean(bean: AbstractBean, originalJson: String): ProfileDocument = when (bean) {
        is ChainBean -> ProfileDocument(kind = "chain", name = bean.name.orEmpty(), hops = bean.proxies.toList())
        is ConfigBean -> ProfileDocument(kind = "raw_config", name = bean.name.orEmpty(), content = bean.config.orEmpty(), scope = if (bean.type == 1) "outbound" else "config")
        else -> {
            val original = originalJson.takeIf { it.isNotEmpty() }?.let(ProfileDocument::decode)?.profile
            val profile = if (original != null) ProfileAdapter.fromBean(bean, original) else ProfileAdapter.fromBean(bean, java.util.UUID.randomUUID().toString())
            ProfileDocument(kind = "node", profile = profile)
        }
    }

    fun copy(id: Long = this.id, groupId: Long = this.groupId, type: Int = this.type,
             userOrder: Long = this.userOrder, tx: Long = this.tx, rx: Long = this.rx,
             status: Int = this.status, ping: Int = this.ping, sourceKey: String = this.sourceKey,
             error: String? = this.error, document: String = this.document): ProxyEntity =
        ProxyEntity(id, groupId, type, userOrder, tx, rx, status, ping, sourceKey, error, document).also { it.dirty = dirty }

    companion object {
        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_VMESS = 4
        const val TYPE_TROJAN = 6

        const val TYPE_WG = 18

        const val TYPE_HYSTERIA = 15
        const val TYPE_SHADOWTLS = 19
        const val TYPE_TUIC = 20
        const val TYPE_ANYTLS = 22

        const val TYPE_CONFIG = 998

        const val TYPE_CHAIN = 8

        val chainName by lazy { app.getString(R.string.proxy_chain) }

        @JvmField
        val CREATOR = object : CREATOR<ProxyEntity>() {

            override fun newInstance(): ProxyEntity {
                return ProxyEntity()
            }

            override fun newArray(size: Int): Array<ProxyEntity?> {
                return arrayOfNulls(size)
            }
        }
    }

    @Ignore
    @Transient
    var dirty: Boolean = false

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)

        output.writeLong(id)
        output.writeLong(groupId)
        output.writeInt(type)
        output.writeLong(userOrder)
        output.writeLong(tx)
        output.writeLong(rx)
        output.writeInt(status)
        output.writeInt(ping)
        output.writeString(sourceKey)
        output.writeString(error)

        val data = document.toByteArray(Charsets.UTF_8)
        output.writeVarInt(data.size, true)
        output.writeBytes(data)

        output.writeBoolean(dirty)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        require(input.readInt() == 0) { "Unsupported parcel version" }

        id = input.readLong()
        groupId = input.readLong()
        type = input.readInt()
        userOrder = input.readLong()
        tx = input.readLong()
        rx = input.readLong()
        status = input.readInt()
        ping = input.readInt()
        sourceKey = input.readString()
        error = input.readString()
        putByteArray(input.readBytes(input.readVarInt(true)))

        dirty = input.readBoolean()
    }


    fun putByteArray(byteArray: ByteArray) {
        val decoded = ProfileDocument.decode(byteArray.toString(Charsets.UTF_8))
        type = decoded.entityType()
        document = ProfileDocument.encode(decoded)
    }

    fun displayType(): String = when (type) {
        TYPE_SOCKS -> socksBean!!.protocolName()
        TYPE_HTTP -> if (httpBean!!.isTLS()) "HTTPS" else "HTTP"
        TYPE_SS -> "Shadowsocks"
        TYPE_VMESS -> if (vmessBean!!.isVLESS) "VLESS" else "VMess"
        TYPE_TROJAN -> "Trojan"
        TYPE_HYSTERIA -> "Hysteria" + hysteriaBean!!.protocolVersion
        TYPE_WG -> "WireGuard"
        TYPE_TUIC -> "TUIC"
        TYPE_SHADOWTLS -> "ShadowTLS"
        TYPE_ANYTLS -> "AnyTLS"
        TYPE_CHAIN -> chainName
        TYPE_CONFIG -> configBean!!.displayType()
        else -> "Undefined type $type"
    }

    fun displayName() = requireBean().displayName()
    fun displayAddress() = requireBean().displayAddress()

    fun requireBean(): AbstractBean {
        projection?.let { return it }
        val decoded = ProfileDocument.decode(document)
        return decoded.toBean().also { projection = it }
    }

    fun haveLink(): Boolean {
        return when (type) {
            TYPE_CHAIN -> false
            else -> true
        }
    }

    fun haveStandardLink(): Boolean {
        return when (requireBean()) {
            is WireGuardBean -> false
            is ShadowTLSBean -> false
            is ConfigBean -> false
            else -> true
        }
    }

    fun toStdLink(compact: Boolean = false): String =
        io.nekohasekai.sagernet.core.CoreClient.exportURI(requireProfile())

    fun toProfileJson(): String = when (type) {
        TYPE_CONFIG -> configBean!!.config
        else -> io.nekohasekai.sagernet.core.CoreClient.exportProfiles(listOf(requireProfile()))
    }

    fun exportConfig(): Pair<String, String> {
        val name = "${requireBean().displayName()}.json"
        val config = buildConfig(this@ProxyEntity, forExport = true)
        return config.config to name
    }

    fun singMux(): MultiplexOptions? {
        return when (type) {
            TYPE_VMESS -> MultiplexOptions().apply {
                enabled = vmessBean!!.enableMux
                padding = vmessBean!!.muxPadding
                max_streams = vmessBean!!.muxConcurrency
                protocol = when (vmessBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
            }

            TYPE_TROJAN -> MultiplexOptions().apply {
                enabled = trojanBean!!.enableMux
                padding = trojanBean!!.muxPadding
                max_streams = trojanBean!!.muxConcurrency
                protocol = when (trojanBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
            }

            else -> null
        }
    }

    fun putBean(bean: AbstractBean): ProxyEntity {
        val updated = documentFromBean(bean, document)
        type = updated.entityType()
        document = ProfileDocument.encode(updated)
        projection = bean
        return this
    }

    fun settingIntent(ctx: Context, isSubscription: Boolean): Intent {
        return Intent(
            ctx, when (type) {
                TYPE_SOCKS -> SocksSettingsActivity::class.java
                TYPE_HTTP -> HttpSettingsActivity::class.java
                TYPE_SS -> ShadowsocksSettingsActivity::class.java
                TYPE_VMESS -> VMessSettingsActivity::class.java
                TYPE_TROJAN -> TrojanSettingsActivity::class.java
                TYPE_HYSTERIA -> HysteriaSettingsActivity::class.java
                TYPE_WG -> WireGuardSettingsActivity::class.java
                TYPE_TUIC -> TuicSettingsActivity::class.java
                TYPE_SHADOWTLS -> ShadowTLSSettingsActivity::class.java
                TYPE_ANYTLS -> AnyTLSSettingsActivity::class.java
                TYPE_CHAIN -> ChainSettingsActivity::class.java
                TYPE_CONFIG -> ConfigSettingActivity::class.java
                else -> throw IllegalArgumentException()
            }
        ).apply {
            putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
            putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT EXISTS(SELECT 1 FROM proxy_entities)")
        fun hasProfiles(): Boolean

        @Query("SELECT EXISTS(SELECT 1 FROM proxy_entities WHERE id NOT IN (:excludedIds))")
        fun hasProfilesExcluding(excludedIds: List<Long>): Boolean

        @Query("select * from proxy_entities")
        fun getAll(): List<ProxyEntity>

        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getIdsByGroup(groupId: Long): List<Long>

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE id in (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM proxy_entities WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT  MAX(userOrder) + 1 FROM proxy_entities WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Query("SELECT * FROM proxy_entities WHERE id = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyId)")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM proxy_entities WHERE groupId in (:groupId)")
        fun deleteByGroup(groupId: LongArray)

        @Delete
        fun deleteProxy(proxy: ProxyEntity): Int

        @Delete
        fun deleteProxy(proxies: List<ProxyEntity>): Int

        @Query("UPDATE proxy_entities SET userOrder = :order WHERE id = :id")
        fun updateOrder(id: Long, order: Long): Int

        @Query("UPDATE proxy_entities SET tx = tx + :tx, rx = rx + :rx WHERE id = :id")
        fun addTraffic(id: Long, tx: Long, rx: Long): Int

        @Query("SELECT id, tx, rx FROM proxy_entities WHERE id = :id")
        fun getTraffic(id: Long): io.nekohasekai.sagernet.aidl.TrafficData?

        @Query("UPDATE proxy_entities SET tx = 0, rx = 0 WHERE id IN (:ids)")
        fun clearTraffic(ids: List<Long>)

        @Update
        fun updateProxy(proxy: ProxyEntity): Int

        @Query("UPDATE proxy_entities SET status = :status, ping = :ping, error = :error WHERE id = :id")
        fun updateConnectionTestResult(id: Long, status: Int, ping: Int, error: String?): Int

        @Transaction
        fun updateConnectionTestResults(results: List<ConnectionTestResult>) {
            results.forEach { result ->
                updateConnectionTestResult(result.id, result.status, result.ping, result.error)
            }
        }

        @Update
        fun updateProxy(proxies: List<ProxyEntity>): Int

        @Insert
        fun addProxy(proxy: ProxyEntity): Long

        @Insert
        fun insert(proxies: List<ProxyEntity>)

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteAll(groupId: Long): Int

        @Query("DELETE FROM proxy_entities")
        fun reset()

    }

    override fun describeContents(): Int {
        return 0
    }
}
