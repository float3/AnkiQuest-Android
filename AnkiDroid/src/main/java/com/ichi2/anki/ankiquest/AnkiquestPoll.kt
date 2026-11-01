// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads pending reviews and fetches the leaderboard and profile in the background, redraws the
 * widget and raises rank and streak notifications. Runs every 15 minutes and on demand.
 */
object AnkiquestPoll {
    private const val PERIODIC_NAME = "ankiquestPoll"
    private const val NOW_NAME = "ankiquestPollNow"

    @VisibleForTesting
    internal const val PREF_CACHE = "ankiquestWidgetLeaderboard"

    @VisibleForTesting
    internal const val PREF_CACHE_AT = "ankiquestWidgetLeaderboardAt"

    fun schedule(context: Context) {
        try {
            val request =
                PeriodicWorkRequestBuilder<AnkiquestPollWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not schedule polling")
        }
    }

    fun refreshNow(context: Context) {
        try {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AnkiquestPollWorker>().build())
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not refresh")
        }
    }

    /**
     * Reviews not yet on the server are uploaded first, so the scores fetched next include them.
     * Presentation failures must not skip the private inbox; transient inbox failures need a retry.
     * Optional pictures come after the inbox, so unavailable photos cannot delay messages.
     */
    suspend fun run(context: Context): Result {
        Ankiquest.uploadPending()
        val prefs = AnkiDroidApp.sharedPrefs()
        val configured = Ankiquest.dashboardUrl() != null
        val cacheScope = AnkiquestHomeData.account(prefs)?.scope
        val cacheKey = scopedKey(PREF_CACHE, cacheScope)
        val cacheAtKey = scopedKey(PREF_CACHE_AT, cacheScope)
        val photoAccount = runCatching { AnkiquestAvatars.account() }.getOrNull()
        val photoScope = photoAccount?.scope
        var photoBoard: JSONArray? = null
        var photoFetchedAt = 0L
        var photoOffline = false
        try {
            if (!configured) {
                AnkiquestWidget.render(context, null, 0, offline = false)
            } else {
                val board =
                    try {
                        Ankiquest.leaderboard()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "ankiquest leaderboard fetch failed")
                        null
                    }
                if (board != null) {
                    val now = TimeManager.time.intTimeMS()
                    prefs.edit {
                        putString(cacheKey, board.toString())
                        putLong(cacheAtKey, now)
                    }
                    AnkiquestWidget.render(context, board, now, offline = false)
                    photoBoard = board
                    photoFetchedAt = now
                    AnkiquestNotifier.onLeaderboard(context)
                } else {
                    val cached = prefs.getString(cacheKey, null)?.let { JSONArray(it) } ?: JSONArray()
                    AnkiquestWidget.render(context, cached, prefs.getLong(cacheAtKey, 0), offline = true)
                    photoBoard = cached
                    photoFetchedAt = prefs.getLong(cacheAtKey, 0)
                    photoOffline = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest leaderboard update failed")
        }
        if (!configured) return Result.success()
        try {
            AnkiquestNotifier.onProfile(context, Ankiquest.profile())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest profile fetch failed")
        }
        val result =
            try {
                Ankiquest.completionNotifications()?.let { (account, notifications, scope) ->
                    AnkiquestNotificationHealth.recordSuccessfulSync(scope, TimeManager.time.intTimeMS(), prefs)
                    AnkiquestNotifier.onDeckCompletions(context, account, notifications, scope)
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest completion inbox fetch failed")
                val transient =
                    e is IOException &&
                        (e !is AnkiquestHttpException || e.code == 408 || e.code == 429 || e.code in 500..599)
                if (transient) Result.retry() else Result.success()
            }
        photoBoard?.let { board ->
            val stillCurrent = {
                photoScope != null &&
                    photoScope == runCatching { AnkiquestAvatars.account()?.scope }.getOrNull() &&
                    prefs.getLong(cacheAtKey, 0) == photoFetchedAt &&
                    prefs.getString(cacheKey, null) == board.toString()
            }
            if (!stillCurrent()) return@let
            try {
                AnkiquestAvatars.refresh(
                    board.objects().map { it.getString("user") },
                    photoAccount,
                )
                if (stillCurrent()) AnkiquestWidget.render(context, board, photoFetchedAt, photoOffline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "ankiquest optional photos could not refresh")
            }
        }
        return result
    }
}

class AnkiquestPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = AnkiquestPoll.run(applicationContext)
}
