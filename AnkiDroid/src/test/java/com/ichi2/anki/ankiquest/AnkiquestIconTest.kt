// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestIcon.Mood
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestIconTest : RobolectricTest() {
    private val hour = 3_600_000L
    private val now = 100 * hour

    private fun profile(
        state: String,
        streak: Int,
        hoursLeft: Long,
    ) = JSONObject()
        .put("streak_state", state)
        .put("streak", streak)
        .put("day_ends_at", now + hoursLeft * hour)

    @Test
    fun `the mood follows today's streak`() {
        assertEquals(Mood.HAPPY, AnkiquestIcon.mood(profile("studied", 12, 10), now))
        assertEquals(Mood.FROZEN, AnkiquestIcon.mood(profile("protected", 12, 10), now))
        assertEquals(Mood.DEFAULT, AnkiquestIcon.mood(profile("pending", 12, 10), now))
        assertEquals(Mood.WORRIED, AnkiquestIcon.mood(profile("pending", 12, 3), now))
        assertEquals(Mood.SLEEPY, AnkiquestIcon.mood(profile("pending", 0, 3), now))
        assertEquals(Mood.DEFAULT, AnkiquestIcon.mood(profile("studied", 12, -1), now), "an ended day says nothing about today")
        assertEquals(Mood.DEFAULT, AnkiquestIcon.mood(null, now))
    }

    @Test
    fun `builds without the quest aliases keep their launcher entry`() {
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        assertFalse(AnkiquestIcon.available(targetContext))
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(Mood.HAPPY))
    }

    @Test
    fun `the mood icon is on unless turned off`() {
        val prefs = targetContext.sharedPrefs()
        prefs.edit { remove(AnkiquestIcon.PREF_ENABLED) }
        assertTrue(AnkiquestIcon.enabled(prefs))
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }
        assertFalse(AnkiquestIcon.enabled(prefs))
    }

    @Test
    fun `builds without the quest aliases ignore the default at start`() {
        val prefs = targetContext.sharedPrefs()
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        AnkiquestIcon.onAppStart(targetContext, prefs)
        AnkiquestIcon.showLastKnown(targetContext, now, prefs)
        for (mood in Mood.entries) assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(mood), mood.name)
    }

    @Test
    fun `turning the icon on shows the last known day at once`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        assertEquals(listOf(Mood.DEFAULT), enabled(), "off shows the plain icon")
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        AnkiquestIcon.showLastKnown(targetContext, now, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled(), "the profile seen while off is used without another poll")
    }

    @Test
    fun `the app start catches up with the last known day`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        prefs.edit { remove(AnkiquestIcon.PREF_ENABLED) }
        assertNull(AnkiquestIcon.lastKnown(prefs))
        AnkiquestIcon.onAppStart(targetContext, prefs)
        assertEquals(listOf(Mood.DEFAULT), enabled(), "nothing known yet keeps the plain icon")
        val clock = TimeManager.time.intTimeMS()
        val protected = JSONObject().put("streak_state", "protected").put("streak", 12).put("day_ends_at", clock + 10 * hour)
        AnkiquestIcon.update(targetContext, protected, clock, prefs)
        AnkiquestIcon.show(targetContext, Mood.DEFAULT, prefs)
        AnkiquestIcon.onAppStart(targetContext, prefs)
        assertEquals(listOf(Mood.FROZEN), enabled())
    }

    @Test
    fun `exactly one launcher icon is enabled`() {
        registerAliases()
        assertTrue(AnkiquestIcon.available(targetContext))
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled())
        AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
        assertEquals(listOf(Mood.WORRIED), enabled())
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }
        AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
        assertEquals(listOf(Mood.DEFAULT), enabled())
    }

    @Test
    fun `a worried icon turns happy once today counts`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
        assertEquals(listOf(Mood.WORRIED), enabled())
        AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled())
        assertEquals(Mood.HAPPY, AnkiquestIcon.current(prefs))
    }

    @Test
    fun `the launcher mood waits for the app to stop`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(now))
        AnkiquestIcon.show(targetContext, Mood.DEFAULT, prefs)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val original = Mood.entries.map(::state)

        lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
            assertEquals(original, Mood.entries.map(::state), "the reviewer still owns a visible task")

            lifecycle.currentState = Lifecycle.State.STARTED
            AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
            assertEquals(original, Mood.entries.map(::state), "a paused activity can still be visible")

            lifecycle.currentState = Lifecycle.State.CREATED
            assertEquals(Mood.HAPPY, switchedFrom(prefs, Mood.DEFAULT))
            assertEquals(listOf(Mood.HAPPY), enabled(), "apply the latest mood when the app stops")
        } finally {
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    @Test
    fun `a queued mood is resolved again after the study day ends`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        val clock = MockTime(now)
        TimeManager.resetWith(clock)
        AnkiquestIcon.show(targetContext, Mood.WORRIED, prefs)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
            clock.addM(121)

            lifecycle.currentState = Lifecycle.State.CREATED

            assertEquals(Mood.DEFAULT, switchedFrom(prefs, Mood.WORRIED))
            assertEquals(listOf(Mood.DEFAULT), enabled())
        } finally {
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    @Test
    fun `turning the icon off while a mood is queued restores the default`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(now))
        AnkiquestIcon.show(targetContext, Mood.WORRIED, prefs)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
            prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }

            lifecycle.currentState = Lifecycle.State.CREATED

            assertEquals(Mood.DEFAULT, switchedFrom(prefs, Mood.WORRIED))
            assertEquals(listOf(Mood.DEFAULT), enabled())
        } finally {
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    @Test
    fun `a queued change uses the account selected when the app stops`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(now))
        selectAccount(prefs, "second")
        AnkiquestIcon.update(targetContext, profile("protected", 12, 2), now, prefs)
        AnkiquestIcon.show(targetContext, Mood.DEFAULT, prefs)
        selectAccount(prefs, "first")
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
            selectAccount(prefs, "second")

            lifecycle.currentState = Lifecycle.State.CREATED

            assertEquals(Mood.FROZEN, switchedFrom(prefs, Mood.DEFAULT))
            assertEquals(listOf(Mood.FROZEN), enabled())
        } finally {
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    @Test
    fun `resuming does not wait for a slow launcher update`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(now))
        AnkiquestIcon.show(targetContext, Mood.DEFAULT, prefs)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val context =
            object : ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this

                override fun getPackageManager(): PackageManager {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return targetContext.packageManager
                }
            }
        val owner = ProcessLifecycleOwner.get()
        AnkiquestIcon.onStart(owner)
        AnkiquestIcon.update(context, profile("studied", 13, 2), now, prefs)
        AnkiquestIcon.onStop(owner)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the background change reached PackageManager")
            // A slow binder call must not hold the lock needed by the main-thread lifecycle callback.
            CompletableFuture.runAsync { AnkiquestIcon.onStart(owner) }.get(1, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            switchedFrom(prefs, Mood.DEFAULT)
            AnkiquestIcon.onStop(owner)
        }
    }

    @Test
    fun `a quick return requeues the launcher change until the next stop`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(now))
        AnkiquestIcon.show(targetContext, Mood.DEFAULT, prefs)
        val owner = ProcessLifecycleOwner.get()
        AnkiquestIcon.onStart(owner)
        AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
        val visibility = ReflectionHelpers.getField<Any>(AnkiquestIcon, "visibility")
        val pending = ReflectionHelpers.getField<Any>(visibility, "pending")
        AnkiquestIcon.onStart(owner)

        ReflectionHelpers.callInstanceMethod<Unit>(AnkiquestIcon, "applyPending", ClassParameter.from(pending.javaClass, pending))

        assertEquals(listOf(Mood.DEFAULT), enabled(), "the resumed task leaves the aliases alone")
        AnkiquestIcon.onStop(owner)
        assertEquals(Mood.HAPPY, switchedFrom(prefs, Mood.DEFAULT))
        assertEquals(listOf(Mood.HAPPY), enabled())
    }

    private fun selectAccount(
        prefs: SharedPreferences,
        player: String,
    ) {
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, player)
            putString(Ankiquest.PREF_TOKEN, "test-$player")
        }
    }

    private fun switchedFrom(
        prefs: SharedPreferences,
        previous: Mood,
    ): Mood =
        runBlocking {
            withTimeout(5000) {
                while (AnkiquestIcon.current(prefs) == previous) {
                    delay(10)
                }
                assertNotNull(AnkiquestIcon.current(prefs))
            }
        }

    @Test
    fun `the launcher's components win over what the pref says`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        AnkiquestIcon.show(targetContext, Mood.HAPPY, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled())
        // A switch the system undid or that stopped halfway: the plain icon is back and happy is off.
        val packageManager = targetContext.packageManager
        packageManager.setComponentEnabledSetting(component(Mood.HAPPY), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        packageManager.setComponentEnabledSetting(component(Mood.DEFAULT), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        assertEquals(Mood.HAPPY, AnkiquestIcon.current(prefs), "the pref still says happy")

        AnkiquestIcon.show(targetContext, Mood.HAPPY, prefs)

        assertEquals(listOf(Mood.HAPPY), enabled())
    }

    @Test
    fun `a matching icon is left alone`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        AnkiquestIcon.show(targetContext, Mood.FROZEN, prefs)
        val packageManager = targetContext.packageManager
        // Equivalent to the manifest's own state, so nothing needs to change for the launcher.
        packageManager.setComponentEnabledSetting(component(Mood.HAPPY), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)

        AnkiquestIcon.show(targetContext, Mood.FROZEN, prefs)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(Mood.HAPPY), "an alias that is already off is not touched")
        assertEquals(listOf(Mood.FROZEN), enabled())
    }

    /** The aliases come from the quest build's manifest, which unit tests do not merge. */
    private fun registerAliases() {
        for (mood in Mood.entries) {
            shadowOf(targetContext.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = targetContext.packageName
                    name = component(mood).className
                    targetActivity = "com.ichi2.anki.IntentHandler"
                    enabled = mood == Mood.DEFAULT
                },
            )
        }
    }

    private fun component(mood: Mood) = ComponentName(targetContext.packageName, "com.ichi2.anki.ankiquest.${mood.alias}")

    private fun state(mood: Mood) = targetContext.packageManager.getComponentEnabledSetting(component(mood))

    private fun enabled(): List<Mood> =
        Mood.entries.filter { mood ->
            val state = state(mood)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && mood == Mood.DEFAULT)
        }
}
