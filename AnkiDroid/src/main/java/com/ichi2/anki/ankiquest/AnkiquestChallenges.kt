// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Friend goals from `/api/community/challenges/{user}` (docs/mobile-api.md, "friend goals for
 * apps"): invitations, goals in progress and finished ones, the people the player can invite and
 * this week's suggested friend. Every change answers the whole list again.
 */
internal object AnkiquestChallenges {
    data class Member(
        val user: String,
        val display: String,
        /** `invited`, `accepted`, `declined` or `left`. */
        val status: String,
        val progress: Long,
    )

    data class Challenge(
        val id: Long,
        val title: String,
        val reviews: Boolean,
        val cooperative: Boolean,
        val creator: String,
        /** Unix milliseconds. */
        val startAt: Long,
        /** Unix milliseconds. */
        val endAt: Long,
        val target: Long,
        val members: List<Member>,
        val progress: Long,
        val status: String,
        val weekly: Boolean,
        val started: Boolean,
    ) {
        fun member(user: String): Member? = members.firstOrNull { it.user == user }

        /** Over, whatever the result; a reached goal stays open until its deadline, as on the website. */
        fun ended(now: Long): Boolean = status in setOf("ended", "cancelled", "declined") || now >= endAt

        /** Still waiting for [user]'s answer. */
        fun invites(
            user: String,
            now: Long,
        ): Boolean = !ended(now) && member(user)?.status == "invited"

        /** What counts toward [target] for [user]: everyone's sum together, else their own. */
        fun progressFor(user: String): Long = if (cooperative) progress else member(user)?.progress ?: 0
    }

    data class Recipient(
        val user: String,
        val display: String,
    )

    data class Suggestion(
        val weekStart: Long,
        val expiresAt: Long,
        val friend: Recipient,
        val dismissed: Boolean,
        val challengeId: Long?,
    )

    data class Overview(
        /** Newest first. */
        val challenges: List<Challenge>,
        val recipients: List<Recipient>,
        val suggestion: Suggestion?,
    )

    /** A new goal, as `POST /api/community/challenges/{user}` takes it. */
    data class NewGoal(
        val title: String,
        val reviews: Boolean,
        val cooperative: Boolean,
        val target: Long,
        val days: Int,
        val recipients: List<String>,
        val startWhenReady: Boolean,
    ) {
        /** Why the server would refuse this goal, checked the way it checks; null when it is fine. */
        fun problem(): Problem? =
            when {
                title.isBlank() -> Problem.NO_TITLE
                recipients.isEmpty() || recipients.size > MAX_RECIPIENTS -> Problem.RECIPIENTS
                days !in 1..MAX_DAYS || target !in 1..MAX_TARGET -> Problem.NUMBERS
                !reviews && target > days.toLong() * (if (cooperative) recipients.size + 1 else 1) -> Problem.TOO_MANY_DAYS
                else -> null
            }

        fun json(requestId: String): JSONObject =
            JSONObject()
                .put("title", title.trim())
                .put("kind", if (reviews) "reviews" else "study_days")
                .put("cooperative", cooperative)
                .put("target", target)
                .put("duration_days", days)
                .put("recipients", JSONArray(recipients))
                .put("start_when_ready", startWhenReady)
                .put("request_id", requestId)
    }

    enum class Problem { NO_TITLE, RECIPIENTS, NUMBERS, TOO_MANY_DAYS }

    const val MAX_TITLE = 80
    const val MAX_DAYS = 31
    const val MAX_TARGET = 100_000L
    const val MAX_RECIPIENTS = 30

    /** [account]'s friend goals. A server without them answers `404`, an [AnkiquestHttpException]. */
    suspend fun overview(account: HomeAccount): Overview =
        withContext(Dispatchers.IO) {
            parse(AnkiquestApi.json(account, AnkiquestApi.request(account, path(account)).build()))
        }

    /** Starts [goal]; a retry of the same goal reuses [requestId], so it is never created twice. */
    suspend fun create(
        account: HomeAccount,
        goal: NewGoal,
        requestId: String,
    ): Overview = post(account, path(account), goal.json(requestId))

    /** `accept`, `decline`, `leave` or `cancel` for goal [id]. */
    suspend fun act(
        account: HomeAccount,
        id: Long,
        action: String,
    ): Overview = post(account, "${path(account)}/$id", JSONObject().put("action", action))

    /** `invite` or `dismiss` for this week's suggested friend. */
    suspend fun weekly(
        account: HomeAccount,
        suggestion: Suggestion,
        action: String,
    ): Overview =
        post(
            account,
            "${path(account)}/weekly",
            JSONObject().put("week_start", suggestion.weekStart).put("action", action),
        )

    private suspend fun post(
        account: HomeAccount,
        path: String,
        body: JSONObject,
    ): Overview =
        withContext(Dispatchers.IO) {
            parse(AnkiquestApi.json(account, AnkiquestApi.request(account, path).post(AnkiquestApi.body(body)).build()))
        }

    private fun path(account: HomeAccount) = "api/community/challenges/${account.encodedUser}"

    fun parse(json: JSONObject): Overview =
        Overview(
            challenges =
                json
                    .optJSONArray("challenges")
                    ?.objects()
                    .orEmpty()
                    .mapNotNull(::challenge),
            recipients =
                json
                    .optJSONArray("recipients")
                    ?.objects()
                    .orEmpty()
                    .mapNotNull(::recipient),
            suggestion = json.optJSONObject("weekly_suggestion")?.let(::suggestion),
        )

    /** One goal, or null for one without a usable id. */
    fun challenge(json: JSONObject): Challenge? {
        val id = json.positive("id") ?: return null
        return Challenge(
            id = id,
            title = json.text("title"),
            reviews = json.text("kind") == "reviews",
            cooperative = json.optBoolean("cooperative"),
            creator = json.text("creator"),
            startAt = json.optLong("start_at"),
            endAt = json.optLong("end_at"),
            target = json.optLong("target"),
            members =
                json.optJSONArray("members")?.objects().orEmpty().mapNotNull { member ->
                    recipient(member)?.let { Member(it.user, it.display, member.text("status"), member.optLong("progress")) }
                },
            progress = json.optLong("progress"),
            status = json.text("status"),
            weekly = json.optBoolean("weekly"),
            started = json.optBoolean("started", true),
        )
    }

    private fun recipient(json: JSONObject): Recipient? {
        val user = json.text("user").ifEmpty { return null }
        return Recipient(user, json.text("display").ifEmpty { user })
    }

    private fun suggestion(json: JSONObject): Suggestion? =
        Suggestion(
            weekStart = json.optLong("week_start"),
            expiresAt = json.optLong("expires_at"),
            friend = json.optJSONObject("friend")?.let(::recipient) ?: return null,
            dismissed = json.optBoolean("dismissed"),
            challengeId = json.positive("challenge_id"),
        )

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONObject.positive(key: String): Long? = if (isNull(key)) null else optLong(key).takeIf { it > 0 }
}
