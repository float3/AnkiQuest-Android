// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.Configuration
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.utils.LanguageUtil
import java.util.Locale

/** Use AnkiDroid's selected language even in services and launcher widgets. */
internal object AnkiquestLanguage {
    fun tag(): String = tag(AnkiDroidApp.sharedPrefs().getString("language", LanguageUtil.SYSTEM_LANGUAGE_TAG))

    /** The tag for a [stored] `language` preference, which is empty or missing to follow the phone. */
    fun tag(stored: String?): String = stored?.takeIf { it.isNotEmpty() } ?: LanguageUtil.getSystemLocale().toLanguageTag()

    fun locale(): Locale = Locale.forLanguageTag(tag().replace('_', '-'))

    fun context(context: Context): Context {
        val locale = locale()
        if (LanguageUtil.getLocaleCompat(context.resources) == locale) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
}
