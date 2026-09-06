package io.nekohasekai.sagernet

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual system consent activity and existing ActivityResult contract, not appops auto-approval. */
@RunWith(AndroidJUnit4::class)
class VpnFirstConsentNativeTest {
    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()

    @Test fun firstSystemApprovalResumesOnceAndConnects() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as SagerNet
        fun shell(cmd:String)=ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(cmd)
        ).bufferedReader().use { it.readText() }
        val oldMode=DataStore.serviceMode;val oldProxy=DataStore.selectedProxy
        val oldDirect=DataStore.directDns;val oldRemote=DataStore.remoteDns
        val oldOp=shell("cmd appops get ${app.packageName} ACTIVATE_VPN")
            .substringAfter("ACTIVATE_VPN: ","default").substringBefore(';').trim()
        check(!DataStore.serviceState.started)
        val db=SagerDatabase.instance
        val group=ProxyGroup(name="first-consent-${System.nanoTime()}")
        group.id=db.groupDao().createGroup(group)
        val connection=SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        profileState.preservingFailure({
            VpnConsentTestUi.assertClean()
            val node=ProxyEntity(groupId=group.id).apply {
                putBean(SOCKSBean().applyDefaultValues().apply {name="consent";serverAddress="127.0.0.1";serverPort=1080})
                id=db.proxyDao().addProxy(this)
            }
            DataStore.serviceMode=Key.MODE_VPN;DataStore.selectedProxy=node.id
            DataStore.directDns="local";DataStore.remoteDns="local"
            shell("cmd appops set ${app.packageName} ACTIVATE_VPN default")
            assertNotNull("Consent must not exist before the system dialog",VpnService.prepare(app))
            app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            connection.connect(app,object:SagerConnection.Callback {
                override fun stateChanged(state:BaseService.State,profileName:String?,msg:String?) {}
                override fun onServiceConnected(service:ISagerNetService) {}
            })
            val bindLimit=System.nanoTime()+5_000_000_000L
            while(connection.service==null && System.nanoTime()<bindLimit)delay(50)
            assertNotNull(connection.service)
            SagerNet.startService()
            val owner=VpnConsentTestUi.awaitFreshDialog()
            val before=shell("dumpsys activity services ${app.packageName}")
            assertFalse(before.contains("startRequested=true") || before.contains("fgRequired=true"))
            VpnConsentTestUi.clickButton("android:id/button1")
            VpnConsentTestUi.awaitDismissed(owner)
            val connectedLimit=System.nanoTime()+10_000_000_000L
            while(connection.service?.state!=BaseService.State.Connected.ordinal && System.nanoTime()<connectedLimit)delay(50)
            println("FIRST_CONSENT_AFTER_APPROVAL\n"+shell("dumpsys activity services ${app.packageName}"))
            assertNull("System activity granted consent",VpnService.prepare(app))
            assertEquals(BaseService.State.Connected.ordinal,connection.service!!.state)
            delay(1_000)
            val state=shell("dumpsys activity services ${app.packageName}")
            println("FIRST_CONSENT_SERVICE_DUMP\n$state")
            assertTrue("Exactly one start request on this fresh bound service",state.contains("lastStartId=1"))
            assertFalse(state.contains("lastStartId=2"))
            println("FIRST_CONSENT granted_by_system_ui=true lastStartId=1 connected=true")
        }, {
            profileState.cleanupSteps({
                VpnConsentTestUi.cleanup()
            }, {
                profileState.stopAndAwait(connection)
            }, {
                connection.disconnect(app)
            }, {
                DataStore.serviceMode=oldMode;DataStore.selectedProxy=oldProxy
                DataStore.directDns=oldDirect;DataStore.remoteDns=oldRemote
                shell("cmd appops set ${app.packageName} ACTIVATE_VPN $oldOp")
                db.runInTransaction { db.proxyDao().deleteByGroup(group.id);db.groupDao().deleteById(group.id) }
            })
        })
        assertNull(db.groupDao().getById(group.id))
    }
}
