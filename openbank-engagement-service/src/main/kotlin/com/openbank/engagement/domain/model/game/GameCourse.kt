// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.domain.model.game

/** Versioned data only. The app also validates with its engine before replacing a known good course. */
data class GameCourse(
    val schema: Int = 1,
    val id: String,
    val theme: String,
    val ground: List<Map<String, Any>>,
    val platforms: List<Map<String, Any>>,
    val coins: List<Map<String, Any>>,
    val vampires: List<Map<String, Any>>,
    val bats: List<Map<String, Any>>,
    val piggies: List<Double>,
    val shields: List<Map<String, Any>>,
    val vaultX: Double,
    val levelEnd: Double,
    val slopes: List<Map<String, Any>> = emptyList(),
    val thieves: List<Map<String, Any>> = emptyList(),
    val dropZones: List<Map<String, Any>> = emptyList(),
    val rooftopObjects: List<Map<String, Any>> = emptyList(),
    val pigeons: List<Map<String, Any>> = emptyList(),
    val pickpocketBoss: Boolean = false,
    val bridgePlatforms: List<Map<String, Any>> = emptyList(),
    val bridgeTraps: List<Map<String, Any>> = emptyList(),
    val paperworkBossX: Double? = null,
) {
    init {
        require(schema == 1 && theme in setOf("embankment", "rooftops", "charles_bridge"))
        require(id.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(levelEnd.isFinite() && levelEnd in MIN_LENGTH..MAX_COORD)
        require(vaultX.isFinite() && vaultX in 0.0..<levelEnd)
        val lists =
            listOf(
                ground, platforms, coins, vampires, bats, shields, slopes, thieves, dropZones,
                rooftopObjects, pigeons, bridgePlatforms, bridgeTraps,
            )
        require(lists.all { it.size <= MAX_ITEMS } && piggies.size <= MAX_ITEMS)
        require(
            lists.flatten().all { item ->
                item.size <= MAX_FIELDS &&
                    item.values.all { value ->
                        when (value) {
                            is Number -> value.toDouble().isFinite() && value.toDouble() in -MAX_COORD..MAX_COORD
                            is Boolean -> true
                            is String -> value.length <= MAX_TOKEN
                            else -> false
                        }
                    }
            },
        )
        require(piggies.all { it.isFinite() && it in 0.0..levelEnd })
        require(ground.isNotEmpty() && ground.first().number("x0") <= START_X && ground.last().number("x1") >= levelEnd)
        require(ground.all { it.number("x1") > it.number("x0") })
        require(
            ground.zipWithNext().all { (a, b) ->
                val gap = b.number("x0") - a.number("x1")
                if (theme == "charles_bridge") gap in MIN_BRIDGE_GAP..MAX_BRIDGE_GAP else gap == GAP
            },
        )
        require(ground.any { it.number("x0") <= vaultX - VAULT_FOOTING && it.number("x1") >= vaultX + VAULT_FOOTING })
        require(coins.isNotEmpty() && coins.any { it["fake"] != true })
        coins.forEach {
            it.point()
            require(it["fake"] == null || it["fake"] is Boolean)
        }
        vampires.forEach {
            it.point()
            require(it["boss"] == null || it["boss"] is Boolean)
        }
        require(vampires.count { it["boss"] == true } <= 1)
        require(vampires.none { it["boss"] == true && it.number("x") >= vaultX })
        platforms.forEach {
            it.point()
            require(it.number("w") > 0 && it.number("y") > 0)
        }
        bats.forEach {
            it.number("cx")
            it.number("cy")
            require(it.number("span") > 0)
        }
        (shields + thieves + dropZones + pigeons).forEach { it.point() }
        require(piggies.all { x -> ground.any { x in it.number("x0")..it.number("x1") } })
        require(
            thieves.all { t ->
                (t["y"]?.let { t.number("y") } ?: 0.0) == 0.0 &&
                    ground.any { t.number("x") in it.number("x0") + THIEF_MARGIN..it.number("x1") - THIEF_MARGIN }
            },
        )
        require(dropZones.all { it.number("y") > 0 })
        slopes.forEach { s ->
            val dx = s.number("x1") - s.number("x0")
            require(dx > 0 && s.number("y0") >= 0 && s.number("y1") >= 0)
            require(kotlin.math.abs((s.number("y1") - s.number("y0")) / dx) <= MAX_ROOF_GRADE)
        }
        rooftopObjects.forEach { o ->
            val x = o.number("x")
            val y = o.number("y")
            val w = o.number("w")
            require(o["kind"] in setOf("BALCONY", "CHIMNEY", "LAUNDRY", "CRUMBLE", "DORMER"))
            require(y >= 0 && o.optionalNumber("baseY", 0.0) <= y && x >= 0 && w > 0 && x + w <= levelEnd)
            require(ground.any { x >= it.number("x0") && x + w <= it.number("x1") })
            if (o["kind"] == "CRUMBLE") require(slopes.none { it.number("x0") < x + w && it.number("x1") > x })
        }
        require(
            theme == "charles_bridge" || bridgePlatforms.isEmpty() && bridgeTraps.isEmpty() && paperworkBossX == null,
        )
        bridgePlatforms.forEach { p ->
            val x = p.number("x")
            val y = p.number("y")
            val w = p.number("w")
            val dx = p.optionalNumber("dx", 0.0)
            val dy = p.optionalNumber("dy", 0.0)
            require(minOf(x, x + dx) >= 0 && maxOf(x, x + dx) + w <= levelEnd)
            require(minOf(y, y + dy) >= MIN_BRIDGE_HEIGHT && maxOf(y, y + dy) <= MAX_BRIDGE_HEIGHT)
            require(kotlin.math.abs(dx) <= MAX_BRIDGE_TRAVEL_X && kotlin.math.abs(dy) <= MAX_BRIDGE_TRAVEL_Y)
            require(p.optionalNumber("period", DEFAULT_BRIDGE_PERIOD) in MIN_BRIDGE_PERIOD..MAX_BRIDGE_PERIOD)
            require(w in MIN_BRIDGE_WIDTH..MAX_BRIDGE_WIDTH && p.optionalNumber("offset", 0.0) in 0.0..1.0)
            require(p.optionalNumber("collapseAfter", 0.0) in 0.0..MAX_COLLAPSE_DELAY)
        }
        bridgeTraps.forEach { p ->
            val x = p.number("x")
            require(x in 0.0..levelEnd && ground.any { x in it.number("x0")..it.number("x1") })
            require(p.optionalNumber("direction", 1.0) in listOf(-1.0, 1.0))
        }
        paperworkBossX?.let { x ->
            require(x.isFinite() && x in MIN_BOSS_POSITION..vaultX - BOSS_FINISH_MARGIN)
            require(ground.any { x - BOSS_FOOT_LEFT >= it.number("x0") && x + BOSS_FOOT_RIGHT <= it.number("x1") })
            require(vampires.none { it["boss"] == true })
        }
        require(!pickpocketBoss || theme == "rooftops" && vampires.count { it["boss"] == true } == 1)
    }
}

/** JSON integral/floating spellings must not make an unchanged LIVE course look edited. */
internal fun GameCourse.comparisonValue(): GameCourse = copy(
    ground = ground.comparisonValues(),
    platforms = platforms.comparisonValues(),
    coins = coins.comparisonValues(),
    vampires = vampires.comparisonValues(),
    bats = bats.comparisonValues(),
    shields = shields.comparisonValues(),
    slopes = slopes.comparisonValues(),
    thieves = thieves.comparisonValues(),
    dropZones = dropZones.comparisonValues(),
    rooftopObjects = rooftopObjects.comparisonValues(),
    pigeons = pigeons.comparisonValues(),
    bridgePlatforms = bridgePlatforms.comparisonValues(),
    bridgeTraps = bridgeTraps.comparisonValues(),
)

private fun List<Map<String, Any>>.comparisonValues(): List<Map<String, Any>> = map { item ->
    item.mapValues { (_, value) -> if (value is Number) value.toDouble() else value }
}

private fun Map<String, Any>.number(key: String): Double = requireNotNull(this[key] as? Number) {
    "Missing numeric $key"
}.toDouble()
private fun Map<String, Any>.optionalNumber(key: String, default: Double): Double =
    if (key in this) number(key) else default

private fun Map<String, Any>.point() {
    number("x")
    if ("y" in this) number("y")
}
private const val MAX_ITEMS = 200
private const val MAX_FIELDS = 16
private const val MAX_TOKEN = 64
private const val MAX_COORD = 50_000.0
private const val MIN_LENGTH = 1_000.0
private const val START_X = 80.0
private const val GAP = 140.0
private const val MIN_BRIDGE_GAP = 140.0
private const val MAX_BRIDGE_GAP = 600.0
private const val VAULT_FOOTING = 60.0

private const val THIEF_MARGIN = 20.0
private const val MAX_ROOF_GRADE = 0.5
private const val MIN_BRIDGE_HEIGHT = -200.0
private const val MAX_BRIDGE_HEIGHT = 300.0
private const val MAX_BRIDGE_TRAVEL_X = 500.0
private const val MAX_BRIDGE_TRAVEL_Y = 200.0
private const val DEFAULT_BRIDGE_PERIOD = 4.0
private const val MIN_BRIDGE_PERIOD = 2.0
private const val MAX_BRIDGE_PERIOD = 20.0
private const val MIN_BRIDGE_WIDTH = 60.0
private const val MAX_BRIDGE_WIDTH = 400.0
private const val MAX_COLLAPSE_DELAY = 2.0
private const val MIN_BOSS_POSITION = 700.0
private const val BOSS_FINISH_MARGIN = 100.0
private const val BOSS_FOOT_LEFT = 450.0
private const val BOSS_FOOT_RIGHT = 220.0
