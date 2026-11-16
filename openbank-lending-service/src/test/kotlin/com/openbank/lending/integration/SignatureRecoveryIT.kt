// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.CatalogFosLoanPort
import com.openbank.lending.application.port.out.CatalogLoanProfile
import com.openbank.lending.application.port.out.CatalogLoanProfilePort
import com.openbank.lending.application.port.out.FosAction
import com.openbank.lending.application.port.out.FosRoute
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.market.OfferingMarkets
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class SignatureRecoveryIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = SIGNER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `the scheduler recovers a staged signature after the application transaction rolls back`() {
        val application = seedApplication()
        execute(
            "ALTER TABLE lending_outbox ADD CONSTRAINT test_signature_interruption " +
                "CHECK (aggregate_id <> '$application'::uuid)",
        )
        try {
            assertThat(sign(application, ARTEFACT)).isGreaterThanOrEqualTo(400)
            assertThat(scalar("SELECT status::text FROM loan_application WHERE id = ?", application))
                .isEqualTo("AWAITING_SIGNATURE")
            val intentSql = "SELECT artefact_hash FROM lending_signature_intent WHERE application_id = ?"
            assertThat(scalar(intentSql, application)).isEqualTo(ARTEFACT)
        } finally {
            execute("ALTER TABLE lending_outbox DROP CONSTRAINT test_signature_interruption")
        }
        execute(
            "UPDATE lending_signature_intent SET next_attempt_at = clock_timestamp() WHERE application_id = ?",
            application,
        )

        awaitSignature(application)
        assertThat(outboxCount(application)).isEqualTo(1L)
        assertAtomicCompletion(application)
        assertThat(sign(application, ARTEFACT)).isEqualTo(200)
        assertThat(sign(application, "bb".repeat(32))).isEqualTo(422)
        assertThat(outboxCount(application)).isEqualTo(1L)
    }

    @Test
    @TestSecurity(user = SIGNER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `FOS recovers separate signing and cash operations using their original requests`() {
        val payments = installCashBackends()
        val receipts = installCatalogue()
        val application = createFosApplication()
        moveToSignature(application)
        assertThat(sign(application, ARTEFACT)).isGreaterThanOrEqualTo(400)
        assertThat(status(application)).isEqualTo("AWAITING_SIGNATURE")
        recoverFos(application, FosAction.SIGN)
        assertThat(status(application)).isEqualTo("SIGNED")
        assertThat(receipts.values).containsExactly(FosAction.SIGN)

        execute("UPDATE loan_application SET status = 'READY_TO_DISBURSE' WHERE id = ?", application)
        assertThat(disburse(application)).isEqualTo(409)
        assertThat(status(application)).isEqualTo("DISBURSED")
        recoverFos(application, FosAction.DISBURSE)
        assertThat(disburse(application)).isEqualTo(201)
        assertThat(receipts.values).containsExactlyInAnyOrder(FosAction.SIGN, FosAction.DISBURSE)
        assertThat(payments).hasSize(1)
        assertThat(scalar("SELECT count(*) FROM loan WHERE application_id = ?", application)).isEqualTo(1L)
        assertThat(outboxCount(application)).isEqualTo(1L)
        assertThat(
            scalar(
                """
                SELECT count(*) FROM lending_outbox WHERE event_type = 'loan.disbursed'
                    AND aggregate_id IN (SELECT id FROM loan WHERE application_id = ?)
                """.trimIndent(),
                application,
            ),
        ).isEqualTo(1L)
    }

    private fun installCatalogue(): Map<UUID, FosAction> {
        val receipts = ConcurrentHashMap<UUID, FosAction>()
        val lostSignatureResponse = AtomicBoolean()
        val catalogue = mockk<CatalogFosLoanPort>()
        every { catalogue.resolve(GRANT, PARTY, REVISION) } returns Uni.createFrom().item(ROUTE)
        every { catalogue.admit(any(), any(), PARTY, REVISION) } answers {
            val operation = firstArg<com.openbank.lending.application.port.out.FosOperation>()
            check(secondArg<com.openbank.lending.application.port.out.FosRoutePin>().grantId == GRANT)
            check(receipts.putIfAbsent(operation.id, operation.action).let { it == null || it == operation.action })
            if (operation.action == FosAction.SIGN && lostSignatureResponse.compareAndSet(false, true)) {
                Uni.createFrom().failure(IllegalStateException("synthetic lost catalogue response"))
            } else {
                Uni.createFrom().item(Unit)
            }
        }
        QuarkusMock.installMockForType(catalogue, CatalogFosLoanPort::class.java)
        val profiles = mockk<CatalogLoanProfilePort>()
        every { profiles.resolvePublished(REVISION.offeringId) } returns Uni.createFrom().item(
            CatalogLoanProfile(
                REVISION,
                "CZK",
                12,
                AmortizationMethod.ANNUITY,
                BigDecimal("0.05"),
                null,
                null,
                OfferingMarkets(setOf("SK")),
                "INSTALLMENT_LOAN",
            ),
        )
        QuarkusMock.installMockForType(profiles, CatalogLoanProfilePort::class.java)
        val guard = mockk<CompliancePackGuard>(relaxed = true)
        every { guard.resolveOriginationPack("SK", "CONSUMER_CREDIT") } returns mockk(relaxed = true)
        QuarkusMock.installMockForType(guard, CompliancePackGuard::class.java)
        return receipts
    }

    private fun installCashBackends(): Map<String, LocalDate> {
        val payments = ConcurrentHashMap<String, LocalDate>()
        val accounts = mockk<BorrowerAccountLookupPort>()
        every { accounts.findCurrentAccount(PARTY, "CZK") } returns Uni.createFrom().item(ACCOUNT)
        QuarkusMock.installMockForType(accounts, BorrowerAccountLookupPort::class.java)
        val ledger = mockk<LedgerPostingPort>()
        every { ledger.postConfirmedDisbursement(any()) } returns Uni.createFrom().item(Unit)
        QuarkusMock.installMockForType(ledger, LedgerPostingPort::class.java)
        val credit = mockk<BorrowerCreditPort>()
        every { credit.creditOn(any(), ACCOUNT, any(), any()) } answers {
            val date = arg<LocalDate>(3)
            val prior = payments.putIfAbsent(firstArg(), date)
            if (prior == null) {
                Uni.createFrom().failure(IllegalStateException("synthetic lost credit response"))
            } else {
                check(prior == date) { "Credit replay changed the value date" }
                Uni.createFrom().item(Unit)
            }
        }
        QuarkusMock.installMockForType(credit, BorrowerCreditPort::class.java)
        return payments
    }

    private fun createFosApplication(): UUID {
        val response = given().contentType("application/json").body(
            mapOf(
                "partyId" to PARTY,
                "requestedAmount" to mapOf("amount" to "10000.00", "currency" to mapOf("code" to "CZK")),
                "nominalAnnualRate" to "0.05",
                "termPeriods" to 12,
                "firstDueDate" to LocalDate.now().plusMonths(1).toString(),
                "catalogOfferingId" to REVISION.offeringId,
                "passportGrantId" to GRANT,
            ),
        ).post("/api/v1/lending/applications").then().statusCode(201)
        val id = UUID.fromString(response.extract().jsonPath().getString("id"))
        assertThat(scalar("SELECT jurisdiction FROM loan_application WHERE id = ?", id)).isEqualTo("SK")
        val route = scalar("SELECT CAST(route AS text) FROM lending_fos_route WHERE application_id = ?", id)
        assertThat(ObjectMapper().readTree(route as String).path("grantId").asText()).isEqualTo(GRANT.toString())
        return id
    }

    private fun moveToSignature(application: UUID) {
        execute(
            """
            UPDATE loan_application SET status = 'AWAITING_SIGNATURE', proposed_by = 'synthetic-maker',
                decided_by = 'synthetic-checker', decided_at = CURRENT_TIMESTAMP WHERE id = ?
            """.trimIndent(),
            application,
        )
    }

    private fun status(application: UUID) =
        scalar("SELECT status::text FROM loan_application WHERE id = ?", application)

    private fun disburse(application: UUID): Int =
        given().post("/api/v1/lending/applications/$application/disburse").statusCode

    private fun recoverFos(application: UUID, action: FosAction) {
        execute(
            "UPDATE lending_fos_operation SET next_attempt_at = clock_timestamp() " +
                "WHERE application_id = ? AND action = ?",
            application,
            action.name,
        )
        val sql = """
            SELECT completed_at IS NOT NULL FROM lending_fos_operation
            WHERE application_id = ? AND action = '${action.name}'
        """.trimIndent()
        awaitCompletion(sql, application)
    }

    private fun sign(application: UUID, artefactHash: String): Int = given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(mapOf("artefactHash" to artefactHash))
        .post("/api/v1/lending/applications/$application/sign")
        .statusCode

    private fun awaitSignature(application: UUID) {
        val sql = "SELECT completed_at IS NOT NULL FROM lending_signature_intent WHERE application_id = ?"
        awaitCompletion(sql, application)
        assertThat(scalar("SELECT status::text FROM loan_application WHERE id = ?", application)).isEqualTo("SIGNED")
    }

    private fun awaitCompletion(sql: String, application: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos()
        while (scalar(sql, application) != true && System.nanoTime() < deadline) Thread.sleep(50)
        assertThat(scalar(sql, application)).isEqualTo(true)
    }

    private fun assertAtomicCompletion(application: UUID) {
        val sql = """
            SELECT i.xmin = a.xmin AND o.xmin = a.xmin FROM lending_signature_intent i
            JOIN loan_application a ON a.id = i.application_id
            JOIN lending_outbox o ON o.aggregate_id = a.id
            WHERE a.id = ? AND o.event_type = 'credit.application.transition'
                AND o.payload::jsonb ->> 'toState' = 'SIGNED'
        """.trimIndent()
        assertThat(scalar(sql, application)).isEqualTo(true)
    }

    private fun outboxCount(application: UUID): Any = checkNotNull(
        scalar(
            """
            SELECT count(*) FROM lending_outbox WHERE aggregate_id = ? AND event_type = 'credit.application.transition'
                AND payload::jsonb ->> 'toState' = 'SIGNED'
            """.trimIndent(),
            application,
        ),
    )

    private fun seedApplication(): UUID {
        val application = UUID.randomUUID()
        execute(
            """
            INSERT INTO loan_application (
                id, party_id, requested_amount, currency, nominal_annual_rate, term_periods,
                first_due_date, status, proposed_by, decided_by, decided_at, jurisdiction,
                catalog_offering_id, catalog_revision_id, catalog_content_hash, catalog_schema_version
            ) VALUES (?, ?, 10000, 'CZK', 0.05, 12, CURRENT_DATE + 30, 'AWAITING_SIGNATURE',
                'synthetic-maker', 'synthetic-checker', CURRENT_TIMESTAMP, 'CZ', ?, ?, ?, 1)
            """.trimIndent(),
            application,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "11".repeat(32),
        )
        return application
    }

    private fun execute(sql: String, vararg args: Any) = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeUpdate()
        }
    }

    private fun scalar(sql: String, id: UUID): Any? = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use(::firstColumn)
        }
    }

    private fun firstColumn(rows: ResultSet): Any? = if (rows.next()) rows.getObject(1) else null

    private companion object {
        const val SIGNER = "synthetic-signature-operator"
        val ARTEFACT = "aa".repeat(32)
        val PARTY = UUID.randomUUID()
        val ACCOUNT = UUID.randomUUID()
        val GRANT = UUID.randomUUID()
        val REVISION = CatalogLoanSnapshot(UUID.randomUUID(), UUID.randomUUID(), "dc".repeat(32), 2)
        val ROUTE = FosRoute("SK", UUID.randomUUID(), UUID.randomUUID(), "WEB", "RETAIL", "ef".repeat(32))
    }
}
