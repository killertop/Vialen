package io.nekohasekai.sagernet.ui.form

import android.os.Bundle
import android.util.AtomicFile
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import moe.matsuri.nb4a.TempDatabase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Keep potentially large JSON out of Binder saved-state transactions; no database schema change. */
object FormDraftState {
    private var session: String? = null
    private fun file(token: String): AtomicFile {
        require(UUID.fromString(token).toString() == token)
        val directory = File(SagerNet.application.filesDir, "form-drafts").apply { mkdirs() }
        return AtomicFile(File(directory, token))
    }
    fun currentSession(): String? = session
    fun begin(): String = UUID.randomUUID().toString().also { session = it }
    fun newTextToken(): String = UUID.randomUUID().toString()

    private fun write(token: String, block: (DataOutputStream) -> Unit) {
        val target = file(token)
        val stream = target.startWrite()
        try {
            val output = DataOutputStream(stream)
            block(output)
            output.flush()
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }
    fun save(out: Bundle, token: String) {
        write(token) { output ->
            val rows = TempDatabase.profileCacheDao.all()
            output.writeInt(rows.size)
            rows.forEach { row ->
                output.writeUTF(row.key)
                output.writeInt(row.valueType)
                output.writeInt(row.value.size)
                output.write(row.value)
            }
        }
        out.putString("form.session", token)
    }
    fun restore(state: Bundle?): String? {
        val token = state?.getString("form.session") ?: return null
        if (session != token) {
            val rows = DataInputStream(file(token).openRead()).use { input ->
                List(input.readInt()) {
                    KeyValuePair(input.readUTF()).apply {
                        valueType = input.readInt()
                        value = ByteArray(input.readInt()).also { input.readFully(it) }
                    }
                }
            }
            TempDatabase.profileCacheDao.reset()
            rows.forEach { TempDatabase.profileCacheDao.put(it) }
            session = token
        }
        return token
    }
    fun saveText(token: String, text: String) = write(token) { it.write(text.toByteArray(Charsets.UTF_8)) }
    fun readText(token: String): String = file(token).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    fun discard(token: String) {
        file(token).delete()
        if (session == token) session = null
    }
}
