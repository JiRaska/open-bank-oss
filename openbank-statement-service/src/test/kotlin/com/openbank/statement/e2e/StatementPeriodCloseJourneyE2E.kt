// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journey for a statement period's whole legal life: **close January for an account
 * against the upstream reads (account, pockets, party, booked entries, point-in-time balances),
 * read the period back, render it as camt.053, render the customer-facing styled document, restate
 * it after a booked-data correction lands — and watch the surface fail closed everywhere a legal
 * record could otherwise be minted on bad figures.**
 *
 * Driven entirely through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres (`PostgresTestResource`, per-class Testcontainers). No use case or
 * repository is called directly. The five upstream services statement-service reads at close time
 * are served by [UpstreamServicesStubResource], one JDK HttpServer whose routes are deterministic
 * per account id — the same approach the module's pact tests take with their mock servers, extended
 * here so the whole close → read → render → restate chain travels over real REST calls in both
 * directions.
 *
 * ### Service boundaries — what is NOT claimed here
 *
 * The upstream services' own behaviour is stubbed, not exercised: this journey proves
 * statement-service *calls the right routes with the right parameters and reconciles what comes
 * back*, not that account-service's pockets endpoint is correct. The outbox event's DISPATCH to a
 * broker is out of scope — the row is this service's own durable state, but no consumer runs in
 * this process.
 *
 * ### Why the read-back and the restate matter
 *
 * A close endpoint returns the record it just wrote, which is the same code path that computed it.
 * Reading the period back with `GET /{accountId}` after the close is what proves a legal sequence
 * was persisted rather than merely rendered. The restate is the stronger check: a correction must
 * write a NEW sequenced row, flip the old one to SUPERSEDED in the same transaction, and never edit
 * the retracted record (ADR-0035 §D) — a defect there answers 200 here while the legal record stays
 * wrong.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(UpstreamServicesStubResource::class)
@TestProfile(StatementPeriodCloseJourneyE2E.JourneyProfile::class)
class StatementPeriodCloseJourneyE2E {

    /**
     * RBAC is asserted through `@RolesAllowed`; the advisory OPA PEP has no sidecar in tests, and
     * the OIDC client is disabled so the outbound rest clients travel to the stub without a token
     * flow (the same %test posture kyc-service uses).
     */
    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
            "quarkus.oidc-client.enabled" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `1 - an operator closes January for the reconciling account and the period reads back`() {
        UpstreamServicesStubResource.wireAccount(MAIN_ACCOUNT)
        val closed = given()
            .contentType("application/json")
            .queryParam("from", FROM)
            .queryParam("to", TO)
            .post("/api/v1/statements/$MAIN_ACCOUNT/close")
        closed.then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].pocketCurrency", equalTo("CZK"))
            .body("[0].legalSequenceNumber", equalTo(1))
            .body("[0].electronicSequenceNumber", equalTo(1))
            .body("[0].entryCount", equalTo(2))
            .body("[0].status", equalTo("CLOSED"))
        assertThat(BigDecimal(closed.jsonPath().getString("[0].openingBalance")).compareTo(BigDecimal("1000.00")))
            .describedAs("opening balance asOf the day before the period: %s", closed.body.asString())
            .isZero()
        assertThat(BigDecimal(closed.jsonPath().getString("[0].closingBalance")).compareTo(BigDecimal("1075.00")))
            .describedAs("closing balance = opening + booked net (+100.00 -25.00): %s", closed.body.asString())
            .isZero()

