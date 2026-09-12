package io.nekohasekai.sagernet

import android.content.Context
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.ClassRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.model.Statement

/** Physical-device functional suite with a per-test foreground host and a suite CPU wake lock.
 * Traffic tests drive internal consumer signals; this does not measure OS-background power behavior.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    SubscriptionEndToEndNativeTest::class,
    SubscriptionPersistenceNativeTest::class,
    FullConfigSnapshotNativeTest::class,
    ChainTagIntegrityNativeTest::class,
    ProfileAutoSelectionNativeTest::class,
    ListenerLifecycleNativeTest::class,
    CancellableUrlTestNativeTest::class,
    TrafficEfficiencyNativeTest::class,
)
class UpgradeBackgroundNativeSuite {
    companion object {
        @ClassRule @JvmField
        val cpuAwake: TestRule = object : TestRule {
            override fun apply(base: Statement, description: Description): Statement = object : Statement() {
                override fun evaluate() {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "${context.packageName}:upgrade-background-native-suite")
                    lock.setReferenceCounted(false)
                    lock.acquire(5 * 60_000L)
                    try {
                        base.evaluate()
                    } finally {
                        if (lock.isHeld) lock.release()
                    }
                }
            }
        }
    }
}
