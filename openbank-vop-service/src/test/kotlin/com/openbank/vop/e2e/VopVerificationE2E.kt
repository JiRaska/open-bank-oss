// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Verification of Payee (ADR-0171, IPR Art. 5c) over the real HTTP surface: the full chain
 * caller → endpoint → two-hop name resolution (account-service → party-service, stood in for by
 * [VopDownstreamWireMockResource] over real HTTP) → match policy → hashed evidence row in real
 * Postgres, with every durable fact asserted over plain JDBC.
 *
 * Every journey uses a distinct `@TestSecurity` user because the rate limiter keys its fixed
 * window on the requester principal — a shared user would let one journey's calls consume
 * another's window under the deliberately low `requests-per-minute` the profile sets.
 *
 * The journeys are ORDERED, deliberately: the `lookup failure` journey's 503s count as genuine
 * failures on [com.openbank.vop.infrastructure.adapter.AccountHolderNameLookupAdapter]'s circuit
 * breaker, and its rolling window opens the breaker for 10 s — any lookup-heavy journey placed
 * after it would see `lookup_unavailable` answers that belong to the breaker, not to the journey.
 * It runs last so the breaker can open without poisoning anyone.
 */
@QuarkusTest
@TestProfile(VopE2EProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VopVerificationE2E : VopE2EBase() {

    @BeforeEach
    fun resetDownstream() = VopDownstreamWireMockResource.reset()

    @Test
    @Order(1)
    @TestSecurity(user = "vop-e2e-payer-a", roles = ["ROLE_PAYMENTS"])
    fun `exact name verifies match and a legal form is presentation not identity`() {
        val personIban = "CZ860101000000000012345678"
        val companyIban = "CZ280101000000000087654321"
        VopDownstreamWireMockResource.stubAccountFound(personIban, PARTY_A)
        VopDownstreamWireMockResource.stubPartyName(PARTY_A, legalName = "Jiří Raška")
        VopDownstreamWireMockResource.stubAccountFound(companyIban, PARTY_B)
        VopDownstreamWireMockResource.stubPartyName(PARTY_B, legalName = "Acme s.r.o.")

        val person = verify(mapOf("creditorIban" to personIban, "creditorName" to "Jiří Raška"), 200)
        assertThat(person.get("status").asText()).isEqualTo("match")
        assertThat(person.get("matchedName").isNull).isTrue()

        val company = verify(mapOf("creditorIban" to companyIban, "creditorName" to "Acme"), 200)
        assertThat(company.get("status").asText()).isEqualTo("match")

        val personRow = evidenceRow(personIban, "Jiří Raška")
        assertThat(personRow).isNotNull()
        assertThat(personRow!!.outcome).isEqualTo("MATCH")
        assertThat(personRow.noDataReason).isNull()
        assertThat(personRow.requestedBy).isEqualTo("vop-e2e-payer-a")
        assertThat(personRow.verifiedAt).isBetween(java.time.Instant.now().minusSeconds(120), java.time.Instant.now())
    }

    @Test
    @Order(2)
    @TestSecurity(user = "vop-e2e-payer-b", roles = ["ROLE_OPERATOR"])
    fun `close match discloses the held name and no match never does`() {
        val iban = "CZ350101000000000055511111"
        VopDownstreamWireMockResource.stubAccountFound(iban, PARTY_C)
        VopDownstreamWireMockResource.stubPartyName(PARTY_C, legalName = "Jiří Raška")

        // Reordered tokens: the payer knows the name, their PSP's field order differs.
        val close = verify(mapOf("creditorIban" to iban, "creditorName" to "Raška Jiří"), 200)
        assertThat(close.get("status").asText()).isEqualTo("close_match")
        assertThat(close.get("matchedName").asText()).isEqualTo("Jiří Raška")

        val no = verify(mapOf("creditorIban" to iban, "creditorName" to "Jan Novák"), 200)
        assertThat(no.get("status").asText()).isEqualTo("no_match")
        assertThat(no.get("matchedName").isNull).isTrue()

        assertThat(evidenceRow(iban, "Raška Jiří")!!.outcome).isEqualTo("CLOSE_MATCH")
        val noRow = evidenceRow(iban, "Jan Novák")
        assertThat(noRow!!.outcome).isEqualTo("NO_MATCH")
        assertThat(noRow.noDataReason).isNull()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "vop-e2e-payer-c", roles = ["ROLE_VIEWER"])
    fun `unknown domestic iban answers no_data account_not_found without retrying`() {
        val iban = "CZ560101000000000044445555"
        VopDownstreamWireMockResource.stubAccountNotFound(iban)

        val answer = verify(mapOf("creditorIban" to iban, "creditorName" to "Jiří Raška"), 200)
        assertThat(answer.get("status").asText()).isEqualTo("no_data")
        assertThat(answer.get("reason").asText()).isEqualTo("account_not_found")

        // A 404 is an answer, not a failure: the adapter recovers it inside the guarded method, so
        // @Retry must NOT replay it and @CircuitBreaker must NOT count it.
        assertThat(VopDownstreamWireMockResource.accountRequestsFor(iban)).isEqualTo(1)
        val row = evidenceRow(iban, "Jiří Raška")
        assertThat(row!!.outcome).isEqualTo("NO_DATA")
        assertThat(row.noDataReason).isEqualTo("ACCOUNT_NOT_FOUND")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "vop-e2e-payer-d", roles = ["ROLE_ADMIN"])
    fun `account without a party link answers no_data honestly`() {
        val iban = "CZ150101000000000077778888"
        VopDownstreamWireMockResource.stubAccountWithoutParty(iban)

        val answer = verify(mapOf("creditorIban" to iban, "creditorName" to "Kdokoliv"), 200)
        assertThat(answer.get("status").asText()).isEqualTo("no_data")
        // Deliberately indistinguishable from ACCOUNT_NOT_FOUND on the wire: "not our account" is
        // itself information an enumerator would want (ADR-0171 §4).
        assertThat(answer.get("reason").asText()).isEqualTo("account_not_found")
        assertThat(evidenceRow(iban, "Kdokoliv")!!.outcome).isEqualTo("NO_DATA")
    }

    @Test
    @Order(11)
    @TestSecurity(user = "vop-e2e-payer-e", roles = ["ROLE_PAYMENTS"])
    fun `lookup failure fails open loudly after the retry fan-out`() {
        val iban = "CZ660101000000000099990000"
        VopDownstreamWireMockResource.stubAccountUnavailable(iban)

        val answer = verify(mapOf("creditorIban" to iban, "creditorName" to "Jiří Raška"), 200)
        assertThat(answer.get("status").asText()).isEqualTo("no_data")
        assertThat(answer.get("reason").asText()).isEqualTo("lookup_unavailable")

        // @Retry(maxRetries = 2) replays the 503 across a real wire before the service recovers
        // it — the resilience annotations actually fire, through the CDI proxy. The circuit
        // breaker's rolling window (failureRatio 0.5 over the last 4) can open after the second
        // consecutive 503 and answer the third replay itself, so the wire sees two or three hits;
        // one would mean no retry ever fired.
        assertThat(VopDownstreamWireMockResource.accountRequestsFor(iban)).isGreaterThanOrEqualTo(2)
        val row = evidenceRow(iban, "Jiří Raška")
        assertThat(row!!.outcome).isEqualTo("NO_DATA")
        assertThat(row.noDataReason).isEqualTo("LOOKUP_UNAVAILABLE")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "vop-e2e-payer-f", roles = ["ROLE_PAYMENTS"])
    fun `foreign iban answers no_data no_scheme_connectivity and never fans out`() {
        val iban = "DE89370400440532013000"

        val answer = verify(mapOf("creditorIban" to iban, "creditorName" to "Max Mustermann"), 200)
        assertThat(answer.get("status").asText()).isEqualTo("no_data")
        assertThat(answer.get("reason").asText()).isEqualTo("no_scheme_connectivity")
        assertThat(VopDownstreamWireMockResource.accountNeverRequested()).isZero()
        assertThat(evidenceRow(iban, "Max Mustermann")!!.noDataReason).isEqualTo("NO_SCHEME_CONNECTIVITY")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "vop-e2e-valid-a", roles = ["ROLE_OPERATOR"])
    fun `missing and malformed fields are refused before any downstream call`() {
        verify(mapOf("creditorName" to "Jiří Raška"), 400)
        verify(mapOf("creditorIban" to "CZ860101000000000012345678"), 400)
        verify(mapOf("creditorIban" to "CZ12", "creditorName" to "Jiří Raška"), 400)
        assertThat(VopDownstreamWireMockResource.accountNeverRequested()).isZero()
    }

    @Test
    @Order(7)
    @TestSecurity(user = "vop-e2e-valid-b", roles = ["ROLE_OPERATOR"])
    fun `oversized fields are refused and nothing persists`() {
        val oversizedIban = "C" + "Z860101000000000012345678".padEnd(34, '0')
        verify(mapOf("creditorIban" to oversizedIban, "creditorName" to "Jiří Raška"), 400)
        verify(mapOf("creditorIban" to "CZ860101000000000012345678", "creditorName" to "x".repeat(141)), 400)
        verify(mapOf("creditorIban" to "  ", "creditorName" to "Jiří Raška"), 400)
        assertThat(VopDownstreamWireMockResource.accountNeverRequested()).isZero()
    }

    @Test
    @Order(10)
    @TestSecurity(user = "vop-e2e-enumerator", roles = ["ROLE_PAYMENTS"])
    fun `rate limit is the enumeration control and refusals record no evidence`() {
        val iban = "CZ860101000000000012345678"
        VopDownstreamWireMockResource.stubAccountFound(iban, PARTY_A)
        VopDownstreamWireMockResource.stubPartyName(PARTY_A, legalName = "Jiří Raška")
        val request = mapOf("creditorIban" to iban, "creditorName" to "Jiří Raška")

        // Profile sets requests-per-minute = 4: the first four pass, the fifth is refused.
        repeat(4) { verify(request, 200) }
        val refused = verify(request, 429)
        assertThat(refused.get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED")

        // The 429 aborts in the request filter, before the resource ever runs: four evidence rows,
        // not five — a refused probe leaves no trace an attacker could harvest.
        assertThat(evidenceCountFor("MATCH", "vop-e2e-enumerator")).isEqualTo(4)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "vop-e2e-outsider", roles = ["ROLE_RISK"])
    fun `caller without a permitted role is refused before any downstream call`() {
        verify(mapOf("creditorIban" to "CZ860101000000000012345678", "creditorName" to "Jiří Raška"), 403)
        assertThat(VopDownstreamWireMockResource.accountNeverRequested()).isZero()
    }

    @Test
    @Order(9)
    fun `anonymous caller is unauthenticated before any downstream call`() {
        verify(mapOf("creditorIban" to "CZ860101000000000012345678", "creditorName" to "Jiří Raška"), 401)
        assertThat(VopDownstreamWireMockResource.accountNeverRequested()).isZero()
    }

    private companion object {
        const val PARTY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val PARTY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val PARTY_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }
}