        // Read-back through the list: the legal sequence exists in the retained record, not just
        // in the close response.
        given().get("/api/v1/statements/$MAIN_ACCOUNT").then()
            .statusCode(200)
            .body("legalSequenceNumber", hasItem(1))
            .body("status", hasItem("CLOSED"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `2 - an account whose reported balance disagrees with the booked entries fails closed with 409`() {
        // BROKEN_ACCOUNT is never wired into the stub: the stub reports a 1000.00 closing balance
        // for it while the booked entries still net +75.00 — reconciliation must REFUSE to mint a
        // legal statement on figures that do not tie out (ADR-0035 §E), and no period may persist.
        val conflict = given()
            .contentType("application/json")
            .queryParam("from", FROM)
            .queryParam("to", TO)
            .post("/api/v1/statements/$BROKEN_ACCOUNT/close")
        conflict.then()
            .statusCode(409)
            .body("error", containsString("reconciliation failed"))
        given().get("/api/v1/statements/$BROKEN_ACCOUNT").then()
            .statusCode(200)
            .body("size()", equalTo(0))
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `3 - the summary endpoint replays the closed period as structured JSON`() {
        given().get("/api/v1/statements/$MAIN_ACCOUNT/CZK/1/summary").then()
            .statusCode(200)
            .body("iban", equalTo(UpstreamServicesStubResource.IBAN))
            .body("currency", equalTo("CZK"))
            .body("holderName", equalTo(UpstreamServicesStubResource.HOLDER_NAME))
            .body("legalSequenceNumber", equalTo(1))
            .body("periodFrom", equalTo(FROM))
            .body("periodTo", equalTo(TO))
            .body("entries.entryRef", hasItems("TX-E2E-1", "TX-E2E-2"))
            .body("entries.find { it.entryRef == 'TX-E2E-1' }.creditDebit", equalTo("CRDT"))
            .body("entries.find { it.entryRef == 'TX-E2E-2' }.creditDebit", equalTo("DBIT"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `4 - the closed period renders on demand as camt 053`() {
        val camt = given()
            .queryParam("format", "CAMT_053")
            .get("/api/v1/statements/$MAIN_ACCOUNT/CZK/1")
        camt.then()
            .statusCode(200)
        // Nothing is stored at render time (ADR-0035 §F.2) — the document is replayed from the
        // frozen snapshot, so the IBAN and the booked entry refs must appear in the projection.
        assertThat(camt.body.asString())
            .contains(UpstreamServicesStubResource.IBAN)
            .contains("TX-E2E-1")
            .contains("TX-E2E-2")
    }

    @Test
    @Order(5)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `5 - the customer-facing styled document renders through document-service's preview`() {
        val document = given()
            .queryParam("locale", "cs")
            .get("/api/v1/statements/$MAIN_ACCOUNT/CZK/1/document")
        document.then()
            .statusCode(200)
            .contentType(containsString("text/html"))
        // The stub's preview merges the holder name the close resolved from party-service.
        assertThat(document.body.asString())
            .contains(UpstreamServicesStubResource.HOLDER_NAME)
    }

    @Test
    @Order(6)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `6 - a booked-data correction restates the period as a new sequenced close`() {
        // The stub now reports one more booked entry (+10.00 on 2026-01-25) and a 1085.00 closing
        // balance: the same reconciliation the close used must pass on the corrected figures, and
        // the standing close must be superseded — never edited.
        UpstreamServicesStubResource.applyRestatementCorrection()
        val restated = given()
            .contentType("application/json")
            .queryParam("from", FROM)
            .queryParam("to", TO)
            .post("/api/v1/statements/$MAIN_ACCOUNT/CZK/restate")
        restated.then()
            .statusCode(200)
            .body("legalSequenceNumber", equalTo(2))
            .body("electronicSequenceNumber", equalTo(2))
            .body("supersedesSequence", equalTo(1))
            .body("entryCount", equalTo(3))
            .body("status", equalTo("CLOSED"))
        assertThat(BigDecimal(restated.jsonPath().getString("closingBalance")).compareTo(BigDecimal("1085.00")))
            .describedAs("restated closing = 1000.00 + (100.00 - 25.00 + 10.00): %s", restated.body.asString())
            .isZero()

        // Both rows are retained: the retracted close is SUPERSEDED, the correction is the one
        // CLOSED record for the window (ADR-0035 §D).
        given().get("/api/v1/statements/$MAIN_ACCOUNT").then()
            .statusCode(200)
            .body("size()", equalTo(2))
            .body("legalSequenceNumber", hasItems(1, 2))
            .body("find { it.legalSequenceNumber == 1 }.status", equalTo("SUPERSEDED"))
            .body("find { it.legalSequenceNumber == 2 }.status", equalTo("CLOSED"))

        // The superseding period replays from its own stored snapshot.
        given().get("/api/v1/statements/$MAIN_ACCOUNT/CZK/2/summary").then()
            .statusCode(200)
            .body("legalSequenceNumber", equalTo(2))
            .body("supersedesSequence", equalTo(1))
            .body("entries.entryRef", hasItem("TX-E2E-3"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = VIEWER, roles = ["ROLE_VIEWER"])
    fun `7 - a viewer can read statements but never close a period`() {
        // ROLE_VIEWER is in the endpoint's read roles but not its close roles — the write is
        // refused before any period state is touched.
        given()
            .contentType("application/json")
            .queryParam("from", FROM)
            .queryParam("to", TO)
            .post("/api/v1/statements/$MAIN_ACCOUNT/close")
            .then()
            .statusCode(403)
    }

    @Test
    @Order(8)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `8 - the surface fails closed on missing period bounds and unknown sequences`() {
        // A period is never guessed: a missing `from` is a client error, not a default window.
        given()
            .queryParam("to", TO)
            .contentType("application/json")
            .post("/api/v1/statements/$MAIN_ACCOUNT/close")
            .then()
            .statusCode(400)

        // Render/summary of a sequence that was never closed is a 404, not an empty document.
        given().get("/api/v1/statements/$MAIN_ACCOUNT/CZK/99").then()
            .statusCode(404)
        given().get("/api/v1/statements/$MAIN_ACCOUNT/CZK/99/summary").then()
            .statusCode(404)

        // Restating a window that was never closed has nothing to supersede.
        given()
            .queryParam("from", FROM)
            .queryParam("to", TO)
            .contentType("application/json")
            .post("/api/v1/statements/$BROKEN_ACCOUNT/CZK/restate")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(9)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `9 - an operator triggers a manual close run and reads the cadence telemetry back`() {
        // The registry the orchestrator enumerates is empty in this JVM, so the manual pass closes
        // nothing — but the run itself is durable operator-visible state (ADR-0069 D3).
        given().contentType("application/json").post("/api/v1/statements/close-runs").then()
            .statusCode(202)
            .body("trigger", equalTo("MANUAL"))

        given().get("/api/v1/statements/close-runs/latest").then()
            .statusCode(200)
            .body("trigger", equalTo("MANUAL"))
            .body("status", equalTo("COMPLETED"))
            .body("accountsEnumerated", equalTo(0))

        given().get("/api/v1/statements/close-runs").then()
            .statusCode(200)
            .body("trigger", hasItem("MANUAL"))
    }

    companion object {
        const val OPERATOR = "e2e-statement-operator"
        const val VIEWER = "e2e-statement-viewer"
        const val FROM = "2026-01-01"
        const val TO = "2026-01-31"

        /** Deterministic ids so the stub can tell the reconciling account from the broken one. */
        val MAIN_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-555555555501")
        val BROKEN_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-555555555502")
    }
}
