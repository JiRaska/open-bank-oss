// SPDX-License-Identifier: Apache-2.0
package com.openbank.billing.shadow

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.billing.application.usecase.FeeShadowComparator
import com.openbank.billing.application.usecase.FeeShadowResult
import com.openbank.billing.infrastructure.adapter.LegacyFees
import com.openbank.billing.infrastructure.adapter.RestProductRevisionPort
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.billing.infrastructure.client.RevisionDto
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.products.catalog.ReferenceProducts
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

    private suspend fun assess(json: String, balance: BigDecimal? = null, cadence: String? = null) = run {
        val catalog = mockk<ProductCatalogRestClient>()
        every { catalog.getRevision(revisionId) } returns
            Uni.createFrom().item(mapper.readValue(json, RevisionDto::class.java))
        val accounts = mockk<AccountContextPort>()
        coEvery { accounts.resolve("account", "EUR") } returns
            AccountBilling("product", FeeContext(currency = "EUR", balance = balance), Instant.parse("2025-01-01T00:00:00Z"))
        val pins = mockk<AccountRevisionPinPort>()
        coEvery { pins.find("account") } returns revisionId
        val meters = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
        FeeAssessmentService(accounts, RestProductRevisionPort(catalog, meters), pins, meters, clock)
            .assess("cycle", "account", "EUR", cadence)
    }

    @Test
    fun `assessment freezes the complete identity from the same authoritative fee terms`(): Unit = runBlocking {
        val assessment = assess(body())
        assertThat(assessment.skipped).isFalse()
        assertThat(assessment.assessedFees.single().chargedAmount).isEqualByComparingTo(BigDecimal("4.90"))
        assertThat(assessment.shadowInput!!.expectedRevision).isEqualTo(ShadowFixtures.identity(revisionId))
    }

    @Test
    fun `pinned fixed savings assessment requires an exact schedule trigger for shadow parity`(): Unit = runBlocking {
        val revision = ReferenceProducts.savingsFixed.compile()
        val response = mapper.readTree(
            body(
                behaviour = ProductIrCanonical.canonicalJson(revision),
                signature = """{"revisionHash":"${revision.revisionHash}"}""",
            ),
        )
        val attributes = response.at("/content/attributes") as ObjectNode
        attributes.put("accountingClass", "SAVINGS_ACCOUNT")
        attributes.put(
            "legacyDocument",
            """{"fees":[{"id":"MAINT","type":"MONTHLY","waivable":true,"waiveCondition":"Balance >= 5000 EUR"}]}""",
        )
        val price = response.at("/content/prices/0") as ObjectNode
        price.put("value", "2.00")
        price.put("code", LegacyFees.priceCode("MAINT"))
        val revisionJson = mapper.writeValueAsString(response)
        val comparator = FeeShadowComparator(ProductInterpreter(FinanceProductArithmetic))
        for ((balance, charge) in listOf("4999.99" to "2.00", "5000.00" to "0.00")) {
            val assessment = assess(revisionJson, BigDecimal(balance), "MONTHLY")
            assertThat(assessment.skipped).isFalse()
            assertThat(assessment.assessedFees.single().chargedAmount).isEqualByComparingTo(charge)
            val input = requireNotNull(assessment.shadowInput)
            assertThat(input.expectedRevision?.revisionHash).isEqualTo(revision.revisionHash)
            assertThat(comparator.compare(revision, input)).containsExactly(FeeShadowResult.UnknownInput("MAINT"))
            val comparison = comparator.compare(revision, input.copy(scheduledTrigger = "FEE_MAINT"))
                .single() as FeeShadowResult.Compared
            assertThat(comparison.diverged).isFalse()
            assertThat(comparison.irCharged).isEqualByComparingTo(charge)
        }
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
