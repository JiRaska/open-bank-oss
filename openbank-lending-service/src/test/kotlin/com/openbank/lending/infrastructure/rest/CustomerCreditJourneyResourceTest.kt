// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.domain.model.ApplicationStateSummary
import com.openbank.lending.domain.model.DecisionOutcomeSummary
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.origination.CreditProductKind
import com.openbank.libs.lending.origination.OriginationState
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

/**
 * Like the intake POST, this GET is a security boundary before it is a feature — it hands back
 * somebody's credit history. The tests are therefore written against the ways it could wrongly
 * DISCLOSE: a caller that is not the edge, a party header that is not the one the row belongs to,
 * and an application id that belongs to someone else.
 */
class CustomerCreditJourneyResourceTest {

    private val edge = "service-account-openbank-edge"
    private val party = UUID.fromString("05a02ef1-381c-40e7-b73f-d6855eead42e")
    private val stranger = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun config(enabled: Boolean = true, caller: String? = "service-account-openbank-edge") =
        CustomerIntakeConfig(
            enabled = enabled,
            callerPrincipal = Optional.ofNullable(caller),
            jurisdiction = "CZ",
            productType = "CONSUMER_CREDIT",
            currency = "CZK",
            nominalAnnualRate = Optional.of(BigDecimal("0.079")),
            minAmount = BigDecimal("5000"),
            maxAmount = BigDecimal("1000000"),
            minTermMonths = 6,
            maxTermMonths = 120,
        )

    private fun identity(name: String): SecurityIdentity =
        QuarkusSecurityIdentity.builder().setPrincipal(Principal { name }).build()

    private fun application(
        owner: UUID = party,
        state: OriginationState = OriginationState.ASSESSMENT,
        kind: CreditProductKind = CreditProductKind.UNSECURED,
        id: UUID = UUID.randomUUID(),
    ) = LoanApplication(
        id = LoanApplicationId(id),
        partyId = owner,
        requestedAmount = Money.of(BigDecimal("250000"), "CZK"),
        nominalAnnualRate = BigDecimal("0.079"),
        termPeriods = 48,
        firstDueDate = LocalDate.parse("2026-10-01"),
        status = state,
        productKind = kind,
        proposedBy = "customer:$owner",
        createdAt = OffsetDateTime.parse("2026-08-01T10:00:00Z"),
    )

    private fun resource(
        rows: List<LoanApplication>,
        principal: String = edge,
        config: CustomerIntakeConfig = config(),
    ) = CustomerCreditJourneyResource(StubRepository(rows), config, identity(principal))

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(response: jakarta.ws.rs.core.Response): List<CustomerCreditJourneyDto> =
        response.entity as List<CustomerCreditJourneyDto>

    // ── Disclosure ────────────────────────────────────────────────────────────

    @Test
    fun `the edge principal reads the party's own applications`() {
        val response = resource(listOf(application())).list(party.toString()).await().indefinitely()
        assertThat(response.status).isEqualTo(200)
        assertThat(bodyOf(response)).hasSize(1)
    }

    @Test
    fun `an operator that is not the edge is refused`() {
        val response = resource(listOf(application()), principal = "service-account-someone-else")
            .list(party.toString()).await().indefinitely()
        assertThat(response.status).isEqualTo(403)
    }

    @Test
    fun `an unset caller-principal refuses everyone rather than admitting any operator`() {
        val response = resource(listOf(application()), config = config(caller = null))
            .list(party.toString()).await().indefinitely()
        assertThat(response.status).isEqualTo(403)
    }

    @Test
    fun `a disabled intake serves nothing`() {
        val response = resource(listOf(application()), config = config(enabled = false))
            .list(party.toString()).await().indefinitely()
        assertThat(response.status).isEqualTo(403)
    }

    @Test
    fun `a missing party header is refused, not treated as fleet-wide`() {
        // 400, not 403: the caller IS the edge, the request just carries no scope. Keeping the two
        // apart keeps 403 meaning exactly one thing — "you are not the edge".
        val response = resource(listOf(application())).list(null).await().indefinitely()
        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `a well-formed party header does not earn access for a caller that is not the edge`() {
        // The permission decision must not depend on request data at all (CodeQL
        // java/tainted-permissions-check): a perfect header from the wrong principal is still 403.
        val response = resource(listOf(application()), principal = "service-account-someone-else")
            .list(party.toString()).await().indefinitely()
        assertThat(response.status).isEqualTo(403)
    }

    @Test
    fun `another party's application reads as not found, never as someone else's credit history`() {
        val theirs = application(owner = stranger)
        val response = resource(listOf(theirs)).one(party.toString(), theirs.id.value.toString())
            .await().indefinitely()
        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `a malformed application id is a 404, not a 500`() {
        val response = resource(listOf(application())).one(party.toString(), "not-a-uuid").await().indefinitely()
        assertThat(response.status).isEqualTo(404)
    }

    // ── Projection ────────────────────────────────────────────────────────────

    @Test
    fun `the journey renders the product's own steps and the state it is actually in`() {
        val row = application(state = OriginationState.OFFERED, kind = CreditProductKind.REVOLVING)
        val dto = bodyOf(resource(listOf(row)).list(party.toString()).await().indefinitely()).single()
        assertThat(dto.productKind).isEqualTo("REVOLVING")
        assertThat(dto.state).isEqualTo("OFFERED")
        assertThat(dto.steps.map { it.code }).contains("ACTIVATE_LIMIT").doesNotContain("DISBURSE")
        assertThat(dto.steps.first { it.code == "OFFER" }.status).isEqualTo("CURRENT")
    }

    @Test
    fun `no price of any kind is exposed — rate, instalment and APRC are ADR-0269 rule 4`() {
        assertThat(CustomerCreditJourneyDto::class.java.declaredFields.map { it.name.lowercase() })
            .noneMatch { it.contains("rate") || it.contains("apr") || it.contains("instal") }
    }

    @Test
    fun `awaitingCustomer is empty while no requirement collection exists`() {
        val dto = bodyOf(resource(listOf(application())).list(party.toString()).await().indefinitely()).single()
        assertThat(dto.awaitingCustomer).isEmpty()
    }

    private class StubRepository(private val rows: List<LoanApplication>) : LoanApplicationRepository {
        override fun save(application: LoanApplication): Uni<LoanApplication> = Uni.createFrom().item(application)

        override fun findById(id: LoanApplicationId): Uni<LoanApplication?> =
            Uni.createFrom().item(rows.firstOrNull { it.id == id })

        override fun findByParty(partyId: UUID): Uni<List<LoanApplication>> =
            Uni.createFrom().item(rows.filter { it.partyId == partyId })

        override fun findRecent(status: String?, limit: Int): Uni<List<LoanApplication>> =
            Uni.createFrom().item(emptyList())

        override fun summariseByState(): Uni<List<ApplicationStateSummary>> = Uni.createFrom().item(emptyList())

        override fun findEvaluated(limit: Int): Uni<List<LoanApplication>> =
            Uni.createFrom().item(rows.filter { it.decidedEngineAt != null }.take(limit))

        override fun summariseDecisions(): Uni<List<DecisionOutcomeSummary>> = Uni.createFrom().item(emptyList())

        override fun update(application: LoanApplication): Uni<LoanApplication> = Uni.createFrom().item(application)

        override fun compareAndSetStatus(
            id: LoanApplicationId,
            from: OriginationState,
            to: OriginationState,
            decidedBy: String?,
            decisionReason: String?,
            decidedAt: OffsetDateTime?,
        ): Uni<Int> = Uni.createFrom().item(0)
    }
}
