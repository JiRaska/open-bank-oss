// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.domain.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.engagement.domain.model.game.GameCourse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class GameCourseTest {
    private val mapper = jacksonObjectMapper()
    private fun template(theme: String): GameCourse {
        val root = if (Files.isDirectory(Path.of("openbank-admin-ui"))) Path.of("") else Path.of("..")
        return mapper.readValue(Files.readString(root.resolve("openbank-admin-ui/public/lion-quest/$theme.json")))
    }

    @Test
    fun `real shipped courses deserialize and remain editable`() {
        for (theme in listOf("embankment", "rooftops", "charles_bridge")) {
            val course = template(theme)
            assertThat(course.theme).isEqualTo(theme)
            assertThat(course.coins).isNotEmpty()
            assertThat(mapper.readValue<GameCourse>(mapper.writeValueAsString(course))).isEqualTo(course)
        }
    }

    @Test
    fun `invalid floor finish or unbounded content cannot be published`() {
        val course = template("embankment")
        assertThrows<IllegalArgumentException> { course.copy(levelEnd = Double.NaN) }
        assertThrows<IllegalArgumentException> { course.copy(ground = emptyList()) }
        assertThrows<IllegalArgumentException> { course.copy(vaultX = course.levelEnd) }
        assertThrows<IllegalArgumentException> { course.copy(coins = List(201) { mapOf("x" to 100, "y" to 38) }) }
        assertThrows<IllegalArgumentException> { course.copy(coins = listOf(mapOf("x" to "bad", "y" to 38))) }
        assertThrows<IllegalArgumentException> {
            course.copy(
                coins = listOf(
                    mapOf(
                        "x" to 100,
                        "y" to 38,
                        "script" to mapOf("a" to 1),
                    ),
                ),
            )
        }
    }
}
