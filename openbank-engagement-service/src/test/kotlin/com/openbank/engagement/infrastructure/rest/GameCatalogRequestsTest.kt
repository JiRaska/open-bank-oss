// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GameCatalogRequestsTest {
    @Test
    fun `whole direction numbers are emitted as integers for the native engine`() {
        val mapper = jacksonObjectMapper()
        val root = if (Files.isDirectory(Path.of("openbank-admin-ui"))) Path.of("") else Path.of("..")
        val node = mapper.readTree(
            Files.readString(root.resolve("openbank-admin-ui/public/lion-quest/charles_bridge.json")),
        )
        (node.path("bridgeTraps")[0] as ObjectNode).put("direction", -1.0)
        val request = mapper.treeToValue(node, GameCourseWriteRequest::class.java)
        val response = mapper.valueToTree<JsonNode>(request.toDomain())
        val direction = response.path("bridgeTraps")[0].path("direction")
        assertThat(direction.isIntegralNumber).isTrue()
        assertThat(direction.asInt()).isEqualTo(-1)
    }
}
