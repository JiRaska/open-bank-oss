// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.settlement.domain.model.SettlementStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class SettlementResponseStatusTest {
    @Test
    fun `every response status is in the v1 OpenAPI vocabulary`() {
        val spec = javaClass.getResourceAsStream("/openapi.yaml")!!.use { Yaml().load<Map<String, Any>>(it) }
        val components = spec.getValue("components") as Map<*, *>
        val schemas = components.get("schemas") as Map<*, *>
        val response = schemas.get("SettlementResponse") as Map<*, *>
        val properties = response.get("properties") as Map<*, *>
        val status = properties.get("status") as Map<*, *>
        val vocabulary = (status["enum"] as List<*>).map { requireNotNull(it as? String) }
        val mapper = ObjectMapper()

        assertThat(SettlementResponseStatus.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(vocabulary)
        for (domain in SettlementStatus.entries) {
            val serialized = mapper.readTree(mapper.writeValueAsString(SettlementResponseStatus.fromDomain(domain)))
                .asText()
            assertThat(vocabulary).contains(serialized)
            if (domain == SettlementStatus.BALANCE_STATE_UNKNOWN) {
                assertThat(serialized).isEqualTo("PENDING")
            } else {
                assertThat(serialized).isEqualTo(domain.name)
            }
        }
    }
}
