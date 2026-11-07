// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.product.FeeContext
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The observable distributed shape of the fee-assessment money write.
 *
 * `POST /api/v1/fees/post` persists three rows in one transaction — the
 * `billing_cycle_assessment`, one `assessed_fee`, and the `billing_outbox` `fee.post-intent` row
 * that IS the instruction to charge the customer in the ledger (see [BillingOutboxAtomicityIT] for
 * why the outbox row is money-bearing). The spans asserted here are the service's OWN: Quarkus'
 * OpenTelemetry auto-instrumentation produces them for the real HTTP request and the real Postgres
 * writes; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can read
 * what an OTLP collector would receive. Nothing asserted here is created by the test, so every
 * assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says all three INSERTs happened *inside*
 * the request's trace. A write that escaped the request context — an un-propagated reactive hop, a
 * fire-and-forget executor — still writes the rows and still answers 200, is invisible to every
 * presence assertion this module already had, and lands in a trace of its own. For billing that is
 * worse than for a read model: the escaped row is a charge instruction, so a trace that splits is
 * a charge no operator can reconstruct from telemetry.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); the fee post is billing's most important write, so it
 * is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(BillingTraceContractIT.BillingTraceProfile::class)
class BillingTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off); the two
     * additions are billing-specific: the resolving read-side stubs that put the chargeable write
     * on the wire, and port 0 — billing's `%test` leaves `quarkus.http.test-port` at the fixed
     * default 8081, and a profile's own boot re-binds that port before the outgoing instance has
     * released it (`QuarkusBindException`, measured in [BillingOutboxAtomicityIT]).
     */
    class BillingTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "0",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(ResolvingAccountContext::class.java, OneChargeableFeeCatalog::class.java)
    }

    /** account-service + balance-service stand-in: always resolves, so nothing is skipped. */
    @Alternative
    @ApplicationScoped
    class ResolvingAccountContext : AccountContextPort {
        override suspend fun resolve(accountId: String, currency: String) = AccountBilling(
            productId = PRODUCT_ID,
            context = FeeContext(balance = BigDecimal("10.00"), currency = currency),
            openedAt = java.time.Instant.parse("2026-01-15T10:00:00Z"),
        )
    }

    /**
     * One non-waivable fee, so exactly one `assessed_fee` row and one post-intent outbox row are
     * written — the trace contract names every row kind of the write without count ambiguity.
     */
    @Alternative
    @ApplicationScoped
    class OneChargeableFeeCatalog : ProductRevisionPort {
        override suspend fun publishedRevisionIdFor(productId: String, effectiveAt: java.time.Instant?): String =
            REVISION_ID

        override suspend fun terms(revisionId: String, currency: String) = RevisionTerms(
            revisionId = revisionId,
            accountingClass = "CUSTOMER_DEPOSIT_DEMAND",
            accountingClassSource = AccountingClassSource.REVISION,
            fees = listOf(
                BillableFee(
                    feeId = FEE_ID,
                    name = "Monthly maintenance",
                    type = "MAINTENANCE",
                    amount = BigDecimal("150.00"),
                    currency = currency,
                    waivable = false,
                    waiveCondition = null,
                ),
            ),
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `assessing and posting a fee keeps the request and all three money rows in one trace`() {
        val cycleId = "it-trace-${System.nanoTime()}"
        val accountId = "acc-trace-${UUID.randomUUID()}"

        val posted = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }
        // Arrangement assertion: billing fails CLOSED, so an unresolvable account or catalog yields
        // a `skipped` assessment with no fee and no outbox rows — a state in which every assertion
        // below would pass vacuously over spans of a write that never happened.
        assertThat(posted.extract().path<Boolean>("skipped"))
            .describedAs("the stubs really resolved, so the money write under test was reached")
            .isFalse()

        exporter.contract()
            .requiresSpan("POST /api/v1/fees/post")
            .requiresAttribute("POST /api/v1/fees/post", "http.response.status_code")
            .requiresAttribute("INSERT billing_cycle_assessment", "db.sql.table")
            .requiresAttribute("INSERT assessed_fee", "db.sql.table")
            .requiresAttribute("INSERT billing_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/fees/post",
                "INSERT billing_cycle_assessment",
                "INSERT assessed_fee",
                "INSERT billing_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("billing-fee-post")
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
        const val PRODUCT_ID = "CURRENT_CZK"
        const val FEE_ID = "maintenance-monthly"
        const val REVISION_ID = "0191c0de-0000-7000-8000-0000000000a1"
        const val CURRENCY = "CZK"
    }
}
