package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RemovedDashboardCleanup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RemovedDashboardCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun upgradeRemovesOnlyDashboardAndNeverFollowsLinks() {
        val root = temporary.newFolder("files").canonicalFile
        val keep = File(root, "remote-rule-sets").apply { mkdir() }
        File(keep, "rule.srs").writeText("keep")
        val dashboard = File(root, "yacd").apply { mkdir() }
        File(dashboard, "index.html").writeText("old")
        File(root, "yacd.version.txt").writeText("old")
        Files.createSymbolicLink(File(dashboard, "outside").toPath(), keep.toPath())
        RemovedDashboardCleanup.clean(root)
        RemovedDashboardCleanup.clean(root)
        assertFalse(dashboard.exists())
        assertFalse(File(root, "yacd.version.txt").exists())
        assertEquals("keep", File(keep, "rule.srs").readText())
    }
}
