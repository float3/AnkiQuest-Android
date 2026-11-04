// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestLanguageTest : RobolectricTest() {
    @Test
    fun `Anki language overrides the phone language for widgets and server requests`() {
        AnkiDroidApp.sharedPrefs().edit { putString("language", "es-ES") }
        assertEquals("es-ES", AnkiquestLanguage.tag())
        assertEquals("esta semana", AnkiquestLanguage.context(targetContext).getString(R.string.ankiquest_period_week))
        val account = HomeAccount("https://quest.example", "cerro", "secret", AnkiquestLanguage.tag())
        assertEquals("es-ES", AnkiquestApi.request(account, "api/profile/cerro").build().header("Accept-Language"))
    }

    @Test
    fun `unsupported languages keep a usable English fallback`() {
        AnkiDroidApp.sharedPrefs().edit { putString("language", "it") }
        assertEquals("it", AnkiquestLanguage.tag())
        assertEquals("Profile picture", AnkiquestLanguage.context(targetContext).getString(R.string.ankiquest_avatar_title))
    }

    @Test
    fun `French German and Portuguese resources follow Anki language in widgets and daily decks`() {
        for (expected in listOf(
            listOf(
                "fr-FR",
                "Amis",
                "Terminé pour aujourd’hui",
                "État des notifications",
                "Quel classement ?",
                "Rappel de série",
                "Je m’en occupe !",
                "Aki est là pour t’aider. Une carte, un petit pas.",
                "Célébrations",
            ),
            listOf(
                "de-DE",
                "Freunde",
                "Für heute erledigt",
                "Benachrichtigungsstatus",
                "Welche Rangliste?",
                "Lernserien-Erinnerung",
                "Ich kümmere mich darum!",
                "Aki hilft dir. Eine Karte, ein kleiner Schritt.",
                "Erfolge feiern",
            ),
            listOf(
                "pt-PT",
                "Amigos",
                "Concluído por hoje",
                "Estado das notificações",
                "Qual classificação?",
                "Lembrete da sequência",
                "Já trato disso!",
                "Aki está aqui para te ajudar. Um cartão, um pequeno passo.",
                "Celebrações",
            ),
        )) {
            val (tag, friends, done, health, widget) = expected
            val reminder = expected[5]
            val reply = expected[6]
            val aki = expected[7]
            val celebrations = expected[8]
            AnkiDroidApp.sharedPrefs().edit { putString("language", tag) }
            val localized = AnkiquestLanguage.context(targetContext)
            assertEquals(friends, localized.getString(R.string.ankiquest_nav_friends))
            assertEquals(done, localized.getString(R.string.aq_deck_done))
            assertEquals(health, localized.getString(R.string.ankiquest_health_title))
            assertEquals(widget, localized.getString(R.string.ankiquest_widget_period_title))
            assertEquals(reminder, localized.getString(R.string.ankiquest_streak_hours_title))
            assertEquals(reply, localized.getString(R.string.ankiquest_reply_on_it))
            assertEquals(aki, localized.getString(R.string.aki_welcome))
            assertEquals(celebrations, localized.getString(R.string.ankiquest_celebrations_title))
        }
    }

    @Test
    fun `Spanish resources cover settings home navigation and widgets`() {
        val spanish =
            targetContext.createConfigurationContext(
                Configuration(targetContext.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag("es-ES"))) },
            )
        assertEquals("Clasificación", spanish.getString(R.string.ankiquest_dashboard_title))
        assertEquals("Foto de perfil", spanish.getString(R.string.ankiquest_avatar_title))
        assertEquals("Amigos", spanish.getString(R.string.ankiquest_nav_friends))
        assertEquals("Misiones diarias", spanish.getString(R.string.aq_home_quests))
        assertEquals("esta semana", spanish.getString(R.string.ankiquest_period_week))
        assertEquals("Notificaciones que recibo", spanish.getString(R.string.ankiquest_subscriptions_title))
    }
}
