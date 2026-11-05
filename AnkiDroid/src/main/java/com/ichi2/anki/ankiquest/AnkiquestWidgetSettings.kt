// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.json.JSONException
import org.json.JSONObject

/**
 * What one placed leaderboard widget shows and how large: whose standings ([who], and [group] for
 * one group), which [period], and its text [scale]. Saved per widget id and removed with the widget,
 * so a new widget always starts from the defaults.
 */
internal data class AnkiquestWidgetSettings(
    val who: Who = Who.FRIENDS,
    /** The group's id, for [Who.GROUP]. */
    val group: Long? = null,
    /** The group's name when it was chosen, for the header. */
    val groupName: String = "",
    val period: String = DEFAULT_PERIOD,
    val scale: Scale = Scale.AUTO,
) {
    /** Whose standings, as `GET /api/leaderboard?scope=` names them. */
    enum class Who(
        val scope: String,
    ) {
        /** The player's circle: themselves, their friends and their groups' members, as the widget always showed. */
        FRIENDS("friends"),
        GROUP("group"),
        GLOBAL("global"),
    }

    /** A fixed text size, or [AUTO] to fit the widget's size. */
    enum class Scale(
        val factor: Float?,
        @StringRes val label: Int,
    ) {
        AUTO(null, R.string.ankiquest_widget_scale_auto),
        SMALL(0.9f, R.string.ankiquest_widget_scale_small),
        MEDIUM(1f, R.string.ankiquest_widget_scale_medium),
        LARGE(1.25f, R.string.ankiquest_widget_scale_large),
        EXTRA_LARGE(1.6f, R.string.ankiquest_widget_scale_extra_large),
    }

    /** Whether this widget needs standings of its own, rather than the circle every widget shares. */
    val ownBoard: Boolean get() = who != Who.FRIENDS

    /** The scale to draw at, for a widget with [options]. */
    fun scale(options: Bundle?): Float = scale.factor ?: autoScale(options)

    /** What the header says the widget shows, e.g. "Study group · Week". */
    fun header(context: Context): String {
        val language = AnkiquestLanguage.context(context)
        val whose =
            when (who) {
                Who.FRIENDS -> language.getString(R.string.ankiquest_nav_friends)
                Who.GROUP -> groupName.ifEmpty { language.getString(R.string.aq_friends_groups) }
                Who.GLOBAL -> language.getString(R.string.ankiquest_widget_who_global)
            }
        val label = AnkiquestWidget.PERIODS.firstOrNull { it.name == period }?.label ?: R.string.ankiquest_period_week
        return "$whose · ${language.getString(label)}"
    }

    /**
     * The `GET /api/leaderboard` request for a widget with its own standings ([ownBoard]). Servers
     * without scopes ([hasScopes]) ignore `scope` and `group` and answer the circle.
     */
    fun leaderboardUrl(account: HomeAccount): HttpUrl =
        account
            .url("api/leaderboard")
            .newBuilder()
            .addQueryParameter("scope", who.scope)
            .apply { if (who == Who.GROUP && group != null) addQueryParameter("group", group.toString()) }
            .addQueryParameter("period", period)
            .build()

    private fun toJson(): JSONObject =
        JSONObject()
            .put("who", who.name)
            .put("group", group ?: JSONObject.NULL)
            .put("groupName", groupName)
            .put("period", period)
            .put("scale", scale.name)

    companion object {
        const val DEFAULT_PERIOD = "week"

        /**
         * The player setting in `GET /api/decks/{user}` that servers with a global board have. They
         * also answer `scope` (friends, group, global) on `/api/leaderboard`.
         */
        const val GLOBAL_SETTING = "global_leaderboard"

        /** Whether [account]'s server has a global board and answers `scope`, from its deck settings. */
        suspend fun hasScopes(account: HomeAccount): Boolean =
            withContext(Dispatchers.IO) {
                AnkiquestApi
                    .json(account, AnkiquestApi.request(account, "api/decks/${account.encodedUser}").build())
                    .has(GLOBAL_SETTING)
            }

        private const val KEY = "ankiquestWidgetSettings"

        /** Where widgets saved their period before they had settings. */
        private const val LEGACY_PERIOD_KEY = "ankiquestWidgetPeriod"

        /** The size [AUTO][Scale.AUTO] draws at 1x; larger widgets draw larger, up to [MAX_AUTO_SCALE]. */
        private const val BASE_WIDTH_DP = 280f
        private const val BASE_HEIGHT_DP = 170f
        private const val MAX_AUTO_SCALE = 1.6f

        /**
         * The scale for a widget of [widthDp] x [heightDp]: it grows smoothly with the smaller of
         * the two against [BASE_WIDTH_DP] x [BASE_HEIGHT_DP], so a widget a few dp smaller looks
         * nearly the same, and stays between 1x and [MAX_AUTO_SCALE].
         */
        fun autoScale(
            widthDp: Float,
            heightDp: Float,
        ): Float = minOf(widthDp / BASE_WIDTH_DP, heightDp / BASE_HEIGHT_DP).coerceIn(1f, MAX_AUTO_SCALE)

        /**
         * The scale for a placed widget, from its minimum width and minimum height: the size it has
         * in both orientations, so turning the phone does not change it.
         */
        fun autoScale(options: Bundle?): Float =
            autoScale(
                (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0).toFloat(),
                (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0).toFloat(),
            )

        /** The settings of widget [widgetId]; a widget from before settings keeps the period it had. */
        fun of(widgetId: Int): AnkiquestWidgetSettings {
            val prefs = AnkiDroidApp.sharedPrefs()
            val legacy = prefs.getString("$LEGACY_PERIOD_KEY:$widgetId", null)
            val stored = prefs.getString("$KEY:$widgetId", null) ?: return AnkiquestWidgetSettings(period = legacy ?: DEFAULT_PERIOD)
            return try {
                val json = JSONObject(stored)
                val who = Who.entries.firstOrNull { it.name == json.optString("who") } ?: Who.FRIENDS
                val group = if (json.isNull("group")) null else json.optLong("group").takeIf { it > 0 }
                AnkiquestWidgetSettings(
                    who = if (who == Who.GROUP && group == null) Who.FRIENDS else who,
                    group = group,
                    groupName = json.optString("groupName"),
                    period = json.optString("period").takeIf { p -> AnkiquestWidget.PERIODS.any { it.name == p } } ?: DEFAULT_PERIOD,
                    scale = Scale.entries.firstOrNull { it.name == json.optString("scale") } ?: Scale.AUTO,
                )
            } catch (_: JSONException) {
                AnkiquestWidgetSettings()
            }
        }

        fun save(
            widgetId: Int,
            settings: AnkiquestWidgetSettings,
        ) {
            AnkiDroidApp.sharedPrefs().edit {
                putString("$KEY:$widgetId", settings.toJson().toString())
                remove("$LEGACY_PERIOD_KEY:$widgetId")
            }
        }

        /** Forgets everything saved for [widgetIds], their own standings included. */
        fun remove(widgetIds: IntArray) {
            AnkiDroidApp.sharedPrefs().edit {
                for (id in widgetIds) {
                    remove("$KEY:$id")
                    remove("$LEGACY_PERIOD_KEY:$id")
                    remove("${AnkiquestWidget.BOARD_KEY}:$id")
                }
            }
        }
    }
}
