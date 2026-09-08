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
        binding.brandReleases.setOnClickListener {
            requireContext().launchCustomTab("https://github.com/killertop/Vialen/releases")
        }
    }
}
