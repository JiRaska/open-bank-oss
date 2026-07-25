// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
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

    /** One line per GL account type so every mapper row is populated with a non-zero value. */
    private val trialBalance = listOf(
        TrialBalanceLineDto(code = "1100-CASH-CZK", accountType = "ASSET", net = BigDecimal("150000.00")),
        TrialBalanceLineDto(code = "2100-DEPOSITS-CZK", accountType = "LIABILITY", net = BigDecimal("120000.00")),
        TrialBalanceLineDto(code = "4100-FEE-INCOME-CZK", accountType = "INCOME", net = BigDecimal("8000.00")),
        TrialBalanceLineDto(code = "5100-STAFF-COST-CZK", accountType = "EXPENSE", net = BigDecimal("3000.00")),
    )

    @BeforeEach
    fun installLedgerStub() {
        val ledger = mockk<LedgerAdapter>()
        coEvery { ledger.getTrialBalance(any()) } returns trialBalance
        QuarkusMock.installMockForType(ledger, LedgerAdapter::class.java)
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

        assertThat(json.keys).containsExactlyInAnyOrder("templateId", "period", "cells", "isBalanced")
        assertThat(json["templateId"]).isEqualTo("F01.01")
        assertThat(json["period"]).isEqualTo("2026-06-30")
        assertThat(json["isBalanced"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val cells = json["cells"] as List<Map<*, *>>
        assertThat(cells).hasSize(3)
        assertThat(cells.first().keys).containsExactlyInAnyOrder("rowRef", "colRef", "value", "currency")
        assertThat(cells.map { it["rowRef"] }).containsExactly("r010", "r380", "r490")
        assertThat(cells.first()["currency"]).isEqualTo("CZK")
        // r010 total assets, r380 total liabilities, r490 derived equity — proves the cells are
        // really derived from the ledger trial balance, not a fixed skeleton. `value` is a JSON
        // NUMBER (schema `type: number`), so compare numerically — an untyped parse of `150000.00`
        // yields a Double whose toString drops the scale.
        assertThat(cells.map { (it["value"] as Number).toDouble() })
            .containsExactly(150_000.00, 120_000.00, 30_000.00)
    }

    @Test
    fun `the COREP template wire format matches the published openapi schema`() {
        val json = getJson("/api/v1/corep/templates/C_01.00", LocalDate.of(2026, 6, 30))

        assertThat(json.keys).containsExactlyInAnyOrder("templateId", "period", "cells", "hasDataGaps")
        assertThat(json["templateId"]).isEqualTo("C_01.00")
        assertThat(json["period"]).isEqualTo("2026-06-30")
        // No EQUITY line in the stubbed trial balance — every capital row is a flagged zero.
        assertThat(json["hasDataGaps"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val cells = json["cells"] as List<Map<*, *>>
        assertThat(cells).hasSize(9)
        assertThat(cells.first().keys)
            .containsExactlyInAnyOrder("rowRef", "colRef", "label", "value", "currency", "isDataGap", "gapReason")
        assertThat(cells.first()["rowRef"]).isEqualTo("r010")
        assertThat(cells.first()["label"]).isEqualTo("OWN FUNDS")
        assertThat(cells.first()["isDataGap"]).isEqualTo(true)
        assertThat(cells.first()["gapReason"].toString()).contains("No capital-structure GL accounts")
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
}
