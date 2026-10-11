// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.onboarding.TransferRequestRepository
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.transfer.Counterparty
import com.openbank.pension.domain.transfer.TransferDirection
import com.openbank.pension.domain.transfer.TransferOrigin
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import com.openbank.pension.infrastructure.onboarding.temporal.onWorker
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ContractFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * #12435 gap (a): the participant LISTS their pending transfers (incoming and outgoing) and
 * WITHDRAWS a transfer-out — their own request, or the consent a receiving provider waits for —
 * before it executes. Over real HTTP against real Postgres.
 *
 * Transfers are seeded straight into the repository in the status under test, so no workflow
 * races the assertion; the workflow's side of a withdrawal (valuate finds CANCELLED and sells
 * nothing) is the domain's transition table, held in `TransferRequestTest`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionTransferWithdrawalApiIT {

    private val transfers = "/api/v2/pension/transfers"
    private val party: UUID = UUID.randomUUID()

    @Inject
    lateinit var useCase: PensionContractUseCase

    @Inject
    lateinit var contracts: PensionContractRepository

    @Inject
    lateinit var repository: TransferRequestRepository

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        asParty: UUID? = party,
        idempotencyKey: String? = UUID.randomUUID().toString(),
    ): ValidatableResponse = given().contentType("application/json")
        .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
        .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
        .apply { if (body != null) body(body) }
        .`when`().request(method, path).then()

    private fun challenge() = """{"scaChallengeId":"sca-${UUID.randomUUID()}"}"""

    private fun activeContract(owner: UUID = party): UUID =
        onWorker { ContractFixtures.activeContract(useCase, contracts, owner) }

    /** A transfer seeded in [status]; OUT by default, and provider-requested when awaiting consent. */
    private fun seed(
        contractId: UUID,
        status: TransferStatus,
        direction: TransferDirection = TransferDirection.OUT,
        owner: UUID = party,
    ): String {
        val now = Instant.now()
        val origin = if (status == TransferStatus.AWAITING_CONSENT) {
            TransferOrigin.RECEIVING_PROVIDER
        } else {
            TransferOrigin.PARTICIPANT
        }
        var transfer = TransferRequest.request(
            direction = direction,
            origin = origin,
            contractId = contractId,
            partyId = owner,
            counterparty = Counterparty("P2", "Receiving", "C-${UUID.randomUUID()}"),
            currency = "CZK",
            signatureRef = if (origin == TransferOrigin.PARTICIPANT) "sca-seed" else null,
            deadline = LocalDate.now().plusDays(30),
            now = now,
        )
        if (status == TransferStatus.VALUATED) transfer = transfer.valuated(BigDecimal("1000"), BigDecimal.ZERO, now)
        check(transfer.status == status) { "seeded ${transfer.status}, wanted $status" }
        return onWorker { repository.save(transfer) }.id.toString()
    }

    private fun stored(id: String): TransferRequest =
        requireNotNull(onWorker { repository.findById(UUID.fromString(id)) })

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `the participant lists exactly their own pending transfers, in either direction`() {
        val contract = activeContract()
        val awaiting = seed(contract, TransferStatus.AWAITING_CONSENT)
        val incoming = seed(contract, TransferStatus.REQUESTED, TransferDirection.IN)
        // One open transfer-out per contract (uq_pension_transfer_out_open): a second contract.
        val ended = seed(activeContract(), TransferStatus.REQUESTED)
        call("POST", "$transfers/$ended/withdrawal", challenge()).statusCode(200)
        val stranger = UUID.randomUUID()
        val theirs = seed(activeContract(stranger), TransferStatus.AWAITING_CONSENT, owner = stranger)

        call("GET", transfers).statusCode(200)
            .body("transferId", containsInAnyOrder(awaiting, incoming))
        call("GET", "$transfers?direction=OUT").statusCode(200).body("transferId", containsInAnyOrder(awaiting))
        call("GET", "$transfers?direction=IN").statusCode(200).body("transferId", containsInAnyOrder(incoming))
        call("GET", "$transfers?pending=false").statusCode(200)
            .body("transferId", containsInAnyOrder(awaiting, incoming, ended))
            .body("transferId", not(hasItem(theirs)))
        // IDOR: another party's list never carries ours, and a party with none sees an empty list.
        call("GET", transfers, asParty = stranger).statusCode(200)
            .body("transferId", containsInAnyOrder(theirs))
        call("GET", transfers, asParty = UUID.randomUUID()).statusCode(200).body("$", empty<Any>())
        call("GET", transfers, asParty = null).statusCode(400)
        call("GET", "$transfers?limit=0").statusCode(400)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a consent the provider waits for and a not-yet-valued request are both withdrawn, and a retry is a no-op`() {
        val contract = activeContract()
        val awaiting = seed(contract, TransferStatus.AWAITING_CONSENT)
        val signed = challenge()
        val key = UUID.randomUUID().toString()
        call("POST", "$transfers/$awaiting/withdrawal", signed, idempotencyKey = key).statusCode(200)
            .body("status", equalTo("CANCELLED"))
        assertThat(stored(awaiting).failureReason).isEqualTo(TransferRequest.WITHDRAWN_BY_PARTICIPANT)
        // A retry presents the challenge already spent; it answers the same outcome, spends nothing.
        call("POST", "$transfers/$awaiting/withdrawal", signed, idempotencyKey = key).statusCode(200)
            .body("status", equalTo("CANCELLED"))
        // Consent to a withdrawn request is no longer possible.
        call("POST", "$transfers/$awaiting/consent", challenge()).statusCode(409)

        val requested = seed(contract, TransferStatus.REQUESTED)
        call("POST", "$transfers/$requested/withdrawal", challenge()).statusCode(200)
            .body("status", equalTo("CANCELLED"))
        assertThat(stored(requested).status).isEqualTo(TransferStatus.CANCELLED)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a withdrawal is refused once valuation began, for a stranger, an incoming transfer, or without SCA and key`() {
        val contract = activeContract()
        val valued = seed(contract, TransferStatus.VALUATED)
        call("POST", "$transfers/$valued/withdrawal", challenge()).statusCode(409)
        assertThat(stored(valued).status).isEqualTo(TransferStatus.VALUATED)

        val awaiting = seed(activeContract(), TransferStatus.AWAITING_CONSENT)
        // IDOR: another party's transfer reads as absent and is left untouched.
        call("POST", "$transfers/$awaiting/withdrawal", challenge(), asParty = UUID.randomUUID()).statusCode(404)
        call("POST", "$transfers/${UUID.randomUUID()}/withdrawal", challenge()).statusCode(404)
        // Idempotency-Key and an SCA challenge are both required; a rejected challenge changes nothing.
        call("POST", "$transfers/$awaiting/withdrawal", challenge(), idempotencyKey = null).statusCode(400)
        call("POST", "$transfers/$awaiting/withdrawal", "{}").statusCode(400)
        call("POST", "$transfers/$awaiting/withdrawal", """{"scaChallengeId":"rejected-1"}""").statusCode(422)
        assertThat(stored(awaiting).status).isEqualTo(TransferStatus.AWAITING_CONSENT)

        val incoming = seed(contract, TransferStatus.REQUESTED, TransferDirection.IN)
        call("POST", "$transfers/$incoming/withdrawal", challenge()).statusCode(409)
        assertThat(stored(incoming).status).isEqualTo(TransferStatus.REQUESTED)
    }

    @Test
    @TestSecurity(user = "some-other-service", roles = ["ROLE_API"])
    fun `a party header from a principal that is not the trusted relay is refused`() {
        val awaiting = seed(activeContract(), TransferStatus.AWAITING_CONSENT)
        call("GET", transfers).statusCode(403)
        call("POST", "$transfers/$awaiting/withdrawal", challenge()).statusCode(403)
        assertThat(stored(awaiting).status).isEqualTo(TransferStatus.AWAITING_CONSENT)
    }
}
