// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.domain.model.game

import com.openbank.engagement.domain.model.gamification.EarnSource
import java.time.Instant

enum class GameLevelState { OFF, BETA, LIVE }
enum class GameUnlockKind { NONE, PREVIOUS_LEVEL, EARN_SOURCE }

data class GameRequirement(
    val kind: GameUnlockKind = GameUnlockKind.NONE,
    val source: String? = null,
    val count: Int = 1,
    val hintCs: String = "",
    val hintEn: String = "",
) {
    init {
        require(count in 1..MAX_AWARDS && hintCs.length <= MAX_HINT && hintEn.length <= MAX_HINT)
        require(if (kind == GameUnlockKind.EARN_SOURCE) EarnSource.byId(source.orEmpty()) != null else source == null)
    }
}

data class GameLevel(
    val id: String,
    val theme: String,
    val order: Int,
    val titleCs: String,
    val titleEn: String,
    val state: GameLevelState,
    val requirement: GameRequirement = GameRequirement(),
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val content: GameCourse? = null,
) {
    init {
        require(id.matches(KEY) && theme.matches(KEY))
        require(
            order in 0..MAX_ORDER &&
                titleCs.isNotBlank() &&
                titleCs.length <= MAX_TITLE &&
                titleEn.isNotBlank() &&
                titleEn.length <= MAX_TITLE,
        )
        require(content == null || content.id == id && content.theme == theme)
        require(startsAt == null || endsAt == null || startsAt < endsAt)
    }

    fun visible(beta: Boolean, now: Instant): Boolean = state != GameLevelState.OFF &&
        (state != GameLevelState.BETA || beta) &&
        (startsAt == null || now >= startsAt) &&
        (endsAt == null || now < endsAt)

    private companion object {
        val KEY = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

data class GameCatalog(val revision: Long, val levels: List<GameLevel>) {
    /** A draft editor cannot publish, hide, delete, reorder or change existing LIVE content. */
    fun requireDraftEditOf(before: GameCatalog) {
        if (revision != before.revision) throw GameCatalogConflict()
        val liveBefore = before.levels.filter { it.state == GameLevelState.LIVE }.sortedBy { it.id }
            .map { it.copy(content = it.content?.comparisonValue()) }
        val liveAfter = levels.filter { it.state == GameLevelState.LIVE }.sortedBy { it.id }
            .map { it.copy(content = it.content?.comparisonValue()) }
        require(liveBefore == liveAfter) { "Published chapters require an administrator" }
    }

    init {
        require(revision >= 0 && levels.size in 1..MAX_LEVELS)
        require(levels.map { it.id }.distinct().size == levels.size)
        require(levels.map { it.order }.distinct().size == levels.size)
    }
}

data class CustomerGameLevel(val level: GameLevel, val unlocked: Boolean, val completed: Boolean)
data class CustomerGameCatalog(val revision: Long, val levels: List<CustomerGameLevel>)

/** Completion only unlocks play. It never writes an award, points, money or a banking decision. */
object GameAccess {
    fun evaluate(
        catalog: GameCatalog,
        completed: Set<String>,
        awards: Map<String, Long>,
        beta: Boolean,
        now: Instant,
    ): CustomerGameCatalog {
        val visible = catalog.levels.filter { it.visible(beta, now) }.sortedBy { it.order }
        return CustomerGameCatalog(
            catalog.revision,
            visible.mapIndexed { index, level ->
                val unlocked = level.id in completed ||
                    when (level.requirement.kind) {
                        GameUnlockKind.NONE -> true
                        GameUnlockKind.PREVIOUS_LEVEL -> index > 0 && visible[index - 1].id in completed
                        GameUnlockKind.EARN_SOURCE -> (awards[level.requirement.source] ?: 0) >= level.requirement.count
                    }
                CustomerGameLevel(level, unlocked, level.id in completed)
            },
        )
    }
}

class GameCatalogConflict : RuntimeException("The game catalogue changed; reload before saving")

private const val MAX_AWARDS = 1000
private const val MAX_HINT = 160
private const val MAX_ORDER = 99
private const val MAX_TITLE = 80
private const val MAX_LEVELS = 30
