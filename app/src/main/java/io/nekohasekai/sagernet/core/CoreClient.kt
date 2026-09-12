package io.nekohasekai.sagernet.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/** Coarse business calls; Android services and database transactions stay in Kotlin. */
internal object CoreClient {
    private val gson = Gson()

    data class ImportRequest(
        @SerializedName("text") val text: String = "",
        @SerializedName("format") val format: String = "auto",
        @SerializedName("file_name") val fileName: String = "",
    )
    data class ImportIssue(
        @SerializedName("index") val index: Int = 0,
        @SerializedName("code") val code: String = "",
        @SerializedName("message") val message: String = "",
        @SerializedName("severity") val severity: String = "error",
    )
    data class ImportResult(
        @SerializedName("profiles") val profiles: List<Profile> = emptyList(),
        @SerializedName("issues") val issues: List<ImportIssue> = emptyList(),
        @SerializedName("format") val format: String = "",
    ) {
        /** Automatic updates must never silently replace a group with partial data. */
        fun requireComplete(): List<Profile> {
            val errors = issues.filter { it.severity != "warning" }
            require(errors.isEmpty()) {
                "Import rejected ${errors.size} entries: " + errors.take(5).joinToString { "${it.index + 1}: ${it.code}" }
            }
            require(profiles.isNotEmpty()) { "No supported profiles found" }
            return profiles
        }
    }

    private fun bytes(value: Any): ByteArray = gson.toJson(value).encodeToByteArray(throwOnInvalidSequence = true)

    fun importProfiles(text: String, format: String = "auto", fileName: String = ""): ImportResult =
        gson.fromJson(CoreTransport.execute("import", bytes(ImportRequest(text, format, fileName)))
            .decodeToString(throwOnInvalidSequence = true), ImportResult::class.java)

    fun parseURI(uri: String): Profile = importProfiles(uri, "links").requireComplete().single()

    fun exportURI(profile: Profile): String = gson.fromJson(
        CoreTransport.execute("export", bytes(profile)).decodeToString(throwOnInvalidSequence = true),
        JsonObject::class.java,
    ).get("uri").asString

    fun exportProfiles(profiles: List<Profile>): String = gson.toJson(mapOf("profiles" to profiles))

    fun validate(profile: Profile) = validateProfiles(listOf(profile))

    fun validateProfiles(profiles: List<Profile>) {
        CoreTransport.execute("validate", bytes(mapOf("profiles" to profiles)))
    }

    fun compile(request: JsonObject): JsonObject =
        gson.fromJson(CoreTransport.execute("compile", bytes(request)).decodeToString(throwOnInvalidSequence = true), JsonObject::class.java)
}
