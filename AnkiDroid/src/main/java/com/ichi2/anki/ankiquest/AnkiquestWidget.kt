// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.app.PendingIntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.widget.RemoteViewsCompat
import androidx.core.widget.RemoteViewsCompat.RemoteCollectionItems
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.widget.getAppWidgetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.text.NumberFormat

/** Scrollable homescreen leaderboard. Tapping the header or a player opens the study library. */
open class AnkiquestWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AnkiquestPoll.schedule(context)
        AnkiquestPoll.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        AnkiquestPoll.schedule(context)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        AnkiquestWidgetSettings.remove(appWidgetIds)
    }

    /** A resized widget is drawn again for its new size straight away, from the standings it already shows. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        redraw(context, appWidgetManager, styles.first { it.provider == javaClass }, appWidgetId)
    }

    companion object {
        private const val MIN_REFRESH_MS = 30 * 1000L

        /** A widget's own standings ([AnkiquestWidgetSettings.ownBoard]), per widget id. */
        internal const val BOARD_KEY = "ankiquestWidgetBoard"

        /** The companion's size in the widget layouts. */
        private const val COMPANION_DP = 28

        /** The leaderboard periods a widget can show, as served by the ankiquest API. */
        class Period(
            val name: String,
            @StringRes val label: Int,
        )

        val PERIODS =
            listOf(
                Period("hour", R.string.ankiquest_period_hour),
                Period("day", R.string.ankiquest_period_day),
                Period("week", R.string.ankiquest_period_week),
                Period("month", R.string.ankiquest_period_month),
                Period("year", R.string.ankiquest_period_year),
                Period("all", R.string.ankiquest_period_all),
            )

        /** Widget [id]'s settings, as the settings screen saved them. */
        internal fun settingsOf(id: Int): AnkiquestWidgetSettings = AnkiquestWidgetSettings.of(id)

        /** Saves widget [widgetId]'s settings and draws it again with them. */
        internal fun saveSettings(
            context: Context,
            widgetId: Int,
            settings: AnkiquestWidgetSettings,
        ) {
            val before = AnkiquestWidgetSettings.of(widgetId)
            AnkiquestWidgetSettings.save(widgetId, settings)
            val sameBoard = before.who == settings.who && before.group == settings.group && before.period == settings.period
            if (!sameBoard) AnkiDroidApp.sharedPrefs().edit { remove("$BOARD_KEY:$widgetId") }
            val manager = getAppWidgetManager(context)
            val style = styles.firstOrNull { widgetId in widgetIds(context, it) }
            if (manager != null && style != null) redraw(context, manager, style, widgetId)
            if (settings.ownBoard && !sameBoard) AnkiquestPoll.refreshNow(context)
        }

        /**
         * Orders [board] by the XP of [period], falling back to the week for older servers. Rows
         * without `periods`, like strangers on the global board, carry the asked period's `xp`; a
         * board whose rows all have a `rank` keeps the server's order, where the player's own row may
         * come last with its own place.
         */
        internal fun forPeriod(
            board: JSONArray,
            period: String,
        ): JSONArray {
            val entries = board.objects()
            val xpOf = { entry: JSONObject ->
                entry.optJSONObject("periods")?.takeIf { it.has(period) }?.optLong(period)
                    ?: if (entry.has("xp")) entry.optLong("xp") else entry.optLong("week_xp")
            }
            val ranked = entries.isNotEmpty() && entries.all { it.optInt("rank") > 0 }
            val ordered = JSONArray()
            entries
                .sortedWith(
                    if (ranked) {
                        compareBy { it.optInt("rank") }
                    } else {
                        compareByDescending(xpOf).thenByDescending { it.optLong("xp_total") }
                    },
                ).forEach { ordered.put(JSONObject(it.toString()).put("xp", xpOf(it))) }
            return ordered
        }

        internal class Style(
            val provider: Class<out AnkiquestWidget>,
            @LayoutRes val layout: Int,
            @LayoutRes val rowLayout: Int,
            @DrawableRes val ownRow: Int,
        )

        internal val styles =
            listOf(
                Style(
                    AnkiquestWidget::class.java,
                    R.layout.widget_ankiquest,
                    R.layout.widget_ankiquest_row,
                    R.drawable.ankiquest_widget_row_self,
                ),
                Style(
                    AnkiquestTransparentWidget::class.java,
                    R.layout.widget_ankiquest_transparent,
                    R.layout.widget_ankiquest_row_transparent,
                    R.drawable.ankiquest_widget_row_self_clear,
                ),
            )

        @Volatile
        private var requestedAt = 0L

        /** What the widgets show now, so a resized one is drawn again without asking the server. */
        private class Drawn(
            val board: JSONArray?,
            val fetchedAt: Long,
            val offline: Boolean,
            @StringRes val note: Int? = null,
        )

        @Volatile
        private var drawn: Drawn? = null

        /** Refreshes the leaderboard soon, at most every [MIN_REFRESH_MS]. */
        fun requestUpdate(context: Context) {
            val now = TimeManager.time.intTimeMS()
            if (now - requestedAt < MIN_REFRESH_MS) return
            requestedAt = now
            AnkiquestPoll.refreshNow(context)
        }

        private fun widgetIds(
            context: Context,
            style: Style,
        ): IntArray =
            getAppWidgetManager(context)
                ?.getAppWidgetIds(ComponentName(context, style.provider))
                ?: IntArray(0)

        internal fun widgetDestination(context: Context): Intent =
            Intent(context, DeckPicker::class.java)
                .putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** The refresh button's broadcast, sent to [AnkiquestWidgetRefreshReceiver] which other apps cannot reach. */
        internal fun refreshIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, AnkiquestWidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Shows every placed widget as refreshing and fetches the leaderboard now. */
        internal fun refresh(context: Context) {
            showRefreshing(context)
            AnkiquestPoll.refreshNow(context)
        }

        private fun showRefreshing(context: Context) {
            for (style in styles) {
                val ids = widgetIds(context, style)
                if (ids.isEmpty()) continue
                val views = RemoteViews(context.packageName, style.layout)
                views.setTextViewText(R.id.ankiquest_widget_updated, "…")
                getAppWidgetManager(context)?.partiallyUpdateAppWidget(ids, views)
            }
        }

        /**
         * Draws [board] into every placed widget of every style. Each placed widget, transparent
         * ones included, gets its own adapter ID.
         *
         * @param board this week's standings, or null when ankiquest is not configured
         * @param fetchedAt when [board] was fetched from the server
         * @param offline whether the last fetch failed and [board] is a cached copy
         */
        fun render(
            context: Context,
            board: JSONArray?,
            fetchedAt: Long,
            offline: Boolean,
        ) {
            val shown = Drawn(board, fetchedAt, offline)
            drawn = shown
            AnkiquestStreakWidget.render(context, board)
            val manager = getAppWidgetManager(context) ?: return
            for (style in styles) {
                for (id in widgetIds(context, style)) draw(context, manager, style, id, shown)
            }
        }

        /** Draws widget [id] again for its current size; before anything was drawn in this process, it fetches instead. */
        internal fun redraw(
            context: Context,
            manager: AppWidgetManager,
            style: Style,
            id: Int,
        ) {
            val shown = drawn ?: return AnkiquestPoll.refreshNow(context)
            draw(context, manager, style, id, shown)
        }

        /** Draws the streak widgets again from the standings already shown, like [redraw]. */
        internal fun redrawStreaks(context: Context) {
            val shown = drawn ?: return AnkiquestPoll.refreshNow(context)
            AnkiquestStreakWidget.render(context, shown.board)
        }

        private fun draw(
            context: Context,
            manager: AppWidgetManager,
            style: Style,
            id: Int,
            shown: Drawn,
        ) {
            val settings = settingsOf(id)
            val scale = settings.scale(manager.getAppWidgetOptions(id))
            // Not signed in shows the same note on every widget; otherwise a widget of one group or of
            // everyone shows its own standings, or that they are still on their way.
            val source =
                if (settings.ownBoard && shown.board != null) {
                    ownBoard(id) ?: Drawn(null, 0, false, R.string.aq_board_loading)
                } else {
                    shown
                }
            val ranked = source.board?.let { forPeriod(it, settings.period) }
            val items = collection(context, ranked ?: JSONArray(), style, scale)
            val views = layout(context, ranked, source.fetchedAt, source.offline, style, settings, scale, id, source.note)
            RemoteViewsCompat.setRemoteAdapter(context, views, id, R.id.ankiquest_widget_list, items)
            manager.updateAppWidget(id, views)
        }

        /** Widget [id]'s own standings as last fetched for the signed-in account, or null before the first fetch. */
        private fun ownBoard(id: Int): Drawn? {
            val stored = AnkiDroidApp.sharedPrefs().getString("$BOARD_KEY:$id", null) ?: return null
            return try {
                val json = JSONObject(stored)
                if (json.optString("account") != AnkiquestHomeData.account()?.scope) return null
                val note = OwnNote.entries.firstOrNull { it.name == json.optString("note") }?.text
                Drawn(json.optJSONArray("board") ?: JSONArray(), json.optLong("at"), json.optBoolean("offline"), note)
            } catch (_: JSONException) {
                null
            }
        }

        /** Why a widget with its own standings shows none. */
        private enum class OwnNote(
            @StringRes val text: Int,
        ) {
            GLOBAL_UNAVAILABLE(R.string.ankiquest_widget_global_unavailable),
        }

        private fun storeOwnBoard(
            id: Int,
            account: HomeAccount,
            board: JSONArray,
            at: Long,
            offline: Boolean,
            note: OwnNote? = null,
        ) {
            val value =
                JSONObject()
                    .put("account", account.scope)
                    .put("board", board)
                    .put("at", at)
                    .put("offline", offline)
                    .put("note", note?.name ?: "")
            AnkiDroidApp.sharedPrefs().edit { putString("$BOARD_KEY:$id", value.toString()) }
        }

        /**
         * Fetches the standings of every placed widget that shows one group or everyone, and draws
         * those widgets. Widgets asking the same thing share one request. A server without
         * scopes ([AnkiquestWidgetSettings.hasScopes]) gets a group's standings from [circle], the
         * standings every widget shares, by keeping the group's members; it has no global board.
         * A group the player is no longer in (`403`) shows nobody. When a fetch fails otherwise, the
         * widget keeps what it showed, marked offline.
         */
        internal suspend fun refreshOwnBoards(
            context: Context,
            circle: JSONArray?,
        ) {
            val manager = getAppWidgetManager(context) ?: return
            val placed =
                styles
                    .flatMap { style -> widgetIds(context, style).map { style to it } }
                    .filter { (_, id) -> settingsOf(id).ownBoard }
            if (placed.isEmpty() || circle == null) return
            val account = AnkiquestHomeData.account() ?: return
            val scoped = fetch { AnkiquestWidgetSettings.hasScopes(account) }
            val fetched = mutableMapOf<String, Result<JSONArray>>()
            var overview: Result<AnkiquestSocial.Overview>? = null
            for ((style, id) in placed) {
                val settings = settingsOf(id)
                val now = TimeManager.time.intTimeMS()
                val answer: Result<JSONArray>? =
                    when {
                        scoped.getOrNull() == true -> {
                            val url = settings.leaderboardUrl(account)
                            fetched.getOrPut(url.toString()) { fetch { leaderboard(account, url) } }
                        }
                        // Whether there is a global board is unknown while the server cannot be reached.
                        scoped.isFailure && settings.who == AnkiquestWidgetSettings.Who.GLOBAL -> Result.failure(scoped.exceptionOrNull()!!)
                        settings.who == AnkiquestWidgetSettings.Who.GROUP -> {
                            val social = overview ?: fetch { AnkiquestSocial.overview(account) }.also { overview = it }
                            social.map { groupBoard(circle, it, settings.group, account.user) }
                        }
                        else -> null
                    }
                when {
                    answer == null -> storeOwnBoard(id, account, JSONArray(), now, offline = false, note = OwnNote.GLOBAL_UNAVAILABLE)
                    answer.isSuccess -> storeOwnBoard(id, account, answer.getOrThrow(), now, offline = false)
                    (answer.exceptionOrNull() as? AnkiquestHttpException)?.code == 403 ->
                        storeOwnBoard(id, account, JSONArray(), now, offline = false)
                    else -> {
                        val last = ownBoard(id)
                        storeOwnBoard(id, account, last?.board ?: JSONArray(), last?.fetchedAt ?: 0, offline = true)
                    }
                }
                draw(context, manager, style, id, drawn ?: Drawn(circle, now, false))
            }
        }

        private suspend fun <T> fetch(block: suspend () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest widget standings fetch failed")
                Result.failure(e)
            }

        private suspend fun leaderboard(
            account: HomeAccount,
            url: HttpUrl,
        ): JSONArray =
            withContext(Dispatchers.IO) {
                val request = AnkiquestApi.request(url, account.token, account.language).build()
                AnkiquestApi.call(account, request) { AnkiquestApi.jsonArray(it) }
            }

        /** The rows of [circle] for [group]'s members and [me], for servers that cannot answer one group. */
        internal fun groupBoard(
            circle: JSONArray,
            overview: AnkiquestSocial.Overview,
            group: Long?,
            me: String,
        ): JSONArray {
            val members =
                overview.groups
                    .firstOrNull { it.id == group }
                    ?.members
                    .orEmpty()
                    .map { it.user }
                    .toSet() + me
            return JSONArray(circle.objects().filter { it.optString("user") in members })
        }

        /** Whether views of a fixed size can grow; launchers before Android 12 keep the layout's sizes. */
        private val boxesGrow get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /**
         * Scales the text of [id] from its size in the layout, [sp], by [scale]. Text inside a box of
         * fixed size ([boxed]) only changes along with its box.
         */
        private fun RemoteViews.scaleText(
            @IdRes id: Int,
            sp: Float,
            scale: Float,
            boxed: Boolean = false,
        ) {
            if (boxed && !boxesGrow) return
            setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * scale)
        }

        /** Scales the box [id] of [widthDp] x [heightDp] in the layout by [scale]; a null height keeps wrapping its content. */
        private fun RemoteViews.scaleBox(
            @IdRes id: Int,
            widthDp: Float,
            heightDp: Float?,
            scale: Float,
        ) {
            if (!boxesGrow) return
            setViewLayoutWidth(id, widthDp * scale, TypedValue.COMPLEX_UNIT_DIP)
            if (heightDp != null) setViewLayoutHeight(id, heightDp * scale, TypedValue.COMPLEX_UNIT_DIP)
        }

        /** Opens the settings of widget [widgetId]; each widget has its own. */
        internal fun settingsIntent(
            context: Context,
            widgetId: Int,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, AnkiquestWidgetConfig::class.java)
                    .setData("ankiquest-widget:$widgetId".toUri())
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /**
         * @param widgetId the placed widget, whose header then opens its settings
         * @param note shown instead of the standings, like while a widget's own ones are on their way
         */
        internal fun layout(
            context: Context,
            board: JSONArray?,
            fetchedAt: Long,
            offline: Boolean,
            style: Style = styles.first(),
            settings: AnkiquestWidgetSettings = AnkiquestWidgetSettings(),
            scale: Float = 1f,
            widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
            @StringRes note: Int? = null,
        ): RemoteViews {
            val language = AnkiquestLanguage.context(context)
            val period = settings.period
            val views = RemoteViews(context.packageName, style.layout)
            views.scaleText(R.id.ankiquest_widget_title, 15f, scale)
            views.scaleText(R.id.ankiquest_widget_period, 12f, scale)
            views.scaleText(R.id.ankiquest_widget_updated, 11f, scale)
            views.scaleText(R.id.ankiquest_widget_status, 13f, scale)
            views.scaleBox(R.id.ankiquest_widget_companion, COMPANION_DP.toFloat(), COMPANION_DP.toFloat(), scale)
            views.scaleBox(R.id.ankiquest_widget_refresh, 32f, 32f, scale)
            views.setViewVisibility(R.id.ankiquest_widget_companion, if (AnkiquestCompanion.visible()) View.VISIBLE else View.GONE)
            if (AnkiquestCompanion.visible()) {
                val art = AnkiquestCompanion.resource(R.drawable.aki_face)
                val pixels = (COMPANION_DP * scale * context.resources.displayMetrics.density).toInt()
                val bitmap = AnkiquestArt.bitmap(context, art, pixels)
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.ankiquest_widget_companion, bitmap)
                } else {
                    views.setImageViewResource(R.id.ankiquest_widget_companion, art)
                }
            }
            val header = settings.header(context)
            views.setTextViewText(R.id.ankiquest_widget_period, header)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                views.setOnClickPendingIntent(R.id.ankiquest_widget_period, settingsIntent(context, widgetId))
                views.setContentDescription(
                    R.id.ankiquest_widget_period,
                    language.getString(R.string.ankiquest_widget_settings_open, header),
                )
            }
            views.setOnClickPendingIntent(
                R.id.ankiquest_widget_root,
                PendingIntent.getActivity(
                    context,
                    1000 + period.hashCode(),
                    widgetDestination(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            views.setPendingIntentTemplate(
                R.id.ankiquest_widget_list,
                PendingIntentCompat.getActivity(
                    context,
                    2000 + period.hashCode(),
                    widgetDestination(context),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                    true,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.ankiquest_widget_refresh,
                refreshIntent(context),
            )

            val time = if (fetchedAt > 0) DateUtils.formatDateTime(language, fetchedAt, DateUtils.FORMAT_SHOW_TIME) else ""
            views.setTextViewText(
                R.id.ankiquest_widget_updated,
                if (offline) language.getString(R.string.ankiquest_widget_offline_at, time) else time,
            )

            when {
                note != null -> status(views, language.getString(note))
                board == null -> status(views, language.getString(R.string.ankiquest_widget_unconfigured))
                board.length() == 0 && offline -> status(views, language.getString(R.string.ankiquest_widget_offline))
                board.length() == 0 -> status(views, language.getString(R.string.ankiquest_widget_empty))
                else -> {
                    views.setViewVisibility(R.id.ankiquest_widget_status, View.GONE)
                    views.setViewVisibility(R.id.ankiquest_widget_list, View.VISIBLE)
                }
            }
            return views
        }

        private fun status(
            views: RemoteViews,
            text: String,
        ) {
            views.setTextViewText(R.id.ankiquest_widget_status, text)
            views.setViewVisibility(R.id.ankiquest_widget_status, View.VISIBLE)
            views.setViewVisibility(R.id.ankiquest_widget_list, View.GONE)
        }

        internal fun collection(
            context: Context,
            board: JSONArray,
            style: Style = styles.first(),
            scale: Float = 1f,
        ): RemoteCollectionItems {
            val items = RemoteCollectionItems.Builder().setViewTypeCount(1)
            val me = Ankiquest.player()
            val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())
            val languageContext = AnkiquestLanguage.context(context)
            val xpOf = { entry: JSONObject -> entry.optLong("xp", entry.optLong("week_xp")) }
            val leaderXp = board.optJSONObject(0)?.let(xpOf)?.coerceAtLeast(1) ?: 1
            for (i in 0 until board.length()) {
                val entry = board.getJSONObject(i)
                val xp = xpOf(entry)
                val streak = entry.optInt("streak")
                val views = RemoteViews(context.packageName, style.rowLayout)
                views.scaleText(R.id.ankiquest_widget_rank, 16f, scale, boxed = true)
                views.scaleText(R.id.ankiquest_widget_initial, 16f, scale, boxed = true)
                views.scaleText(R.id.ankiquest_widget_name, 14f, scale)
                views.scaleText(R.id.ankiquest_widget_streak, 12f, scale)
                views.scaleText(R.id.ankiquest_widget_level, 12f, scale)
                views.scaleText(R.id.ankiquest_widget_xp, 14f, scale)
                views.scaleBox(R.id.ankiquest_widget_place, 30f, null, scale)
                views.scaleBox(R.id.ankiquest_widget_medal, 22f, 22f, scale)
                views.scaleBox(R.id.ankiquest_widget_portrait, 32f, 32f, scale)
                views.setInt(
                    R.id.ankiquest_widget_row,
                    "setBackgroundResource",
                    if (entry.getString("user") == me) style.ownRow else 0,
                )
                val place = entry.optInt("rank").takeIf { it > 0 } ?: (i + 1)
                val medal = AnkiquestArt.medal(place)
                views.setTextViewText(R.id.ankiquest_widget_rank, if (medal == null) numbers.format(place) else "")
                views.setViewVisibility(R.id.ankiquest_widget_medal, if (medal == null) View.GONE else View.VISIBLE)
                if (medal != null) {
                    views.setImageViewResource(R.id.ankiquest_widget_medal, medal)
                    views.setContentDescription(
                        R.id.ankiquest_widget_medal,
                        languageContext.getString(R.string.ankiquest_widget_place, place),
                    )
                }
                views.setTextViewText(R.id.ankiquest_widget_name, entry.getString("display"))
                val photo = AnkiquestAvatars.bitmap(entry.getString("user"))
                val display = entry.getString("display").trim().ifEmpty { entry.getString("user") }
                val initial = display.take(display.offsetByCodePoints(0, minOf(1, display.codePointCount(0, display.length)))).uppercase()
                views.setTextViewText(R.id.ankiquest_widget_initial, initial)
                views.setViewVisibility(R.id.ankiquest_widget_initial, if (photo == null) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.ankiquest_widget_avatar, if (photo == null) View.GONE else View.VISIBLE)
                views.setImageViewBitmap(R.id.ankiquest_widget_avatar, photo)
                val state = streakState(entry)
                val icon =
                    when (state) {
                        "studied" -> R.drawable.ankiquest_streak_studied
                        "protected" -> R.drawable.ankiquest_streak_protected
                        else -> R.drawable.ankiquest_streak_pending
                    }
                val label = streakLabel(languageContext, entry, state)
                views.setTextViewText(R.id.ankiquest_widget_streak, if (streak > 0) numbers.format(streak) else "")
                views.setTextViewCompoundDrawables(R.id.ankiquest_widget_streak, if (streak > 0) icon else 0, 0, 0, 0)
                views.setContentDescription(
                    R.id.ankiquest_widget_streak,
                    if (streak > 0) {
                        languageContext.getString(
                            R.string.ankiquest_streak_description,
                            numbers.format(streak),
                            label,
                        )
                    } else {
                        null
                    },
                )
                views.setTextViewText(
                    R.id.ankiquest_widget_level,
                    languageContext.getString(R.string.ankiquest_widget_level, entry.getInt("level")),
                )
                views.setProgressBar(R.id.ankiquest_widget_bar, 1000, (xp * 1000 / leaderXp).toInt(), false)
                views.setTextViewText(R.id.ankiquest_widget_xp, numbers.format(xp))
                views.setOnClickFillInIntent(R.id.ankiquest_widget_row, Intent())
                items.addItem(i.toLong(), views)
            }
            return items.build()
        }

        /** How today stands for [entry]'s streak, in words, for a [state] from [streakState]. */
        internal fun streakLabel(
            languageContext: Context,
            entry: JSONObject,
            state: String,
        ): String =
            when (state) {
                "studied" -> languageContext.getString(R.string.ankiquest_streak_studied)
                "protected" -> languageContext.getString(R.string.ankiquest_streak_protected)
                "short" ->
                    languageContext.getString(
                        R.string.ankiquest_streak_short,
                        entry.optLong("today_minutes"),
                        entry.optLong("streak_minutes"),
                    )
                "pending" -> languageContext.getString(R.string.ankiquest_streak_pending)
                else -> languageContext.getString(R.string.ankiquest_streak_unknown)
            }

        /**
         * studied, protected, short (under the minimum), pending, or unknown when the data is stale or missing.
         * Servers with a streak minimum say how far today is; older ones count any review.
         */
        internal fun streakState(entry: JSONObject): String {
            val dayEndsAt = entry.optLong("day_ends_at")
            if (dayEndsAt > 0 && dayEndsAt <= TimeManager.time.intTimeMS()) return "unknown"
            if (!entry.has("today_reviews") || entry.isNull("today_reviews")) return "unknown"
            return when {
                entry.optString("streak_state") == "studied" -> "studied"
                entry.optLong("today_reviews") > 0 &&
                    entry.optLong("today_minutes") >= entry.optLong("streak_minutes", 0) -> "studied"
                entry.optString("streak_state") == "protected" -> "protected"
                entry.optLong("today_reviews") > 0 -> "short"
                else -> "pending"
            }
        }
    }
}

/** The same leaderboard without a background, drawn straight on the wallpaper. */
class AnkiquestTransparentWidget : AnkiquestWidget()
