// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.domain.game

import com.openbank.engagement.domain.model.game.GameAccess
import com.openbank.engagement.domain.model.game.GameCatalog
import com.openbank.engagement.domain.model.game.GameLevel
import com.openbank.engagement.domain.model.game.GameLevelState
import com.openbank.engagement.domain.model.game.GameRequirement
import com.openbank.engagement.domain.model.game.GameUnlockKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class GameAccessTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val first = GameLevel("embankment", "embankment", 0, "Náplavka", "Embankment", GameLevelState.LIVE)
    private val next = GameLevel(
        "rooftops",
        "rooftops",
        1,
        "Střechy",
        "Rooftops",
        GameLevelState.BETA,
        GameRequirement(GameUnlockKind.PREVIOUS_LEVEL),
    )

    @Test
    fun `beta off and season bounds control visibility in catalogue order`() {
        val off = first.copy(id = "off", order = 2, state = GameLevelState.OFF)
        val future = first.copy(id = "future", order = 3, startsAt = now.plusSeconds(1))
        val ended = first.copy(id = "ended", order = 4, endsAt = now)
        val catalog = GameCatalog(1, listOf(ended, next, off, first, future))
        assertThat(
            GameAccess.evaluate(catalog, emptySet(), emptyMap(), false, now).levels.map {
                it.level.id
            },
        ).containsExactly(first.id)
        assertThat(
            GameAccess.evaluate(catalog, emptySet(), emptyMap(), true, now).levels.map {
                it.level.id
            },
        ).containsExactly(first.id, next.id)
    }

    @Test
    fun `previous chapter needs a server completion and retained completion remains unlocked`() {
        val catalog = GameCatalog(0, listOf(first, next))
        assertThat(
            GameAccess.evaluate(catalog, emptySet(), emptyMap(), true, now).levels.map {
                it.unlocked
            },
        ).containsExactly(true, false)
        val completed = GameAccess.evaluate(catalog, setOf(first.id), emptyMap(), true, now)
        assertThat(completed.levels.map { it.unlocked }).containsExactly(true, true)
        assertThat(completed.levels.first().completed).isTrue()
        assertThat(
            GameAccess.evaluate(
                GameCatalog(0, listOf(next)),
                emptySet(),
                emptyMap(),
                true,
                now,
            ).levels.single().unlocked,
        ).isFalse()
    }

    @Test
    fun `unlock uses only recorded awards from the selected closed earn source`() {
        val source = "EDUCATIONAL_CONTENT_COMPLETION"
        val earned = next.copy(requirement = GameRequirement(GameUnlockKind.EARN_SOURCE, source, 2))
        val catalog = GameCatalog(0, listOf(first, earned))
        assertThat(
            GameAccess.evaluate(
                catalog,
                emptySet(),
                mapOf(source to 1L, "LOGIN_STREAK" to 99L),
                true,
                now,
            ).levels.last().unlocked,
        ).isFalse()
        assertThat(
            GameAccess.evaluate(catalog, emptySet(), mapOf(source to 2L), true, now).levels.last().unlocked,
        ).isTrue()
        assertThrows<IllegalArgumentException> { GameRequirement(GameUnlockKind.EARN_SOURCE, "CREDIT_UPTAKE") }
        assertThrows<IllegalArgumentException> { GameRequirement(GameUnlockKind.NONE, source) }
    }

    @Test
    fun `invalid identity duplicate ordering and empty season are rejected`() {
        assertThrows<IllegalArgumentException> { first.copy(id = "../other") }
        assertThrows<IllegalArgumentException> { first.copy(startsAt = now, endsAt = now) }
        assertThrows<IllegalArgumentException> { GameCatalog(0, listOf(first, first)) }
        assertThrows<IllegalArgumentException> { GameCatalog(0, listOf(first, next.copy(order = 0))) }
        assertThrows<IllegalArgumentException> { GameCatalog(-1, listOf(first)) }
    }

    @Test
    fun `marketing edits drafts without changing publishing state or any live content`() {
        val before = GameCatalog(5, listOf(first, next))
        before.copy(levels = listOf(first, next.copy(titleCs = "Nový scénář"))).requireDraftEditOf(before)
        assertThrows<IllegalArgumentException> {
            before.copy(levels = listOf(first.copy(titleCs = "Změna živého kola"), next)).requireDraftEditOf(before)
        }
        assertThrows<IllegalArgumentException> {
            before.copy(levels = listOf(first, next.copy(state = GameLevelState.LIVE))).requireDraftEditOf(before)
        }
        assertThrows<IllegalArgumentException> {
            before.copy(levels = listOf(next)).requireDraftEditOf(before)
        }
        assertThrows<com.openbank.engagement.domain.model.game.GameCatalogConflict> {
            before.copy(revision = 4).requireDraftEditOf(before)
        }
    }
}
