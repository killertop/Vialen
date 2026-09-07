package io.nekohasekai.sagernet

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.delay
import org.junit.Assert.*

/** Track the owner as well as the system window; never accept an orphaned dialog. */
internal object VpnConsentTestUi {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    suspend fun launchMainResumed() {
        val app=instrumentation.targetContext.applicationContext as SagerNet
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        val launch=shell("am start -W -n ${app.packageName}/${MainActivity::class.java.name}")
        assertFalse("MainActivity shell launch failed: $launch", launch.contains("Error",ignoreCase=true))
        var mainResumed=false
        val resumeLimit=System.nanoTime()+5_000_000_000L
        while(!mainResumed && System.nanoTime()<resumeLimit) {
            instrumentation.runOnMainSync {
                mainResumed=ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is MainActivity && it.packageName==app.packageName && !it.isFinishing && !it.isDestroyed }
            }
            if(!mainResumed)delay(50)
        }
        assertTrue("MainActivity must be RESUMED before starting the consent flow; launch=$launch",mainResumed)
    }
    fun owners(): List<VpnRequestActivity> {
        var owners=emptyList<VpnRequestActivity>()
        instrumentation.runOnMainSync {
            owners=Stage.values().filter { it!=Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filterIsInstance<VpnRequestActivity>().distinct().filter { !it.isFinishing && !it.isDestroyed }
        }
        return owners
    }
    fun assertClean() {
        assertTrue("No consent Activity may carry over into this case", owners().isEmpty())
        assertNotEquals("No orphaned system consent window", "com.android.vpndialogs",
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
    }
    suspend fun awaitFreshDialog(): VpnRequestActivity {
        val deadline=System.nanoTime()+5_000_000_000L
        while(System.nanoTime()<deadline) {
            val owners=owners()
            if(owners.size==1 && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.vpndialogs") {
                println("VPN_DIALOG_OWNER identity=${System.identityHashCode(owners.single())} task=${owners.single().taskId}")
                return owners.single()
            }
            delay(50)
        }
        throw AssertionError("Expected one fresh VpnRequestActivity and its system consent dialog; owners=${owners().size} rootPackage=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}")
    }
    fun clickButton(id: String) {
        val root=instrumentation.uiAutomation.rootInActiveWindow
        assertEquals("com.android.vpndialogs",root?.packageName?.toString())
        val button=root!!.findAccessibilityNodeInfosByViewId(id).single()
        assertTrue("System consent button enabled",button.isEnabled)
        println("VPN_SYSTEM_BUTTON beforeClick=$id text=${button.text}")
        assertTrue("System consent button click delivered once",button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        println("VPN_SYSTEM_BUTTON clicked=$id")
    }
    suspend fun awaitDismissed(owner: VpnRequestActivity) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(System.nanoTime()<deadline && (owners().contains(owner) ||
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.vpndialogs")) delay(50)
        assertFalse("ActivityResult must finish this consent owner",owners().contains(owner))
        assertNotEquals("Consent dialog must close", "com.android.vpndialogs",
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        println("VPN_DIALOG_DISMISSED identity=${System.identityHashCode(owner)}")
    }
    suspend fun cleanup() {
        // Failure cleanup, after assertions: do not leave a system child without its result owner.
        val root=instrumentation.uiAutomation.rootInActiveWindow
        if(root?.packageName?.toString()=="com.android.vpndialogs") {
            root.findAccessibilityNodeInfosByViewId("android:id/button2").singleOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val deadline=System.nanoTime()+2_000_000_000L
            while(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.vpndialogs" && System.nanoTime()<deadline) delay(50)
        }
        val remaining=owners()
        instrumentation.runOnMainSync { remaining.forEach { it.finishAndRemoveTask() } }
    }
}
