package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override fun createEntity() = HysteriaBean().applyDefaultValues().apply { protocolVersion = 2 }

    override fun HysteriaBean.init() {
        DataStore.profileName = name
        DataStore.protocolVersion = 2
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverAuthType = authPayloadType
        DataStore.serverProtocolInt = protocol
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverStreamReceiveWindow = streamReceiveWindow
        DataStore.serverConnectionReceiveWindow = connectionReceiveWindow
        DataStore.serverDisableMtuDiscovery = disableMtuDiscovery
        DataStore.serverHopInterval = hopInterval
        DataStore.serverDisableChromeParrot = disableChromeParrot ?: false
        DataStore.serverBbrProfile = bbrProfile ?: ""
        DataStore.serverHopIntervalMax = hopIntervalMax ?: 0
        DataStore.serverObfsType = obfsType ?: "salamander"
        DataStore.serverObfsMinPacketSize = obfsMinPacketSize ?: 512
        DataStore.serverObfsMaxPacketSize = obfsMaxPacketSize ?: 1200
    }

    override fun HysteriaBean.serialize() {
        name = DataStore.profileName
        protocolVersion = 2
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        authPayloadType = DataStore.serverAuthType
        authPayload = DataStore.serverPassword
        protocol = DataStore.serverProtocolInt
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        streamReceiveWindow = DataStore.serverStreamReceiveWindow
        connectionReceiveWindow = DataStore.serverConnectionReceiveWindow
        disableMtuDiscovery = DataStore.serverDisableMtuDiscovery
        hopInterval = DataStore.serverHopInterval
        disableChromeParrot = DataStore.serverDisableChromeParrot
        bbrProfile = DataStore.serverBbrProfile
        hopIntervalMax = DataStore.serverHopIntervalMax
        obfsType = DataStore.serverObfsType
        obfsMinPacketSize = DataStore.serverObfsMinPacketSize
        obfsMaxPacketSize = DataStore.serverObfsMaxPacketSize
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.hysteria_preferences)

        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        val authPayload = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!
        authPayload.isVisible = authType.value != "${HysteriaBean.TYPE_NONE}"
        authType.setOnPreferenceChangeListener { _, newValue ->
            authPayload.isVisible = newValue != "${HysteriaBean.TYPE_NONE}"
            true
        }

        val alpn = findPreference<EditTextPreference>(Key.SERVER_ALPN)!!
        val advancedCategory = findPreference<androidx.preference.PreferenceCategory>("hysteria2AdvancedCategory")
        val hopIntervalMaxPref = findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL_MAX)
        val obfsTypePref = findPreference<SimpleMenuPreference>(Key.SERVER_OBFS_TYPE)
        val obfsMinSizePref = findPreference<EditTextPreference>(Key.SERVER_OBFS_MIN_PACKET_SIZE)
        val obfsMaxSizePref = findPreference<EditTextPreference>(Key.SERVER_OBFS_MAX_PACKET_SIZE)

        hopIntervalMaxPref?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        obfsMinSizePref?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        obfsMaxSizePref?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        fun updateObfsType(type: String) {
            val isGecko = type.equals("gecko", ignoreCase = true)
            obfsMinSizePref?.isVisible = isGecko
            obfsMaxSizePref?.isVisible = isGecko
        }

        obfsTypePref?.setOnPreferenceChangeListener { _, newValue ->
            updateObfsType(newValue.toString())
            true
        }
        updateObfsType(DataStore.serverObfsType)

        // The new profile model exposes Hysteria 2 only.
        findPreference<SimpleMenuPreference>(Key.PROTOCOL_VERSION)?.isVisible = false
        authPayload.isVisible = true
        authType.isVisible = false
        alpn.isVisible = false
        findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible = false
        findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible = false
        findPreference<SwitchPreference>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible = false
        advancedCategory?.isVisible = true
        authPayload.title = resources.getString(R.string.password)

        findPreference<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_DOWNLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_OBFS)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }

}
