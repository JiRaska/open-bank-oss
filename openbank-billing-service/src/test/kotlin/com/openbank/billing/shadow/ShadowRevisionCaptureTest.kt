// SPDX-License-Identifier: Apache-2.0
package com.openbank.billing.shadow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.billing.infrastructure.adapter.RestProductRevisionPort
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.billing.infrastructure.client.RevisionDto
import com.openbank.libs.product.FeeContext
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ShadowRevisionCaptureTest {
    private val mapper = jacksonObjectMapper()
    private val revisionId = UUID.randomUUID().toString()
    private val hash = ShadowFixtures.identity(revisionId).revisionHash

    private fun body(
        number: String = "7",
        behaviour: String = ShadowFixtures.IR,
        signature: String = """{"revisionHash":"$hash"}""",
    ) = """{"id":"$revisionId","state":"PUBLISHED","number":$number,"irSignature":$signature,
"content":{"attributes":{"accountingClass":"CURRENT_ACCOUNT"},"behaviour":$behaviour,
"prices":[{"code":"FEE_MAINT","kind":"AMOUNT","value":"4.90","currency":"EUR","cadence":"MONTHLY","role":"FEE"}]}}"""

    private suspend fun assess(json: String) = run {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(revisionId) } returns
            Uni.createFrom().item(mapper.readValue(json, RevisionDto::class.java))
        val accounts = mockk<AccountContextPort>()
        coEvery { accounts.resolve("account", "EUR") } returns
            AccountBilling("product", FeeContext(currency = "EUR"), Instant.parse("2025-01-01T00:00:00Z"))
        val pins = mockk<AccountRevisionPinPort>()
        coEvery { pins.find("account") } returns revisionId
        val meters = SimpleMeterRegistry()
        FeeAssessmentService(accounts, RestProductRevisionPort(catalog, meters), pins, meters)
            .assess("cycle", "account", "EUR")
    }

    @Test
    fun `assessment freezes the complete identity from the same authoritative fee terms`(): Unit = runBlocking {
        val assessment = assess(body())
        assertThat(assessment.skipped).isFalse()
        assertThat(assessment.assessedFees.single().chargedAmount).isEqualByComparingTo(BigDecimal("4.90"))
        assertThat(assessment.shadowInput!!.expectedRevision).isEqualTo(ShadowFixtures.identity(revisionId))
    }

    @Test
    fun `malformed optional shadow metadata never changes or skips actual legacy fees`(): Unit = runBlocking {
        val malformed = listOf(
            body(number = "\"unknown\""),
            body(number = "1.5"),
            body(number = "{}"),
            body(behaviour = "[]"),
            body(behaviour = "\"unknown\""),
            body(signature = "false"),
            body(signature = "{}"),
            body().replace("\"PUBLISHED\"", "\"DRAFT\""),
        )
        for (json in malformed) {
            val assessment = assess(json)
            assertThat(assessment.skipped).isFalse()
            assertThat(assessment.assessedFees.single().chargedAmount).isEqualByComparingTo("4.90")
            assertThat(assessment.shadowInput!!.expectedRevision).isNull()
        }
    }
}
