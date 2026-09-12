package io.nekohasekai.sagernet

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import io.nekohasekai.sagernet.ui.PreferenceTypography
import org.junit.Assert.*
import kotlin.math.roundToInt

/** Same inflated-view assertions on the host and the physical phone; no DB or VPN writes. */
object TypographyContract {
    fun verify(context: Context) {
        fun inflate(layout: Int): View = LayoutInflater.from(context).inflate(layout, null, false)
        fun size(view: TextView, sp: Float) = assertEquals(
            "${view.resources.getResourceEntryName(view.id)} text size",
            // XML TextAppearance dimensions are rounded to whole pixels by TextView.
            // At the phone's 3.25 density, 14sp resolves to 46px, not 45.5px.
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, view.resources.displayMetrics).roundToInt().toFloat(),
            view.textSize, 0.05f
        )
        for ((layout, titleId, detailId) in listOf(
            Triple(R.layout.layout_profile, R.id.profile_name, R.id.profile_type),
            Triple(R.layout.layout_route_item, R.id.profile_name, R.id.profile_type),
            Triple(R.layout.layout_group_item, R.id.group_name, R.id.group_status),
            Triple(R.layout.layout_asset_item, R.id.asset_name, R.id.asset_status)
        )) {
            val root = inflate(layout)
            val title = root.findViewById<TextView>(titleId)
            size(title, 16f)
            assertEquals(Typeface.create("sans-serif-medium", Typeface.NORMAL), title.typeface)
            assertEquals(0f, title.letterSpacing, 0.001f)
            size(root.findViewById(detailId), 14f)
        }
        val about = inflate(R.layout.layout_about)
        for (id in listOf(R.id.brand_help, R.id.brand_releases, R.id.brand_feedback,
            R.id.brand_source, R.id.brand_licenses, R.id.brand_credits)) {
            val row = about.findViewById<TextView>(id)
            size(row, 16f)
            assertEquals(Typeface.create("sans-serif", Typeface.NORMAL), row.typeface)
            assertEquals(0f, row.letterSpacing, 0.001f)
            assertTrue("Navigation retains its 48dp target", row.minimumHeight >=
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, row.resources.displayMetrics).toInt())
        }
        // True actions retain Material's existing button role.
        size(about.findViewById(R.id.brand_version), 14f)
        val input = inflate(R.layout.layout_preference_input).findViewById<EditText>(android.R.id.edit)
        val originalInputType = input.inputType
        val originalMaxLines = input.maxLines
        val originalFilters = input.filters
        input.setText("QA Visual · 本地节点")
        input.setSelection(3)
        for (key in listOf("name", "profileName", "groupName", "routeName", "username", "serverUsername", "futureLabel")) {
            TextViewCompat.setTextAppearance(input, PreferenceTypography.inputAppearance(key))
            size(input, 16f)
            assertEquals("Natural text: $key", Typeface.create("sans-serif", Typeface.NORMAL), input.typeface)
        }
        for (key in listOf("privateKey", "peerPublicKey", "realityPubKey", "certificates", "serverAddress", "routeDomain")) {
            TextViewCompat.setTextAppearance(input, PreferenceTypography.inputAppearance(key))
            size(input, 16f)
            assertEquals("Technical text: $key", Typeface.MONOSPACE, input.typeface)
        }
        assertEquals(originalInputType, input.inputType)
        assertEquals(originalMaxLines, input.maxLines)
        assertArrayEquals(originalFilters, input.filters)
        assertEquals("QA Visual · 本地节点", input.text.toString())
        assertEquals(3, input.selectionStart)
        val password = inflate(R.layout.layout_password_dialog).findViewById<EditText>(android.R.id.edit)
        assertNotNull("Password masking must remain installed", password.transformationMethod)
        // Reusing a protocol row must reset both size and weight, not retain the previous role.
        val row = TextView(context)
        TextViewCompat.setTextAppearance(row, R.style.TextAppearance_Vialen_Section)
        assertEquals(Typeface.create("sans-serif-medium", Typeface.NORMAL), row.typeface)
        TextViewCompat.setTextAppearance(row, R.style.TextAppearance_Vialen_Body)
        assertEquals(Typeface.create("sans-serif", Typeface.NORMAL), row.typeface)
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, row.resources.displayMetrics), row.textSize, 0.05f)
    }
}
