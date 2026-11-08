// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.infrastructure.rest

import com.openbank.engagement.domain.model.game.GameCatalog
import com.openbank.engagement.domain.model.game.GameCourse
import com.openbank.engagement.domain.model.game.GameLevel
import com.openbank.engagement.domain.model.game.GameLevelState
import com.openbank.engagement.domain.model.game.GameRequirement
import java.time.Instant

/** Every untrusted nullable array element is rejected before entering the non-null domain. */
data class GameCatalogWriteRequest(val revision: Long, val levels: List<GameLevelWriteRequest?>) {
    fun toDomain(): GameCatalog = GameCatalog(
        revision,
        levels.mapIndexed { index, level -> requireNotNull(level) { "levels[$index] must not be null" }.toDomain() },
    )
}

data class GameLevelWriteRequest(
    val id: String,
    val theme: String,
    val order: Int,
    val titleCs: String,
    val titleEn: String,
    val state: GameLevelState,
    val requirement: GameRequirement = GameRequirement(),
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val content: GameCourseWriteRequest? = null,
) {
    fun toDomain(): GameLevel = GameLevel(
        id, theme, order, titleCs, titleEn, state, requirement, startsAt, endsAt, content?.toDomain(),
    )
}

data class GameCourseWriteRequest(
    val schema: Int = 1,
    val id: String,
    val theme: String,
    val ground: List<Map<String, Any?>?>,
    val platforms: List<Map<String, Any?>?>,
    val coins: List<Map<String, Any?>?>,
    val vampires: List<Map<String, Any?>?>,
    val bats: List<Map<String, Any?>?>,
    val piggies: List<Double?>,
    val shields: List<Map<String, Any?>?>,
    val vaultX: Double,
    val levelEnd: Double,
    val slopes: List<Map<String, Any?>?> = emptyList(),
    val thieves: List<Map<String, Any?>?> = emptyList(),
    val dropZones: List<Map<String, Any?>?> = emptyList(),
    val rooftopObjects: List<Map<String, Any?>?> = emptyList(),
    val pigeons: List<Map<String, Any?>?> = emptyList(),
    val pickpocketBoss: Boolean = false,
    val bridgePlatforms: List<Map<String, Any?>?> = emptyList(),
    val bridgeTraps: List<Map<String, Any?>?> = emptyList(),
    val paperworkBossX: Double? = null,
) {
    fun toDomain(): GameCourse = GameCourse(
        schema = schema,
        id = id,
        theme = theme,
        ground = ground.requiredItems("ground"),
        platforms = platforms.requiredItems("platforms"),
        coins = coins.requiredItems("coins"),
        vampires = vampires.requiredItems("vampires"),
        bats = bats.requiredItems("bats"),
        piggies = piggies.mapIndexed { index, value -> requireNotNull(value) { "piggies[$index] must not be null" } },
        shields = shields.requiredItems("shields"),
        vaultX = vaultX,
        levelEnd = levelEnd,
        slopes = slopes.requiredItems("slopes"),
        thieves = thieves.requiredItems("thieves"),
        dropZones = dropZones.requiredItems("dropZones"),
        rooftopObjects = rooftopObjects.requiredItems("rooftopObjects"),
        pigeons = pigeons.requiredItems("pigeons"),
        pickpocketBoss = pickpocketBoss,
        bridgePlatforms = bridgePlatforms.requiredItems("bridgePlatforms"),
        bridgeTraps = bridgeTraps.requiredItems("bridgeTraps"),
        paperworkBossX = paperworkBossX,
    )
}

private fun List<Map<String, Any?>?>.requiredItems(name: String): List<Map<String, Any>> = mapIndexed { index, item ->
    requireNotNull(item) { "$name[$index] must not be null" }.mapValues { (key, value) ->
        val present = requireNotNull(value) { "$name[$index].$key must not be null" }
        // The engine's direction is Int; JSON clients can spell the same integer as 1.0.
        if (name != "bridgeTraps" || key != "direction") return@mapValues present
        val direction = present as? Number ?: return@mapValues present
        if (direction.toDouble() in listOf(-1.0, 1.0)) direction.toInt() else present
    }
}
