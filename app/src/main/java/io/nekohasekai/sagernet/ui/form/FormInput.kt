package io.nekohasekai.sagernet.ui.form

/** No fallback: a malformed or out-of-range value must leave the draft intact. */
fun validatedFormInt(text: String, range: IntRange): Int? =
    text.trim().toIntOrNull()?.takeIf { it in range }
