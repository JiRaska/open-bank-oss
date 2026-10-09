// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.tax.application.port.out.EpoRendererPort
import com.openbank.tax.application.usecase.TaxFilingService
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxFilingRecord
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class TaxEvidenceScopeContractTest {
    private val mapper = ObjectMapper()

    @Test
    fun `a historical monthly filed row remains labeled as monthly rather than annual`() {
        val period = FilingPeriod(2025, 12)
        val filing = TaxFilingRecord.open(period, "CZK")
            .assemble(BigDecimal("25.00"), 1, 1, "maker", Instant.parse("2026-01-02T00:00:00Z"))
            .markFiled("legacy-reference", "checker", Instant.parse("2026-01-03T00:00:00Z"))

        val response = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(filing.toResponse())

        assertThat(response.path("status").asText()).isEqualTo("FILED")
        assertThat(response.path("filingReference").asText()).isEqualTo("legacy-reference")
        assertThat(response.path("recordKind").asText()).isEqualTo(LEGACY_MONTHLY_RECORD_KIND)
    }

    @Test
    fun `an observed source batch does not claim cash settlement`() {
        val period = FilingPeriod(2025, 12)
        val observed = ObservedRemittance(
            remittanceId = UUID.randomUUID(),
            period = period,
            currency = "CZK",
            totalTaxAmount = BigDecimal("25.00"),
            itemCount = 1,
            dueDate = period.dueDate,
            observedAt = Instant.parse("2026-01-02T00:00:00Z"),
        )

        val response = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(observed.toResponse())

        assertThat(response.path("sourceStage").asText()).isEqualTo(ASSEMBLED_BATCH_SOURCE_STAGE)
    }

    @Test
    fun `export capability does not call monthly totals annual filing figures`() {
        val renderer = mockk<EpoRendererPort>()
        every { renderer.available } returns false
        val response = TaxFilingResource(mockk<TaxFilingService>(), renderer).exportCapability()
        val capability = response.entity as ExportCapabilityResponse

        assertThat(capability.note).contains("legacy monthly")
        assertThat(capability.note).contains("does not establish cash settlement or annual")
    }

    @Test
    fun `OpenAPI documents the monthly record and assembled batch markers`() {
        val document = Yaml().load<Map<String, Any>>(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
        val components = document["components"] as Map<*, *>
        val schemas = components["schemas"] as Map<*, *>
        val filing = schemas["TaxFilingResponse"] as Map<*, *>
        val remittance = schemas["ObservedRemittanceResponse"] as Map<*, *>
        val filingProperties = filing["properties"] as Map<*, *>
        val remittanceProperties = remittance["properties"] as Map<*, *>

        assertThat((filingProperties["recordKind"] as Map<*, *>)["enum"])
            .isEqualTo(listOf(LEGACY_MONTHLY_RECORD_KIND))
        assertThat((remittanceProperties["sourceStage"] as Map<*, *>)["enum"])
            .isEqualTo(listOf(ASSEMBLED_BATCH_SOURCE_STAGE))
    }
}
