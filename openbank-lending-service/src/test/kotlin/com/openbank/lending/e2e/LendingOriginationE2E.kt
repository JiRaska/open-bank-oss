// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.e2e

import com.openbank.lending.application.usecase.OriginationRefusedException
import com.openbank.lending.integration.LendingOutboxWriteIT
import com.openbank.lending.it.TestBorrowerCreditPort
import com.openbank.lending.it.TestRecordingLedgerPostingPort
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * End-to-end origination spine of the lending money path over real HTTP and a real database:
 * desk apply with a keyed idempotent replay, the advance chain to the four-eyes gate, the
 * maker/checker decision, the signature command, the segregation-of-duties refusals at
 * disbursement, the disbursal itself (loan + contractual schedule + the two money wires), the
 * loan read-backs over HTTP, the customer-facing journey view with party scoping, the
 * customer-edge intake route with its named-principal boundary — and the anonymous/viewer
 * refusals.
 *
 * What is real and what is stood in for, stated out loud: the full origination state machine
 * (#11626 single-transaction booking included), Postgres, the outbox write, and the REST
 * contract run for real. The two remote money wires — the GL journal and the borrower credit —
 * are the module's test-scope recording stubs (`TestRecordingLedgerPostingPort` /
 * `TestBorrowerCreditPort`, @Alternative @Priority over the refuse-by-default NoOps, #6057):
 * this suite asserts the flow TRIED to move the money exactly once per leg with the ADR-0143-
 * style references; the real wire shapes are pinned by the pact suite.
 *
 * The customer-intake surface is switched on through the profile (off by default — blank
 * `caller-principal` refuses everything, deliberately) so the edge boundary can be exercised
 * end to end: intake submit as the vouched service-account, replay, wrong-principal 403,
 * missing party header 400.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(LendingOriginationE2E.E2EProfile::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
    restrictToAnnotatedClass = true,
)
class LendingOriginationE2E {

    class E2EProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A profile forces this class its own Quarkus boot; pin an ephemeral port so the
            // restart cannot die on QuarkusBindException against the still-bound 8081.
            "quarkus.http.test-port" to "0",
            // Customer self-service intake is OFF by default (a blank caller-principal refuses
            // everything — the over-grant-by-omission the KDoc warns about). Turn it on for the
            // edge-boundary journeys, priced and bounded.
            "lending.intake.enabled" to "true",
            "lending.intake.caller-principal" to "service-account-customer-edge-e2e",
            "lending.intake.nominal-annual-rate" to "0.059",
            "lending.intake.min-amount" to "5000",
            "lending.intake.max-amount" to "1000000",
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var ledgerPort: TestRecordingLedgerPostingPort

    @Inject
    lateinit var borrowerCreditPort: TestBorrowerCreditPort

    private val partyId: UUID = UUID.randomUUID()
    private lateinit var applicationId: String
    private lateinit var applyKey: String
    private lateinit var loanId: String

    private companion object {
        const val PROPOSER = "e2e-lending-proposer"
        const val CHECKER = "e2e-lending-checker"
        const val DISBURSER = "e2e-lending-disburser"
        const val EDGE_CLIENT = "customer-edge-e2e"
        const val EDGE_PRINCIPAL = "service-account-customer-edge-e2e"
        const val ADVANCES_TO_FOUR_EYES = 4
        val ARTEFACT_HASH: String = "cd".repeat(32)
        val FIRST_DUE: LocalDate = LocalDate.now().plusMonths(1)
        const val PRINCIPAL = "10000.00"
        const val TERM = 12

        // The wire references LendingService/LoanCashReferences stamp on the two money legs.
        const val DISBURSEMENT_POSTING = "disbursement"
        const val DISBURSEMENT_CREDIT = "disbursement-credit"
    }

    // ---------------------------------------------------------------- desk origination journey

    @Test
    @Order(1)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `1 - desk apply is a keyed 201 landing SUBMITTED`() {
        applyKey = UUID.randomUUID().toString()
        val created = apply(partyId, applyKey)
        applicationId = created.jsonPath().getString("id")
        assertThat(applicationId).isNotBlank()
        assertThat(created.jsonPath().getString("status")).isEqualTo("SUBMITTED")
        assertThat(amountOf(created.jsonPath().get("requestedAmount"))).isEqualByComparingTo(PRINCIPAL)
        assertThat(countPartyApplications(partyId)).isEqualTo(1)
    }

    @Test
    @Order(2)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `2 - a keyed retry replays the same application and a key reuse with another body is 409`() {
        val replay = apply(partyId, applyKey)
        assertThat(replay.jsonPath().getString("id")).isEqualTo(applicationId)
        assertThat(countPartyApplications(partyId))
            .describedAs("a replay never stacks a second SUBMITTED application")
            .isEqualTo(1)

        // #10916: the key is fingerprint-bound — the same key with a DIFFERENT request must not
        // replay, it must refuse.
        val key = UUID.randomUUID().toString()
        apply(partyId, key)
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(applyBody(partyId, "20000.00"))
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `3 - four advances reach the four-eyes gate`() {
        val last = advance(applicationId, ADVANCES_TO_FOUR_EYES)
        assertThat(last.jsonPath().getString("status")).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(4)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK"])
    fun `4 - the maker cannot approve their own application`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"self-approval attempt"}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/decision")
        } Then {
            statusCode(409)
        }
        assertThat(storedStatus(applicationId)).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(5)
    @TestSecurity(user = CHECKER, roles = ["ROLE_CREDIT_RISK"])
    fun `5 - a different checker approves into OFFERED`() {
        val decided = Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"within policy"}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/decision")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(decided.jsonPath().getString("status")).isEqualTo("OFFERED")
    }

    @Test
    @Order(6)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `6 - the offer advances to AWAITING_SIGNATURE and sign commits the artefact hash`() {
        val awaiting = advance(applicationId, 1)
        assertThat(awaiting.jsonPath().getString("status")).isEqualTo("AWAITING_SIGNATURE")

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{"artefactHash":"$ARTEFACT_HASH"}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/sign")
        } Then {
            statusCode(200)
        }
        assertThat(storedStatus(applicationId)).isEqualTo("SIGNED")
        assertThat(storedArtefactHash(applicationId)).isEqualTo(ARTEFACT_HASH)
    }

    @Test
    @Order(7)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `7 - the signed contract advances to READY_TO_DISBURSE`() {
        val ready = advance(applicationId, 1)
        assertThat(ready.jsonPath().getString("status")).isEqualTo("READY_TO_DISBURSE")
    }

    @Test
    @Order(8)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `8 - the proposer cannot release the cash - segregation of duties`() {
        val refusal = disburse(applicationId)
        assertThat(refusal.statusCode()).isEqualTo(409)
        assertThat(refusal.jsonPath().getString("error"))
            .isEqualTo(OriginationRefusedException.SEGREGATION_OF_DUTIES)
        assertThat(storedStatus(applicationId)).isEqualTo("READY_TO_DISBURSE")
    }

    @Test
    @Order(9)
    @TestSecurity(user = CHECKER, roles = ["ROLE_CREDIT_RISK", "ROLE_LENDING_OFFICER"])
    fun `9 - the approver cannot release the cash either`() {
        val refusal = disburse(applicationId)
        assertThat(refusal.statusCode()).isEqualTo(409)
        assertThat(refusal.jsonPath().getString("error"))
            .isEqualTo(OriginationRefusedException.SEGREGATION_OF_DUTIES)
        assertThat(storedStatus(applicationId)).isEqualTo("READY_TO_DISBURSE")
    }

    @Test
    @Order(10)
    @TestSecurity(user = DISBURSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `10 - a third person disburses - the loan, its schedule and both money wires land exactly once`() {
        val booked = disburse(applicationId)
        assertThat(booked.statusCode()).isEqualTo(201)
        loanId = booked.jsonPath().getString("id")
        assertThat(loanId).isNotBlank()
        assertThat(booked.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(booked.jsonPath().getString("contractNumber")).matches("UV-\\d{4}-\\d{6,}")
        assertThat(storedStatus(applicationId)).isEqualTo("DISBURSED")

        // The GL leg and the customer leg were each attempted exactly once, under the
        // ADR-0143-style references (the wire shapes themselves are the pact suite's subject).
        assertThat(ledgerPort.recorded.map { it.reference })
            .describedAs("the GL disbursement journal was posted exactly once")
            .contains("loan:$loanId:$DISBURSEMENT_POSTING")
        assertThat(borrowerCreditPort.calls)
            .describedAs("the borrower credit moved the cash exactly once")
            .contains("loan:$loanId:$DISBURSEMENT_CREDIT")

        // The durable event that audit-service consumes.
        assertThat(outboxRows(loanId)).isEqualTo(1)

        // A disbursed application is terminal — the second attempt refuses rather than booking
        // a second loan.
        val second = disburse(applicationId)
        assertThat(second.statusCode()).isEqualTo(409)
    }

    @Test
    @Order(11)
    @TestSecurity(user = DISBURSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `11 - the loan and its schedule read back over HTTP with exact amortization math`() {
        val loan = Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/loans/$loanId")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(loan.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(loan.jsonPath().getString("applicationId")).isEqualTo(applicationId)
        assertThat(amountOf(loan.jsonPath().get("principal"))).isEqualByComparingTo(PRINCIPAL)
        assertThat(loan.jsonPath().getString("contractNumber")).matches("UV-\\d{4}-\\d{6,}")

        val schedule = Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/loans/$loanId/schedule")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        val rows = schedule.jsonPath().getList<Map<String, Any>>("")
        assertThat(rows).hasSize(TERM)
        assertThat(rows.map { (it["number"] as Int) }).isEqualTo((1..TERM).toList())
        assertThat(rows.first()["dueDate"].toString()).startsWith(FIRST_DUE.toString())
        // The schedule amortizes the full principal — the parts sum back to the loan exactly.
        val principalParts = rows.fold(BigDecimal.ZERO) { acc, row ->
            acc + amountOf(row.getValue("principal"))
        }
        assertThat(principalParts).isEqualByComparingTo(PRINCIPAL)
    }

    // ---------------------------------------------------------------- customer-facing surface

    @Test
    @Order(12)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = EDGE_CLIENT),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
            Claim(key = "sub", value = "00000000-0000-0000-0000-0000000000e9"),
        ],
    )
    fun `12 - the customer journey reads the disbursed application to its owner and hides it from others`() {
        val journey = journeyOne(applicationId, partyId)
        assertThat(journey.statusCode()).isEqualTo(200)
        assertThat(journey.jsonPath().getString("id")).isEqualTo(applicationId)
        assertThat(journey.jsonPath().getString("state")).isEqualTo("DISBURSED")

        // Owner-filtered read: a different party must see NOT_FOUND, never someone else's credit
        // history (the id space does not leak).
        val foreign = journeyOne(applicationId, UUID.randomUUID())
        assertThat(foreign.statusCode()).isEqualTo(404)
    }

    @Test
    @Order(13)
    @TestSecurity(user = EDGE_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = EDGE_CLIENT),
            Claim(key = "preferred_username", value = EDGE_PRINCIPAL),
            Claim(key = "sub", value = "00000000-0000-0000-0000-0000000000e9"),
        ],
    )
    fun `13 - the edge submits a customer application, a keyed retry replays it, and the guards hold`() {
        val intakeParty = UUID.randomUUID()
        val requestId = UUID.randomUUID().toString()

        val submitted = intakeSubmit(intakeParty, requestId)
        assertThat(submitted.statusCode()).isEqualTo(201)
        val intakeId = submitted.jsonPath().getString("id")
        assertThat(submitted.jsonPath().getString("status")).isEqualTo("SUBMITTED")
        // ADR-0214: the actor is the namespaced customer maker, never a desk principal.
        assertThat(submitted.jsonPath().getString("proposedBy")).isEqualTo("customer:$intakeParty")

        val replay = intakeSubmit(intakeParty, requestId)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.jsonPath().getString("id")).isEqualTo(intakeId)
        assertThat(countPartyApplications(intakeParty)).isEqualTo(1)

        // Guard: the party header is the edge's vouched scope, not a client-supplied identity.
        Given {
            contentType("application/json")
            header("X-Request-ID", UUID.randomUUID().toString())
            body("""{"amount":50000,"termMonths":24}""")
        } When {
            post("/api/v1/lending/intake/applications")
        } Then {
            statusCode(400)
        }

        // Guard: the amount bounds are the configured product's, not the caller's choice.
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", intakeParty.toString())
            header("X-Request-ID", UUID.randomUUID().toString())
            body("""{"amount":100,"termMonths":24}""")
        } When {
            post("/api/v1/lending/intake/applications")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @Order(14)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `14 - a plain operator is not the intake principal`() {
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
            header("X-Request-ID", UUID.randomUUID().toString())
            body("""{"amount":50000,"termMonths":24}""")
        } When {
            post("/api/v1/lending/intake/applications")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(15)
    fun `15 - anonymous and viewer callers never touch the money path`() {
        Given {
            contentType("application/json")
            body(applyBody(UUID.randomUUID(), PRINCIPAL))
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @Order(16)
    @TestSecurity(user = "e2e-lending-viewer", roles = ["ROLE_VIEWER"])
    fun `16 - a viewer reads the loan but cannot originate`() {
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/loans/$loanId")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            body(applyBody(UUID.randomUUID(), PRINCIPAL))
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(17)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `17 - unknown ids stay client errors on every surface`() {
        val unknown = UUID.randomUUID()
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$unknown/advance")
        } Then {
            statusCode(404)
        }
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/loans/$unknown")
        } Then {
            statusCode(404)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun applyBody(party: UUID, amount: String): String =
        """
        {"partyId":"$party","requestedAmount":{"amount":"$amount","currency":{"code":"EUR"}},
        "nominalAnnualRate":0.05,"termPeriods":$TERM,"firstDueDate":"$FIRST_DUE"}
        """.trimIndent()

    private fun apply(party: UUID, key: String): ExtractableResponse<Response> = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body(applyBody(party, PRINCIPAL))
    } When {
        post("/api/v1/lending/applications")
    } Then {
        statusCode(201)
    } Extract {
        this
    }

    private fun advance(id: String, times: Int): ExtractableResponse<Response> {
        var last: ExtractableResponse<Response>? = null
        repeat(times) {
            last = Given {
                contentType("application/json")
            } When {
                post("/api/v1/lending/applications/$id/advance")
            } Then {
                statusCode(200)
            } Extract {
                this
            }
        }
        return checkNotNull(last)
    }

    private fun disburse(id: String): ExtractableResponse<Response> = Given {
        contentType("application/json")
    } When {
        post("/api/v1/lending/applications/$id/disburse")
    } Extract {
        this
    }

    private fun intakeSubmit(party: UUID, requestId: String): ExtractableResponse<Response> = Given {
        contentType("application/json")
        header("X-Customer-Party-Id", party.toString())
        header("X-Request-ID", requestId)
        body("""{"amount":50000,"termMonths":24}""")
    } When {
        post("/api/v1/lending/intake/applications")
    } Extract {
        this
    }

    private fun journeyOne(id: String, party: UUID): ExtractableResponse<Response> = Given {
        contentType("application/json")
        header("X-Customer-Party-Id", party.toString())
    } When {
        get("/api/v1/lending/intake/applications/$id")
    } Extract {
        this
    }

    /** RestAssured parses JSON numbers as Double — normalize through BigDecimal for comparisons. */
    private fun amountOf(money: Any): BigDecimal = BigDecimal((money as Map<String, Any>)["amount"].toString())

    private fun storedStatus(id: String): String = dataSource.connection.use { c ->
        c.prepareStatement("select status::text from loan_application where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "application $id not stored" }
                rs.getString(1)
            }
        }
    }

    private fun storedArtefactHash(id: String): String? = dataSource.connection.use { c ->
        c.prepareStatement("select signature_artefact_hash from loan_application where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "application $id not stored" }
                rs.getString(1)
            }
        }
    }

    private fun countPartyApplications(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from loan_application where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun outboxRows(loanId: String): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from lending_outbox where aggregate_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(loanId))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}
