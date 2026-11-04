// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestChallengesActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val overview = AnkiquestChallenges.parse(JSONObject(example("challenges")))
    private val accepted = AnkiquestChallenges.parse(JSONObject(example("challenge_action")))

    @Before
    fun prepareGoals() {
        TimeManager.resetWith(MockTime(EXAMPLE_NOW))
        mockkObject(AnkiquestHomeData, AnkiquestChallenges)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestChallenges.overview(identity) } returns overview
        coEvery { AnkiquestChallenges.act(identity, any(), any()) } returns accepted
        coEvery { AnkiquestChallenges.weekly(identity, any(), any()) } returns overview
    }

    @After
    fun clearGoalMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestChallenges)
    }

    @Test
    fun `the list shows the invitation first, goals in progress with progress, and finished ones on request`() {
        val goals = launch()
        val texts = goals.texts()
        val invitations = texts.indexOf(goals.getString(R.string.aq_challenges_invitations))
        val active = texts.indexOf(goals.getString(R.string.aq_challenges_active_hint))
        assertTrue(invitations >= 0 && active > invitations, "invitations come before goals in progress")
        assertTrue(texts.indexOf("Fifty reviews each") in (invitations + 1) until active)
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_invited_by, "Friend")))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_status_complete)), "the reached goal says so")
        assertTrue(goals.hasText(goals.progress(R.string.aq_challenges_progress_you, 1, R.plurals.aq_challenges_days, 3)))
        assertTrue(goals.hasText(goals.progress(R.string.aq_challenges_progress_together, 8, R.plurals.aq_challenges_reviews, 5)))
        assertEquals(2, goals.views<ProgressBar>().size, "the invitation has no progress yet")
        assertTrue(
            goals.hasText(goals.getString(R.string.aq_challenges_member, "Friend", goals.getString(R.string.aq_challenges_member_invited))),
        )
        assertTrue(goals.hasText("Cerro"), "this week's suggested friend")

        assertFalse(goals.hasText("Weekend reviews"), "finished goals wait behind their button")
        goals.click(goals.getString(R.string.aq_challenges_history, 1))
        assertTrue(goals.hasText("Weekend reviews"))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_status_cancelled)))
    }

    @Test
    fun `accepting an invitation sends it and shows the server's answer`() {
        val goals = launch()
        goals.click(goals.getString(R.string.aq_challenges_accept))
        coVerify { AnkiquestChallenges.act(identity, 4, "accept") }
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_accepted)))
        assertFalse(goals.hasText(goals.getString(R.string.aq_challenges_invitations)), "nothing waits for an answer now")
        assertFalse(goals.hasText(goals.getString(R.string.aq_challenges_accept)))
    }

    @Test
    fun `declining an invitation sends it`() {
        coEvery { AnkiquestChallenges.act(identity, 4, "decline") } returns
            overview.copy(challenges = overview.challenges.filter { it.id != 4L })
        val goals = launch()
        goals.click(goals.getString(R.string.aq_challenges_decline))
        coVerify { AnkiquestChallenges.act(identity, 4, "decline") }
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_declined_done)))
        assertFalse(goals.hasText("Fifty reviews each"))
    }

    @Test
    fun `a refused choice says why and keeps the buttons`() {
        coEvery { AnkiquestChallenges.act(identity, any(), any()) } throws AnkiquestHttpException(400, "This challenge has ended.")
        val goals = launch()
        goals.click(goals.getString(R.string.aq_challenges_accept))
        assertTrue(
            goals.hasText(
                goals.getString(
                    R.string.aq_feed_failed_because,
                    goals.getString(R.string.aq_challenges_action_failed),
                    "This challenge has ended.",
                ),
            ),
        )
        assertTrue(goals.views<TextView>().any { it.text.toString() == goals.getString(R.string.aq_challenges_accept) && it.isEnabled })

        coEvery { AnkiquestChallenges.act(identity, any(), any()) } throws IOException("offline")
        goals.click(goals.getString(R.string.aq_challenges_decline))
        assertTrue(
            goals.hasText(
                goals.getString(
                    R.string.aq_feed_failed_because,
                    goals.getString(R.string.aq_challenges_action_failed),
                    goals.getString(R.string.aq_challenges_offline),
                ),
            ),
        )
    }

    @Test
    fun `a goal opens on its own with everyone's progress and its own actions`() {
        val goals = launch(challenge = 1)
        assertTrue(goals.hasText("Three study days together"))
        assertFalse(goals.hasText("Fifty reviews each"), "only the one goal")
        assertTrue(
            goals.hasText(
                goals.getString(R.string.aq_challenges_member, goals.getString(R.string.aq_challenges_you, "Hill"), goals.days(1)),
            ),
        )
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_member, "Cerro", goals.days(1))))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_cancel)), "its creator can end it")
        assertFalse(goals.hasText(goals.getString(R.string.aq_challenges_leave)), "its creator cannot leave it")

        goals.click(goals.getString(R.string.aq_challenges_cancel))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooperUntil { !goals.findViewById<View>(R.id.aq_feed_loading).isVisible }
        advanceRobolectricLooper()
        coVerify { AnkiquestChallenges.act(identity, 1, "cancel") }

        goals.click(goals.getString(R.string.aq_challenges_all))
        assertTrue(goals.hasText("Fifty reviews each"))
    }

    @Test
    fun `a goal someone else made can be left, and details open from the list`() {
        val goals = launch()
        goals.views<TextView>().filter { it.text.toString() == goals.getString(R.string.aq_challenges_details) }[1].performClick()
        advanceRobolectricLooper()
        assertTrue(goals.hasText("Spanish sprint"))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_leave)))
        assertFalse(goals.hasText(goals.getString(R.string.aq_challenges_cancel)))
        goals.onBackPressedDispatcher.onBackPressed()
        advanceRobolectricLooper()
        assertFalse(goals.isFinishing, "back from a goal opened from the list shows the list")
        assertTrue(goals.hasText("Fifty reviews each"))
    }

    @Test
    fun `a goal that is gone says so`() {
        val goals = launch(challenge = 99)
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_missing)))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_refresh)))
    }

    @Test
    fun `starting a goal sends the chosen friends and opens the new goal`() {
        val sent = slot<AnkiquestChallenges.NewGoal>()
        val created = overview.challenges.first { it.id == 1L }.copy(id = 9, title = "Fresh start")
        coEvery { AnkiquestChallenges.create(identity, capture(sent), any()) } returns
            overview.copy(challenges = listOf(created) + overview.challenges)
        val goals = launch()
        goals.click(goals.getString(R.string.aq_challenges_start))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        send.performClick()
        assertTrue(dialog.isShowing, "a goal without friends is not sent")
        assertTrue(
            dialog.window!!.decorView.allViews.any {
                it is TextView &&
                    it.text.toString() == goals.getString(R.string.aq_challenges_create_no_friends)
            },
        )

        dialog.window!!
            .decorView.allViews
            .filterIsInstance<CheckBox>()
            .first { it.text.toString() == "Friend" }
            .isChecked = true
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<EditText>()
            .first()
            .setText("Fresh start")
        send.performClick()
        advanceRobolectricLooperUntil { !goals.findViewById<View>(R.id.aq_feed_loading).isVisible }
        advanceRobolectricLooper()
        assertEquals(
            AnkiquestChallenges.NewGoal("Fresh start", false, false, 3, 7, listOf("friend"), false),
            sent.captured,
        )
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_create_sent)))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_all)), "the new goal is open")
        assertTrue(goals.hasText("Fresh start"))
    }

    @Test
    fun `the weekly suggestion invites the friend`() {
        val goals = launch()
        goals.click(goals.getString(R.string.aq_challenges_weekly_invite))
        coVerify { AnkiquestChallenges.weekly(identity, overview.suggestion!!, "invite") }
    }

    @Test
    fun `without goals the screen invites to start one`() {
        coEvery { AnkiquestChallenges.overview(identity) } returns overview.copy(challenges = emptyList(), suggestion = null)
        val goals = launch()
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_empty)))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_start)))
    }

    @Test
    fun `offline and refused lists say what to do`() {
        coEvery { AnkiquestChallenges.overview(identity) } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_challenges_offline)))

        coEvery { AnkiquestChallenges.overview(identity) } throws AnkiquestHttpException(401)
        val refused = launch()
        assertTrue(refused.hasText(refused.getString(R.string.aq_home_auth)))
        refused.click(refused.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(refused).nextStartedActivity.component?.className)
    }

    @Test
    fun `an older server without the endpoint says it needs updating`() {
        coEvery { AnkiquestChallenges.overview(identity) } throws AnkiquestHttpException(404)
        val goals = launch(challenge = 42, account = identity.notificationAccount)
        assertTrue(goals.hasText(goals.getString(R.string.aq_server_outdated)))
        assertNull(shadowOf(goals).nextStartedActivity)
        assertFalse(goals.isFinishing)
    }

    @Test
    fun `a notification for another account shows this account's list, not the goal`() {
        val goals = launch(challenge = 1, account = "https://other.example.test/hill")
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_account_route_changed)))
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_invitations)), "the list, not goal 1")
        assertFalse(goals.hasText(goals.getString(R.string.aq_challenges_all)))
    }

    @Test
    fun `without an account the screen asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val goals = launch()
        assertTrue(goals.hasText(goals.getString(R.string.aq_challenges_connect)))
        coVerify(exactly = 0) { AnkiquestChallenges.overview(any()) }
    }

    private fun launch(
        challenge: Long? = null,
        account: String? = null,
    ): AnkiquestChallengesActivity =
        startRegularActivity<AnkiquestChallengesActivity>(AnkiquestChallengesActivity.intent(targetContext, challenge, account))
            .also { goals -> advanceRobolectricLooperUntil { !goals.findViewById<View>(R.id.aq_feed_loading).isVisible } }

    private fun AnkiquestChallengesActivity.days(count: Int) = resources.getQuantityString(R.plurals.aq_challenges_days, count, count)

    private fun AnkiquestChallengesActivity.progress(
        line: Int,
        done: Int,
        unit: Int,
        target: Int,
    ) = getString(line, done, resources.getQuantityString(unit, target, target))

    private inline fun <reified T : View> AnkiquestChallengesActivity.views(): List<T> =
        findViewById<View>(R.id.aq_feed_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestChallengesActivity.texts(): List<String> = views<TextView>().map { it.text.toString() }

    private fun AnkiquestChallengesActivity.hasText(value: String): Boolean = texts().any { it == value }

    private fun AnkiquestChallengesActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_feed_loading).isVisible }
        advanceRobolectricLooper()
    }

    private companion object {
        const val EXAMPLE_NOW = 1_793_183_460_000L

        fun example(name: String): String =
            assertNotNull(AnkiquestChallengesActivityTest::class.java.classLoader?.getResource("ankiquest-api/$name.json"))
                .readText(Charsets.UTF_8)
    }
}
