// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.integration

import com.openbank.fx.application.port.out.PartyNameUnavailableException
import com.openbank.fx.application.port.out.ScreeningEntityType
import com.openbank.fx.application.port.out.ScreeningSubject
import com.openbank.fx.infrastructure.client.PartyNameAdapter
import com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #15723 — `POST /api/v1/fx/convert` must sanctions-screen the names the BANK holds for the
 * converting party, never a name the caller sends.
 *
 * Before the fix the request's `partyName` was the screened subject, so the caller chose the name
 * it was cleared under: a listed party converted freely by sending any clean name. The first test
 * below is that request; on the unfixed code it answered `SETTLED`.
 *
 * Driven through the real endpoint, request body included, because the defect lived in what the
 * resource did with a request field: a use-case test starting from a `ConvertCommand` cannot show
 * that a field of the HTTP body no longer reaches the screen.
 *
 * The two collaborators are doubles at the adapter boundary. The party double is the bank's
 * record (id -> names and kind); the screening double, like sanctions-service, searches the
 * primary name AND every alias for [LISTED_NAME]. Every screened subject is recorded so the tests
 * can assert what reached the lists, not only the verdict.
 */
@QuarkusTest
@QuarkusTestResource(FxOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.fx.it.PostgresRedisTestResource::class)
class FxConvertScreensHeldNameIT {

    private val screened = CopyOnWriteArrayList<ScreeningSubject>()

    @BeforeEach
    fun collaborators() {
        screened.clear()

        val party = mockk<PartyNameAdapter>()
        coEvery { party.screeningSubject(any()) } answers { throw PartyNameUnavailableException(firstArg()) }
        coEvery { party.screeningSubject(LISTED_PERSON) } returns
            ScreeningSubject(listOf(LISTED_NAME), ScreeningEntityType.INDIVIDUAL)
        coEvery { party.screeningSubject(CLEAN_PERSON) } returns
            ScreeningSubject(listOf(CLEAN_NAME), ScreeningEntityType.INDIVIDUAL)
        coEvery { party.screeningSubject(COMPANY_WITH_LISTED_TRADING_NAME) } returns
            ScreeningSubject(listOf(CLEAN_COMPANY_NAME, LISTED_NAME), ScreeningEntityType.ORGANIZATION)
        QuarkusMock.installMockForType(party, PartyNameAdapter::class.java)

        val lists = mockk<SanctionsScreeningAdapter>()
        coEvery { lists.screen(any(), any(), any()) } answers {
            val subject = firstArg<ScreeningSubject>()
            screened += subject
            val hit = LISTED_NAME in subject.names
            ScreeningResult(
                subject = subject.primaryName,
                role = ScreeningRole.DEBTOR,
                status = if (hit) ScreeningMatchStatus.HIT else ScreeningMatchStatus.CLEAR,
                score = if (hit) 0.99 else 0.0,
                matchedEntity = if (hit) LISTED_NAME else null,
            )
        }
        QuarkusMock.installMockForType(lists, SanctionsScreeningAdapter::class.java)
    }

    /** Must-hit. The reproduction: on the unfixed code this answered SETTLED. */
    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a clean caller-supplied name does not clear a party whose held name is listed`() {
        assertThat(convert(LISTED_PERSON, suppliedName = CLEAN_NAME)).isEqualTo("FAILED")

        assertThat(screened.single().names).containsExactly(LISTED_NAME)
    }

    /**
     * Must-clear, and the control for the test above: the supplied name is not merely screened
     * *in addition* — it is not screened at all. A listed name typed into the request for a party
     * whose record is clean must not reach the lists, and omitting the field changes nothing.
     */
    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `the supplied name never reaches the screen and the field may be omitted`() {
        assertThat(convert(CLEAN_PERSON, suppliedName = LISTED_NAME)).isEqualTo("SETTLED")
        assertThat(convert(CLEAN_PERSON, suppliedName = null)).isEqualTo("SETTLED")

        assertThat(screened).hasSize(2)
        assertThat(screened.flatMap { it.names }).containsOnly(CLEAN_NAME)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `every held name is screened and a company is screened as an organisation`() {
        assertThat(convert(COMPANY_WITH_LISTED_TRADING_NAME, suppliedName = CLEAN_NAME)).isEqualTo("FAILED")

        val subject = screened.single()
        assertThat(subject.primaryName).isEqualTo(CLEAN_COMPANY_NAME)
        assertThat(subject.aliases).containsExactly(LISTED_NAME)
        assertThat(subject.entityType).isEqualTo(ScreeningEntityType.ORGANIZATION)
    }

    /** Fail closed: no record, no screen, no settlement — whatever name the caller offers. */
    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a party the bank holds no name for is held, never settled on the supplied name`() {
        assertThat(convert(UUID.randomUUID(), suppliedName = CLEAN_NAME)).isEqualTo("PENDING")

        assertThat(screened).isEmpty()
    }

    /** POSTs a conversion and answers the status the conversion was persisted with. */
    private fun convert(partyId: UUID, suppliedName: String?): String {
        val nameField = suppliedName?.let { "\"partyName\": \"$it\"," } ?: ""
        val created = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "held-name-it-${UUID.randomUUID()}")
            .body(
                """
                {
                  "partyId": "$partyId",
                  "accountId": null,
                  $nameField
                  "fromCurrency": "CZK",
                  "toCurrency": "EUR",
                  "fromAmountMinorUnits": 100000
                }
                """.trimIndent(),
            )
            .post("/api/v1/fx/convert")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/fx/convert: %s", created.body.asString())
            .isEqualTo(201)
        return created.jsonPath().getString("status")
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
        const val LISTED_NAME = "Listed Subject Example"
        const val CLEAN_NAME = "Clean Name Example"
        const val CLEAN_COMPANY_NAME = "Clean Legal Example s.r.o."

        // Literals: a QuarkusTestProfile-style second classloader must not hand out other ids.
        val LISTED_PERSON: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val CLEAN_PERSON: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val COMPANY_WITH_LISTED_TRADING_NAME: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    }
}
