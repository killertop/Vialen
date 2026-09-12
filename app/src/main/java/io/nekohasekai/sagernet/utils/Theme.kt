package io.nekohasekai.sagernet.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R

object Theme {

    fun apply(context: Context) {
        // The app has one appearance, including when Android uses a dark UI.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int = R.style.Theme_SagerNet

    fun getDialogTheme(): Int = R.style.Theme_SagerNet_Dialog

}
