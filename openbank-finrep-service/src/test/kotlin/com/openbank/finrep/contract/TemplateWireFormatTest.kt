// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.finrep.application.port.out.ClosedPeriodDto
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.infrastructure.client.LedgerAdapter
import com.openbank.libs.security.Roles
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Spec-conformance test: asserts the WIRE FORMAT the two template endpoints actually emit, so
 * `src/main/resources/openapi.yaml` documents reality rather than an assumption.
 *
 * Why this exists as a test and not a review note: [com.openbank.finrep.infrastructure.rest.FinrepResource]
 * and [com.openbank.finrep.infrastructure.rest.CorepResource] return the DOMAIN objects directly
 * (`Response.ok(template)`), so the JSON names of the `is`/`has`-prefixed Kotlin properties
 * (`FinrepTemplate.isBalanced`, `CorepTemplate.hasDataGaps`, `CorepCell.isDataGap`) are decided by
 * the Jackson/Kotlin naming interplay, not by anything visible in the source.
 *
 * Measured, not assumed — the answer is counter-intuitive: the emitted keys are **`isBalanced`**,
 * **`isDataGap`** and **`hasDataGaps`**. Kotlin names the getter of an `is`-prefixed property
 * `isBalanced()` with no `get` prefix, and plain Jackson's `isXxx()` boolean-getter convention
 * would strip that to `balanced` — but jackson-module-kotlin contributes the primary-constructor
 * parameter name as the implicit property name, which wins, so the `is` prefix survives on the
 * wire. Asserting the exact key SET (not `contains`) means a Jackson/Kotlin upgrade that flips
 * that precedence fails here instead of silently invalidating the published contract.
 *
 * Deliberately NOT fixed by mapping the resources to explicit REST DTOs with plain names (the
 * pattern openbank-ledger-service uses, and the reason ledger never faces this question): that
 * would change the wire format for existing consumers. This test documents what is served.
 *
 * The ledger read port is stubbed via [QuarkusMock] rather than a `@Mock` CDI alternative on
 * purpose — an alternative would leave the real [LedgerAdapter] unused and eligible for ArC's
 * unused-bean removal, weakening what `FinrepBootSmokeTest` boots to validate. Note that
 * `QuarkusMock.installMockForType` checks the mock against the resolved BEAN class, not the
 * injected type, so the mock must be a [LedgerAdapter] — a `mockk<LedgerPort>()` is rejected with
 * `is not assignable to class ...LedgerAdapter`.
 */
@QuarkusTest
// The role MUST be one the Keycloak realm actually issues (Roles.OPERATOR == "ROLE_OPERATOR").
// This test previously declared roles = ["SERVICE"], which matched the resource's own
// `@RolesAllowed("SERVICE", …)` literal and nothing else in the system: @TestSecurity mints
// whatever role string it is given, so the test minted the exact role the realm never issues and
// went green against a resource that answered 403 to every real caller. Asserting through a
// realm-issued role is what makes this test capable of failing.
@TestSecurity(user = "finrep-spec-conformance", roles = [Roles.OPERATOR])
class TemplateWireFormatTest {

    private val mapper = ObjectMapper()

    /**
     * One line per GL account type so every mapper row is populated with a non-zero value, in
     * ledger's own `net = totalDebit - totalCredit` sign (credit-normal accounts negative), and
     * tying out to zero so the rendered `isBalanced` is `true` for a reason rather than by
     * construction (issue #5987).
     */
    private val trialBalance = listOf(
        TrialBalanceLineDto(
            code = "1100",
            accountType = "ASSET",
            net = BigDecimal("150000.00"),
            currency = "CZK",
        ),
        TrialBalanceLineDto(
            code = "2100",
            accountType = "LIABILITY",
            net = BigDecimal("-125000.00"),
            currency = "CZK",
        ),
        TrialBalanceLineDto(
            code = "4100",
            accountType = "INCOME",
            net = BigDecimal("-28000.00"),
            currency = "CZK",
        ),
        TrialBalanceLineDto(
            code = "5100",
            accountType = "EXPENSE",
            net = BigDecimal("3000.00"),
            currency = "CZK",
        ),
    )

