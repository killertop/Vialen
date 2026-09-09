package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import androidx.activity.addCallback
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.editorkit.model.ColorScheme
import com.blacksquircle.ui.language.json.JsonLanguage
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutEditConfigBinding
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.form.FormDraftState
import io.nekohasekai.sagernet.ui.form.showFormError
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.ui.ExtendedKeyboard
import org.json.JSONObject

class ConfigEditActivity : ThemedActivity() {

    private lateinit var textToken: String
    private var resultSent = false
    private var parentSession: String? = null
    var dirty = false
    var key = Key.SERVER_CONFIG
    var useConfigStore = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as ConfigEditActivity).saveAndExit()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    lateinit var binding: LayoutEditConfigBinding

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        textToken = savedInstanceState?.getString("form.textToken") ?: FormDraftState.newTextToken()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { requestClose() }

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        if (!useConfigStore) {
            parentSession = savedInstanceState?.getString("form.parentSession") ?: FormDraftState.currentSession()
            // Restore the parent's cache before a recreated child commits into it. The parent
            // later sees the same live session, so its older snapshot cannot overwrite this result.
            if (savedInstanceState != null) parentSession?.let { token ->
                FormDraftState.restore(Bundle().apply { putString("form.session", token) })
            }
        }

        binding = LayoutEditConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.config_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.editor.apply {
            // Editorkit freezes its text by default, which can exceed Binder's state limit.
            isSaveEnabled = false
            colorScheme = editorColorScheme()
            language = JsonLanguage()
            setHorizontallyScrolling(true)
            if (savedInstanceState != null) {
                setTextContent(FormDraftState.readText(textToken))
            } else if (useConfigStore) {
                setTextContent(DataStore.configurationStore.getString(key) ?: "")
            } else {
                setTextContent(DataStore.profileCacheStore.getString(key) ?: "")
            }
            addTextChangedListener {
                if (!dirty) {
                    dirty = true
                }
            }
        }

        dirty = savedInstanceState?.getBoolean("form.dirty") ?: false

        binding.actionTab.setOnClickListener {
            try {
                binding.editor.insert(binding.editor.tab())
            } catch (e: Exception) {
            }
        }
        binding.actionUndo.setOnClickListener {
            try {
                binding.editor.undo()
            } catch (_: Exception) {
            }
        }
        binding.actionRedo.setOnClickListener {
            try {
                binding.editor.redo()
            } catch (_: Exception) {
            }
        }
        binding.actionFormat.setOnClickListener {
            formatText()?.let {
                binding.editor.setTextContent(it)
            }
        }

        val extendedKeyboard = findViewById<ExtendedKeyboard>(R.id.extended_keyboard)
        extendedKeyboard.setKeyListener { char ->
            try {
                binding.editor.insert(char)
            } catch (e: Exception) {
            }
        }
        extendedKeyboard.setHasFixedSize(true)
        extendedKeyboard.submitList("{},:_\"".map { it.toString() })
        extendedKeyboard.setBackgroundColor(getColour(R.color.vialen_surface))

        val keyboardContainer = findViewById<LinearLayout>(R.id.keyboard_container)
        ViewCompat.setOnApplyWindowInsetsListener(keyboardContainer) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                // systemBar insets are applied to the bottom of the keyboard
                if (imeVisible) {
                    bottomMargin = imeInsets.bottom - systemBarInsets.bottom
                } else {
                    bottomMargin = 0
                }
            }

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    private fun editorColorScheme(): ColorScheme {
        val primary = getColour(R.color.vialen_text_primary)
        val secondary = getColour(R.color.vialen_text_secondary)
        val surface = getColour(R.color.vialen_surface)
        val outline = getColour(R.color.vialen_outline)
        val selected = getColour(R.color.vialen_selected_background)
        val accent = getColour(R.color.vialen_accent)
        val success = getColour(R.color.vialen_success)
        return ColorScheme(
            textColor = primary,
            cursorColor = accent,
            backgroundColor = surface,
            gutterColor = surface,
            gutterDividerColor = outline,
            gutterCurrentLineNumberColor = primary,
            gutterTextColor = secondary,
            selectedLineColor = selected,
            selectionColor = selected,
            suggestionQueryColor = accent,
            findResultBackgroundColor = selected,
            delimiterBackgroundColor = selected,
            numberColor = accent,
            operatorColor = secondary,
            keywordColor = accent,
            typeColor = accent,
            langConstColor = accent,
            preprocessorColor = secondary,
            variableColor = primary,
            methodColor = accent,
            stringColor = success,
            commentColor = secondary,
            tagColor = secondary,
            tagNameColor = accent,
            attrNameColor = primary,
            attrValueColor = success,
            entityRefColor = accent,
        )
    }

    fun formatText(): String? {
        try {
            val txt = binding.editor.text.toString()
            if (txt.isBlank()) {
                return ""
            }
            return JSONObject(txt).toStringPretty()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
                .setMessage(e.readableMessage).show()
            return null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        FormDraftState.saveText(textToken, binding.editor.text.toString())
        outState.putString("form.textToken", textToken)
        outState.putString("form.parentSession", parentSession)
        outState.putInt("form.selection", binding.editor.selectionStart)
        outState.putBoolean("form.dirty", dirty)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        dirty = savedInstanceState.getBoolean("form.dirty")
        binding.editor.setSelection(savedInstanceState.getInt("form.selection").coerceIn(0, binding.editor.text.length))
    }

    fun saveAndExit() {
        val formatted = formatText() ?: return
        try {
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, formatted)
            } else {
                DataStore.profileCacheStore.putString(key, formatted)
                DataStore.dirty = true
            }
            if (intent.getBooleanExtra("form.returnResult", false)) {
                FormDraftState.saveText(textToken, formatted)
                setResult(RESULT_OK, android.content.Intent().putExtra("form.result", textToken))
                resultSent = true
            } else setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            showFormError(e)
        }
    }

    override fun onDestroy() {
        if (isFinishing && !resultSent) FormDraftState.discard(textToken)
        super.onDestroy()
    }

    private fun requestClose() {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        if (dirty) {
            // Synchronous attachment plus a stable tag also handles two queued Back events.
            if (supportFragmentManager.findFragmentByTag("form.unsaved") == null) {
                UnsavedChangesDialogFragment().apply { key() }
                    .showNow(supportFragmentManager, "form.unsaved")
            }
        } else finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        requestClose()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                saveAndExit()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
