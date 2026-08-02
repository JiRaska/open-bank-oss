// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.ApplyForLoanUseCase
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The intake endpoint is a security boundary before it is a feature, so these tests are written
 * against the ways it can WRONGLY admit a request, not the happy path alone:
 *  - a real operator (not the edge) submitting in a customer's name,
 *  - `caller-principal` left unset being read as "anyone may",
 *  - the party id arriving in the body instead of the trusted header,
 *  - the price arriving from the customer.
 *
 * Each is a case where the endpoint returning 201 would be a defect no downstream layer can catch:
 * the application it creates is indistinguishable from a genuine one.
 */
class CustomerIntakeResourceTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneOffset.UTC)
    private val partyId = UUID.fromString("05a02ef1-381c-40e7-b73f-d6855eead42e")
    private val edge = "service-account-openbank-edge"

    private fun config(
        enabled: Boolean = true,
        caller: String? = "service-account-openbank-edge",
        rate: BigDecimal? = BigDecimal("0.079"),
    ) = CustomerIntakeConfig(
        enabled = enabled,
        callerPrincipal = Optional.ofNullable(caller),
        nominalAnnualRate = Optional.ofNullable(rate),
    )

    private fun identity(name: String): SecurityIdentity =
        QuarkusSecurityIdentity.builder().setPrincipal(Principal { name }).build()

    private fun resource(
        config: CustomerIntakeConfig = config(),
        principal: String = edge,
        apply: RecordingApply = RecordingApply(),
    ) = CustomerIntakeResource(apply, config, identity(principal), clock) to apply

    private fun request(amount: String = "250000", term: Int = 48) = CustomerIntakeRequest(BigDecimal(amount), term)

    @Test
    fun `accepts an application from the edge principal and records the customer as maker`() {
        val (res, apply) = resource()

        val response = res.submit(partyId.toString(), request()).await().indefinitely()

        assertThat(response.status).isEqualTo(201)
        assertThat(apply.lastRequest?.partyId).isEqualTo(partyId)
        // The maker is namespaced. A customer maker must never be mistakable for a desk principal in
        // the ADR-0214 evidence trail, nor satisfy the checker leg of the four-eyes decision.
        assertThat(apply.lastActor).isEqualTo("customer:$partyId")
    }

    @Test
    fun `refuses an ordinary operator who is not the edge principal`() {
        val (res, apply) = resource(principal = "alice@openbank.local")

        val response = res.submit(partyId.toString(), request()).await().indefinitely()

        // @RolesAllowed(ROLE_OPERATOR) alone would have admitted this: the edge's M2M token carries
        // ROLE_OPERATOR and nothing else, so the role cannot distinguish the edge from a person.
        assertThat(response.status).isEqualTo(403)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `an unset caller-principal refuses everything rather than admitting any operator`() {
        val (res, apply) = resource(config = config(caller = null))

        val response = res.submit(partyId.toString(), request()).await().indefinitely()

        assertThat(response.status).isEqualTo(403)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `refuses when the feature is off`() {
        val (res, apply) = resource(config = config(enabled = false))

        assertThat(res.submit(partyId.toString(), request()).await().indefinitely().status).isEqualTo(403)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `refuses an unpriced product instead of guessing a rate`() {
        val (res, apply) = resource(config = config(rate = null))

        assertThat(res.submit(partyId.toString(), request()).await().indefinitely().status).isEqualTo(403)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `refuses a missing or malformed party header`() {
        val (res, apply) = resource()

        assertThat(res.submit(null, request()).await().indefinitely().status).isEqualTo(400)
        assertThat(res.submit("not-a-uuid", request()).await().indefinitely().status).isEqualTo(400)
        // The nil UUID is a real value that parses; it is also what an unset header downstream looks
        // like, so it must not become a party every customer shares.
        assertThat(res.submit("00000000-0000-0000-0000-000000000000", request()).await().indefinitely().status)
            .isEqualTo(400)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `refuses amounts and terms outside the configured product bounds`() {
        val (res, apply) = resource()

        assertThat(res.submit(partyId.toString(), request(amount = "4999")).await().indefinitely().status)
            .isEqualTo(400)
        assertThat(res.submit(partyId.toString(), request(amount = "1000001")).await().indefinitely().status)
            .isEqualTo(400)
        assertThat(res.submit(partyId.toString(), request(term = 5)).await().indefinitely().status).isEqualTo(400)
        assertThat(res.submit(partyId.toString(), request(term = 121)).await().indefinitely().status).isEqualTo(400)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `an over-scaled amount is a 400, not the 500 that Money's init would raise`() {
        val (res, apply) = resource()

        val response = res.submit(partyId.toString(), CustomerIntakeRequest(BigDecimal("250000.123"), 48))
            .await().indefinitely()

        assertThat(response.status).isEqualTo(400)
        assertThat(apply.lastRequest).isNull()
    }

    @Test
    fun `price jurisdiction and product come from configuration, never from the caller`() {
        val (res, apply) = resource()

        res.submit(partyId.toString(), request()).await().indefinitely()

        val submitted = requireNotNull(apply.lastRequest)
        assertThat(submitted.nominalAnnualRate).isEqualByComparingTo(BigDecimal("0.079"))
        // A customer-chosen jurisdiction would let the applicant pick which ADR-0212 compliance pack
        // judges them — the pack is keyed on (jurisdiction, productType).
        assertThat(submitted.jurisdiction).isEqualTo("CZ")
        assertThat(submitted.productType).isEqualTo("CONSUMER_CREDIT")
        assertThat(submitted.requestedAmount.currency.code).isEqualTo("CZK")
        // A full month of runway before instalment one, derived from the clock and not the request.
        assertThat(submitted.firstDueDate).isEqualTo(LocalDate.parse("2026-10-01"))
    }

    /** Records what the resource actually handed the use case — the assertions that matter are about
     *  the request the DESK layer will see, not about the HTTP status alone. */
    private class RecordingApply : ApplyForLoanUseCase {
        var lastRequest: LoanApplicationRequest? = null
        var lastActor: String? = null

        override fun apply(request: LoanApplicationRequest, proposedBy: String): Uni<LoanApplication> {
            lastRequest = request
            lastActor = proposedBy
            return Uni.createFrom().nullItem()
        }

        override fun decide(id: LoanApplicationId, decision: DecisionRequest, decidedBy: String) =
            unsupported<LoanApplication>()
        override fun getApplication(id: LoanApplicationId): Uni<LoanApplication?> = unsupported()
        override fun listApplications(partyId: UUID) = unsupported<List<LoanApplication>>()
        override fun advance(id: LoanApplicationId, actor: String) = unsupported<LoanApplication>()
        override fun expireIfInState(id: LoanApplicationId, expectedState: String, actor: String) =
            unsupported<LoanApplication>()
        override fun advanceIfInState(id: LoanApplicationId, expectedState: String, actor: String) =
            unsupported<LoanApplication>()
        override fun listRecentApplications(status: String?, limit: Int) = unsupported<List<LoanApplication>>()

        private fun <T> unsupported(): Uni<T> = throw UnsupportedOperationException("not used by intake")
    }
}
