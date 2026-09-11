package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.widget.ListListener

class AboutFragment : ToolbarFragment(R.layout.layout_about) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_about)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        val binding = LayoutAboutBinding.bind(view)
        binding.brandVersion.text = getString(R.string.version_x, SagerNet.appVersionNameForDisplay)
        binding.brandVersion.setOnClickListener {
            val copied = SagerNet.trySetPrimaryClip("Vialen ${SagerNet.appVersionNameForDisplay}")
            (activity as? MainActivity)?.snackbar(if (copied) R.string.copy_toast_msg else R.string.action_export_err)?.show()
        }
        binding.brandHelp.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_help).setMessage(R.string.ui_help_body)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        binding.brandFeedback.setOnClickListener { requireContext().launchCustomTab("https://github.com/killertop/Vialen/issues") }
        binding.brandSource.setOnClickListener { requireContext().launchCustomTab("https://github.com/killertop/Vialen") }
        binding.brandLicenses.setOnClickListener { requireContext().launchCustomTab("https://github.com/killertop/Vialen/blob/main/LICENSE") }
        binding.brandCredits.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_credits).setMessage(R.string.ui_credits_body)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        binding.brandReleases.setOnClickListener {
            requireContext().launchCustomTab("https://github.com/killertop/Vialen/releases")
        }
    }
}
