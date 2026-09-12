package io.nekohasekai.sagernet

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/** A distinct sandbox cache key prevents another test's mocked DAO from being captured
 * by DataStore's static preference delegates before this fixture installs real Room. */
class ProfileSelectionRobolectricTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("io.nekohasekai.sagernet.core.")
            .doNotAcquireClass(ProfileSelectionRobolectricTestRunner::class.java.name)
            .build()
}