    @BeforeEach
    fun installLedgerStub() {
        val ledger = mockk<LedgerAdapter>()
        coEvery { ledger.getTrialBalance(any()) } returns TrialBalanceSnapshot(
            lines = trialBalance,
            // Ledger's own verdict for this fixture, passed explicitly: it ties out (issue #6011).
            ledgerReportsBalanced = true,
        )
        coEvery { ledger.listClosedPeriods() } returns listOf(
            ClosedPeriodDto("MONTH", LocalDate.of(2026, 6, 30), "FROZEN", "LINES_V1"),
            ClosedPeriodDto("MONTH", LocalDate.of(2026, 5, 31), "DRAFT", "LINES_V1"),
        )
        QuarkusMock.installMockForType(ledger, LedgerAdapter::class.java)
    }

    @Test
    fun `reporting periods expose only immutable evidence`() {
        val body = given()
            .accept("application/json")
            .get("/api/v1/finrep/periods")
            .then()
            .statusCode(200)
            .extract().body().asString()
        val json = mapper.readValue(body, Map::class.java)

        assertThat(json.keys).containsExactlyInAnyOrder("latest", "periods")
        assertThat(json["latest"]).isEqualTo("2026-06-30")
        assertThat(json["periods"]).isEqualTo(listOf("2026-06-30"))
    }

    private fun getJson(path: String, asOf: LocalDate): Map<*, *> {
        val body = given()
            .accept("application/json")
            .queryParam("asOf", asOf.toString())
            .get(path)
            .then()
            .statusCode(200)
            .extract().body().asString()
        return mapper.readValue(body, Map::class.java)
    }

    @Test
    fun `the FINREP template wire format matches the published openapi schema`() {
        val json = getJson("/api/v1/finrep/templates/F01.01", LocalDate.of(2026, 6, 30))

        assertThat(json.keys).containsExactlyInAnyOrder(
            "templateId",
            "period",
            "cells",
            "dataGaps",
            "hasDataGaps",
            "isBalanced",
            "balanceVerdict",
        )
        assertThat(json["templateId"]).isEqualTo("F01.01")
        assertThat(json["period"]).isEqualTo("2026-06-30")
        assertThat(json["isBalanced"]).isEqualTo(true)
        // The verdict is served as the ENUM NAME, which is what `openapi.yaml` documents (#6011).
        assertThat(json["balanceVerdict"]).isEqualTo("AGREED_BALANCED")
        assertThat(json["hasDataGaps"]).isEqualTo(false)

        @Suppress("UNCHECKED_CAST")
        val gaps = json["dataGaps"] as List<Map<*, *>>
        assertThat(gaps).isEmpty()

        @Suppress("UNCHECKED_CAST")
        val cells = json["cells"] as List<Map<*, *>>
        assertThat(cells).hasSize(6)
        assertThat(cells.first().keys).containsExactlyInAnyOrder("rowRef", "colRef", "value", "currency")
        assertThat(cells.map { it["rowRef"] }).contains("r0010", "r0040", "r0181", "r0183", "r0360", "r0380")
        assertThat(cells.map { it["colRef"] }).containsOnly("c0010")
        assertThat(cells.first()["currency"]).isEqualTo("CZK")
        // Official F 01.01 r0380 total assets — proves the cell is really derived from the ledger
        // trial balance, not a fixed skeleton. `value` is a JSON
        // NUMBER (schema `type: number`), so compare numerically — an untyped parse of `150000.00`
        // yields a Double whose toString drops the scale.
        assertThat((cells.single { it["rowRef"] == "r0380" }["value"] as Number).toDouble())
            .isEqualTo(150_000.00)
    }

