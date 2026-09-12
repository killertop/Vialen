package io.nekohasekai.sagernet.database

import java.io.File

/** Delete only the obsolete app-owned dashboard, without following symlinks. */
object RemovedDashboardCleanup {
    fun clean(filesDir: File) {
        val root = filesDir.canonicalFile
        fun remove(file: File) {
            if (file.canonicalFile != file.absoluteFile) {
                check(file.delete() || !file.exists()) { "Cannot remove dashboard link" }
                return
            }
            file.listFiles()?.forEach { remove(it) }
            check(file.delete() || !file.exists()) { "Cannot remove obsolete dashboard" }
        }
        remove(File(root, "yacd"))
        remove(File(root, "yacd.version.txt"))
    }
}
