// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestWidgetSettings.Who
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A leaderboard widget's settings: whose standings it shows (friends, one of the player's groups,
 * or everyone, when the server has a global board), the timeframe and the text size. Opened when
 * the widget is placed and again from its header or the launcher's reconfigure action.
 */
class AnkiquestWidgetConfig : AnkiActivity(R.layout.activity_ankiquest_widget_settings) {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var who: RadioGroup
    private lateinit var period: RadioGroup
    private lateinit var scale: RadioGroup
    private lateinit var groupsStatus: TextView

    /** The choices on screen, kept while the groups load and across recreation. */
    private var chosen = AnkiquestWidgetSettings()

    /** The player's groups once loaded, and whether the server has a global board. */
    private var groups: List<AnkiquestSocial.Group> = emptyList()
    private var global = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Leaving without saving removes a widget that is being placed; a placed one keeps its settings.
        setResult(Activity.RESULT_CANCELED, result())
        who = findViewById(R.id.aq_widget_settings_who)
        period = findViewById(R.id.aq_widget_settings_period)
        scale = findViewById(R.id.aq_widget_settings_scale)
        groupsStatus = findViewById(R.id.aq_widget_settings_groups_status)
        for (heading in listOf(
            R.id.aq_widget_settings_who_heading,
            R.id.aq_widget_settings_period_heading,
            R.id.aq_widget_settings_scale_heading,
        )) {
            ViewCompat.setAccessibilityHeading(findViewById(heading), true)
        }
        findViewById<View>(R.id.aq_widget_settings_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.aq_widget_settings_save).setOnClickListener { save() }

        chosen = savedInstanceState?.let(::restore) ?: AnkiquestWidgetSettings.of(widgetId)
        val language = AnkiquestLanguage.context(this)
        fill(period, AnkiquestWidget.PERIODS.map { it.name to language.getString(it.label) }, chosen.period) {
            chosen = chosen.copy(period = it)
        }
        fill(scale, AnkiquestWidgetSettings.Scale.entries.map { it.name to language.getString(it.label) }, chosen.scale.name) { name ->
            chosen = chosen.copy(scale = AnkiquestWidgetSettings.Scale.valueOf(name))
        }
        showWho()
        loadGroups()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_WHO, chosen.who.name)
        outState.putLong(STATE_GROUP, chosen.group ?: 0)
        outState.putString(STATE_GROUP_NAME, chosen.groupName)
        outState.putString(STATE_PERIOD, chosen.period)
        outState.putString(STATE_SCALE, chosen.scale.name)
    }

    private fun restore(state: Bundle): AnkiquestWidgetSettings =
        AnkiquestWidgetSettings(
            who = Who.entries.firstOrNull { it.name == state.getString(STATE_WHO) } ?: Who.FRIENDS,
            group = state.getLong(STATE_GROUP).takeIf { it > 0 },
            groupName = state.getString(STATE_GROUP_NAME).orEmpty(),
            period = state.getString(STATE_PERIOD) ?: AnkiquestWidgetSettings.DEFAULT_PERIOD,
            scale =
                AnkiquestWidgetSettings.Scale.entries.firstOrNull { it.name == state.getString(STATE_SCALE) }
                    ?: AnkiquestWidgetSettings.Scale.AUTO,
        )

    /** Asks for the player's groups and whether the server has a global board, then offers them. */
    private fun loadGroups() {
        val account = AnkiquestHomeData.account() ?: return
        groupsStatus.setText(R.string.ankiquest_widget_settings_groups_loading)
        groupsStatus.isVisible = true
        lifecycleScope.launch {
            try {
                global = AnkiquestWidgetSettings.hasScopes(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest widget settings could not tell whether there is a global board")
            }
            try {
                groups = AnkiquestSocial.overview(account).groups
                groupsStatus.isVisible = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest widget settings could not load groups")
                groupsStatus.setText(R.string.ankiquest_widget_settings_groups_failed)
            }
            showWho()
        }
    }

    /** Offers friends, each group, and everyone when the server can show it; a saved choice stays on offer. */
    private fun showWho() {
        val language = AnkiquestLanguage.context(this)
        val options = mutableListOf(whoKey(Who.FRIENDS, null) to language.getString(R.string.ankiquest_nav_friends))
        val shownGroups = groups.map { it.id to it.name }.toMutableList()
        val savedGroup = chosen.group
        if (chosen.who == Who.GROUP && savedGroup != null && shownGroups.none { it.first == savedGroup }) {
            shownGroups += savedGroup to chosen.groupName.ifEmpty { language.getString(R.string.aq_friends_groups) }
        }
        shownGroups.forEach { (id, name) -> options += whoKey(Who.GROUP, id) to name }
        // A renamed group shows its new name in the header once saved again.
        shownGroups.firstOrNull { chosen.who == Who.GROUP && it.first == savedGroup }?.let { chosen = chosen.copy(groupName = it.second) }
        if (global || chosen.who == Who.GLOBAL) {
            options += whoKey(Who.GLOBAL, null) to language.getString(R.string.ankiquest_widget_who_global)
        }
        fill(who, options, whoKey(chosen.who, chosen.group)) { key ->
            val (kind, id) = parseWho(key)
            chosen =
                chosen.copy(
                    who = kind,
                    group = id,
                    groupName = shownGroups.firstOrNull { it.first == id }?.second.orEmpty(),
                )
        }
    }

    /** Puts one radio button per option into [group], with [selected] checked. */
    private fun fill(
        group: RadioGroup,
        options: List<Pair<String, String>>,
        selected: String,
        onChoose: (String) -> Unit,
    ) {
        group.setOnCheckedChangeListener(null)
        group.removeAllViews()
        for ((key, label) in options) {
            val button =
                RadioButton(this).apply {
                    id = View.generateViewId()
                    tag = key
                    text = label
                    textSize = 16f
                    minHeight = (MIN_ROW_DP * resources.displayMetrics.density).toInt()
                }
            group.addView(button)
            if (key == selected) group.check(button.id)
        }
        group.setOnCheckedChangeListener { _, checked -> group.findViewById<View>(checked)?.let { onChoose(it.tag as String) } }
    }

    private fun save() {
        AnkiquestWidget.saveSettings(this, widgetId, chosen)
        setResult(Activity.RESULT_OK, result())
        finish()
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    companion object {
        private const val STATE_WHO = "who"
        private const val STATE_GROUP = "group"
        private const val STATE_GROUP_NAME = "groupName"
        private const val STATE_PERIOD = "period"
        private const val STATE_SCALE = "scale"

        /** Rows at least as tall as a touch target. */
        private const val MIN_ROW_DP = 48

        /** The tag of the radio button for [who], and [group] for one group. */
        internal fun whoKey(
            who: Who,
            group: Long?,
        ): String = if (who == Who.GROUP) "group:$group" else who.name

        private fun parseWho(key: String): Pair<Who, Long?> =
            if (key.startsWith("group:")) {
                Who.GROUP to key.removePrefix("group:").toLongOrNull()
            } else {
                Who.valueOf(key) to null
            }
    }
}
