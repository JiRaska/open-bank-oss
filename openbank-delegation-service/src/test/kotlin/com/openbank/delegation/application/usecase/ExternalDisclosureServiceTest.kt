// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.IssueExternalDisclosureCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.ExternalDisclosureDocumentExporter
import com.openbank.delegation.application.port.out.ExternalDisclosureRepository
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.ExternalDisclosure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExternalDisclosureServiceTest {
    private val now = OffsetDateTime.parse("2026-09-08T12:00:00Z")
    private val clock = Clock.fixed(Instant.from(now), ZoneOffset.UTC)
    private val grantor = UUID.randomUUID()
    private val documentId = UUID.randomUUID()
    private val grant = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = UUID.randomUUID(),
        resourceType = DelegationResourceType.DOCUMENT,
        resourceId = documentId,
        capabilities = setOf(DelegationCapability.OBJECT_READ),
        approvalPolicy = ApprovalPolicy.SOLO,
        // ADR-0375 D6. Deliberately NO exposure: the lifecycle cannot produce an ACTIVE grant that
        // carries it (offer refuses it, `accept` and `reinstate` refuse it), so a fixture with
        // exposure tests a state production cannot reach — which is exactly how `issue` stayed
        // green while being unreachable for every real grant.
        validFrom = now.minusDays(1),
        validTo = now.plusDays(3),
        status = DelegationStatus.ACTIVE,
        createdAt = now.minusDays(1),
        updatedAt = now.minusDays(1),
    )

    @Test
    fun `issues a bounded disclosure but persists only link and OTP hashes`(): Unit = runBlocking {
        val delegations = mockk<DelegationRepository>()
        val disclosures = mockk<ExternalDisclosureRepository>()
        coEvery { delegations.findById(grant.id) } returns grant
        coEvery { disclosures.issue(any()) } answers { firstArg() }
        val service = ExternalDisclosureService(
            delegations,
            disclosures,
            mockk<ExternalDisclosureDocumentExporter>(),
            clock,
            SecureRandom(),
        )

        val issued = service.issue(command(grantor))

        assertThat(issued.linkSecret).isNotBlank()
        assertThat(issued.otp).matches("\\d{6}")
        assertThat(issued.disclosure.linkSecretHash).isNotEqualTo(issued.linkSecret)
        assertThat(issued.disclosure.otpHash).isNotEqualTo(issued.otp)
        assertThat(issued.disclosure.verifyOtpAttempt(issued.otp, now)).isNotNull
        coVerify(exactly = 1) { disclosures.issue(any()) }
    }

    @Test
    fun `refuses disclosure issuance by a party other than the grantor`(): Unit = runBlocking {
        val delegations = mockk<DelegationRepository>()
        val disclosures = mockk<ExternalDisclosureRepository>()
        coEvery { delegations.findById(grant.id) } returns grant
        val service = ExternalDisclosureService(
            delegations,
            disclosures,
            mockk<ExternalDisclosureDocumentExporter>(),
            clock,
            SecureRandom(),
        )

        assertThatThrownBy { runBlocking { service.issue(command(UUID.randomUUID())) } }
            .isInstanceOf(jakarta.ws.rs.ForbiddenException::class.java)
        coVerify(exactly = 0) { disclosures.issue(any()) }
    }

    @Test
    fun `failed sealed export never consumes an external disclosure view`(): Unit = runBlocking {
        val disclosureId = UUID.randomUUID()
        val linkSecret = "opaque-link-secret"
        val disclosure = ExternalDisclosure(
            id = disclosureId,
            delegationId = grant.id,
            documentId = documentId,
            recipientLabel = "External accountant",
            linkSecretHash = ExternalDisclosure.secretHash(disclosureId, linkSecret),
            otpHash = ExternalDisclosure.secretHash(disclosureId, "248913"),
            expiresAt = now.plusDays(2),
            maxViews = 1,
            createdAt = now.minusMinutes(1),
            verifiedAt = now,
        )
        val delegations = mockk<DelegationRepository>()
        val disclosures = mockk<ExternalDisclosureRepository>()
        val exporter = mockk<ExternalDisclosureDocumentExporter>()
        coEvery { disclosures.findById(disclosureId) } returns disclosure
        coEvery { exporter.export(any(), any(), any(), any()) } throws IllegalStateException("document unavailable")
        val service = ExternalDisclosureService(delegations, disclosures, exporter, clock, SecureRandom())

        assertThatThrownBy { runBlocking { service.release(disclosureId, linkSecret) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("document unavailable")
        coVerify(exactly = 0) { disclosures.mutateById(any(), any()) }
    }

    private fun command(caller: UUID): IssueExternalDisclosureCommand = IssueExternalDisclosureCommand(
        delegationId = grant.id,
        documentId = documentId,
        recipientLabel = "External accountant",
        expiresAt = now.plusDays(2),
        maxViews = 1,
        callerPartyId = caller,
    )

    /**
     * The regression that matters, and the one the old fixture could not express.
     *
     * `issue` required `grant.exposure` to be non-null. `DelegationService` refuses exposure on
     * every new offer and on `accept`/`reinstate`, so no grant can be ACTIVE and carry it — the
     * requirement threw for every input a real caller could present, and the sealed-disclosure
     * mechanism that ADR-0375 D6 names as the END STATE was dead code behind a green test.
     *
     * This asserts the shape the lifecycle actually produces: an ACTIVE DOCUMENT grant with
     * OBJECT_READ and NO exposure issues a disclosure, and the view limit is the disclosure's own.
     */
    @Test
    fun `issues for the only grant shape the lifecycle can produce — active, no exposure`(): Unit = runBlocking {
        val delegations = mockk<DelegationRepository>()
        val disclosures = mockk<ExternalDisclosureRepository>()
        coEvery { delegations.findById(grant.id) } returns grant
        coEvery { disclosures.issue(any()) } answers { firstArg() }
        val service = ExternalDisclosureService(
            delegations,
            disclosures,
            mockk<ExternalDisclosureDocumentExporter>(),
            clock,
            SecureRandom(),
        )

        val issued = service.issue(
            IssueExternalDisclosureCommand(
                delegationId = grant.id,
                documentId = documentId,
                recipientLabel = "auditor",
                expiresAt = now.plusDays(2),
                maxViews = 3,
                callerPartyId = grantor,
            ),
        )

        assertThat(grant.exposure).describedAs("the fixture must stay in a reachable shape").isNull()
        assertThat(issued.disclosure.maxViews).isEqualTo(3)
        assertThat(issued.disclosure.delegationId).isEqualTo(grant.id)
    }
}
