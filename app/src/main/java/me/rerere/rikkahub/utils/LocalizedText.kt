package me.rerere.rikkahub.utils

import java.util.Locale

internal fun l10n(zh: String, en: String): String =
    if (Locale.getDefault().language.startsWith("zh")) zh else en