    @Test
    fun `the XBRL CSV preflight is ready when the governed mapping is complete and the trial balance agrees`() {
        val json = getJson("/api/v1/finrep/templates/F01.01/xbrl-csv/preflight", LocalDate.of(2026, 6, 30))

        assertThat(json.keys).containsExactlyInAnyOrder(
            "templateId",
            "period",
            "reportingFrameworkVersion",
            "dpmVersion",
            "taxonomyVersion",
            "state",
            "blockers",
        )
        assertThat(json["templateId"]).isEqualTo("F01.01")
        assertThat(json["period"]).isEqualTo("2026-06-30")
        assertThat(json["reportingFrameworkVersion"]).isEqualTo("4.2")
        assertThat(json["dpmVersion"]).isEqualTo("4.2.1")
        assertThat(json["taxonomyVersion"]).isEqualTo("4.2.0.0")
        assertThat(json["state"]).isEqualTo("READY_FOR_RENDERING")

        @Suppress("UNCHECKED_CAST")
        val blockers = json["blockers"] as List<Map<*, *>>
        assertThat(blockers).isEmpty()
    }

    @Test
    fun `the COREP template wire format matches the published openapi schema`() {
        val json = getJson("/api/v1/corep/templates/C_01.00", LocalDate.of(2026, 6, 30))

        assertThat(json.keys).containsExactlyInAnyOrder("templateId", "period", "cells", "hasDataGaps")
        assertThat(json["templateId"]).isEqualTo("C_01.00")
        assertThat(json["period"]).isEqualTo("2026-06-30")
        // No recognised capital line in the stubbed trial balance — every capital row is a flagged zero.
        assertThat(json["hasDataGaps"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val cells = json["cells"] as List<Map<*, *>>
        assertThat(cells).hasSize(9)
        assertThat(cells.first().keys)
            .containsExactlyInAnyOrder("rowRef", "colRef", "label", "value", "currency", "isDataGap", "gapReason")
        assertThat(cells.first()["rowRef"]).isEqualTo("r010")
        assertThat(cells.first()["label"]).isEqualTo("OWN FUNDS")
        assertThat(cells.first()["isDataGap"]).isEqualTo(true)
        assertThat(cells.first()["gapReason"].toString()).contains("no recognised regulatory-capital source")
    }

    @Test
    fun `an unknown templateId is a 400 with the shared ApiError envelope`() {
        // The openapi.yaml documents 400 for an unknown templateId on both endpoints. That status
        // is not chosen here — it comes from openbank-libs-runtime's CommonExceptionMappers mapping
        // the use case's IllegalArgumentException — so assert it rather than assume it.
        listOf("/api/v1/finrep/templates/F99.99", "/api/v1/corep/templates/C_99.99").forEach { path ->
            val body = given()
                .accept("application/json")
                .get(path)
                .then()
                .statusCode(400)
                .extract().body().asString()
            val error = mapper.readValue(body, Map::class.java)
            assertThat(error.keys).contains("traceId", "status", "code", "message")
            assertThat(error["status"]).isEqualTo(400)
        }
    }

    /**
     * #3874: the SERVED body, not the mapper's return value — `timestamp` is declared in this
     * service's `openapi.yaml` (one of only two specs that declare it), and it was serialised as
     * `1970-01-01T00:00:00Z` on every error the fleet ever returned, because
     * `ApiError.timestamp` defaulted to `Instant.EPOCH` and no call site passed it.
     *
     * Asserts RECENCY. A `isNotNull`/`isNotBlank` assertion passes against the epoch, which is
     * precisely why nothing caught this. The window is bounded by two real clock reads either side
     * of the request, so it cannot pass against a constant.
     */
    @Test
    fun `the served error body carries a timestamp from now, not the epoch`() {
        val before = Instant.now().minusSeconds(1)
        val body = given()
            .accept("application/json")
            .get("/api/v1/finrep/templates/F99.99")
            .then()
            .statusCode(400)
            .extract().body().asString()
        val after = Instant.now().plusSeconds(1)

        val served = mapper.readValue(body, Map::class.java)["timestamp"]
        assertThat(served).describedAs("timestamp is absent from the served error envelope").isNotNull()
        assertThat(Instant.parse(served.toString())).isBetween(before, after)
    }
}
