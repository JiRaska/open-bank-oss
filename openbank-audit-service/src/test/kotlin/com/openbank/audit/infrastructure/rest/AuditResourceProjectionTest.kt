// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import com.openbank.audit.application.AnchorVerificationKey
import com.openbank.audit.application.AuditAnchorService
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.ChainVerification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The read side's PROJECTIONS and BOUNDS, which the annotation-only contract tests cannot see.
 *
 * Two classes of defect live here and nowhere else: a customer-facing view that leaks the raw
 * chain-hashed payload it is documented not to project, and a caller-supplied `limit` that
 * reaches the database uncoerced.
 */
class AuditResourceProjectionTest {

    private val repo = mockk<AuditRepository>()
    private val anchors = mockk<AuditAnchorService>()

    private val resource = AuditResource().also {
        it.repo = repo
        it.anchors = anchors
    }

    private fun entry(
        eventType: String = "PAYMENT_EXECUTED",
        payload: String = """{"operation":"payments.domestic"}""",
        actorId: String? = "delegate-9",
        onBehalfOf: String? = "grantor-1",
        delegationId: String? = "grant-7",
    ) = AuditEntry(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
        eventType = eventType,
        aggregateType = "PAYMENT",
        aggregateId = "pay-1",
        actorId = actorId,
        actorType = "HUMAN",
        payload = payload,
        sourceService = "domestic-payment",
        correlationId = "corr-1",
        occurredAt = Instant.parse("2026-06-01T10:00:00Z"),
        recordedAt = Instant.parse("2026-06-01T10:00:01Z"),
        occurredAtSource = OccurredAtSource.EVENT,
        onBehalfOf = onBehalfOf,
        delegationId = delegationId,
    )

    @Suppress("UNCHECKED_CAST")
    private fun rowsOf(response: jakarta.ws.rs.core.Response) =
        response.entity as List<Map<String, String?>>

    @Test
    fun `the customer access log projects metadata only, never the chain-hashed payload`(): Unit = runBlocking {
        coEvery { repo.findByAggregateId("party-1", any()) } returns listOf(entry())

        val rows = rowsOf(resource.getCustomerAccessLog("party-1", 100))

        assertThat(rows).hasSize(1)
        assertThat(rows.first()).containsOnlyKeys(
            "eventType", "aggregateType", "actorType", "sourceService", "occurredAt",
        )
        assertThat(rows.first()["occurredAt"]).isEqualTo("2026-06-01T10:00:00Z")
    }

    @Test
    fun `a caller-supplied limit is clamped to the page bounds, in both directions`(): Unit = runBlocking {
        coEvery { repo.findByAggregateId(any(), any()) } returns emptyList()

        resource.getCustomerAccessLog("party-1", 0)
        resource.getCustomerAccessLog("party-1", -5)
        resource.getCustomerAccessLog("party-1", 100_000)

        coVerify(exactly = 2) { repo.findByAggregateId("party-1", 1) }
        coVerify(exactly = 1) { repo.findByAggregateId("party-1", 500) }
    }

    @Test
    fun `the audit trail and actor trail clamp their limits too`(): Unit = runBlocking {
        coEvery { repo.findByAggregateId(any(), any()) } returns emptyList()
        coEvery { repo.findByActorId(any(), any(), any()) } returns emptyList()

        resource.getAuditTrail("acct-1", 9999)
        resource.getActorTrail("actor-1", "ui", 0)

        coVerify { repo.findByAggregateId("acct-1", 500) }
        coVerify { repo.findByActorId("actor-1", "ui", 1) }
    }

    @Test
    fun `blank delegate and grant filters are dropped, not passed through as empty strings`(): Unit = runBlocking {
        coEvery { repo.findOnBehalfOf(any(), any(), any(), any()) } returns emptyList()

        resource.getDelegatedActionsForGrantor("grantor-1", "   ", "", 50)

        coVerify {
            repo.findOnBehalfOf(
                grantorPartyId = "grantor-1",
                delegatePartyId = null,
                delegationId = null,
                limit = 50,
            )
        }
    }

    @Test
    fun `a supplied delegate filter reaches the repository unchanged`(): Unit = runBlocking {
        coEvery { repo.findOnBehalfOf(any(), any(), any(), any()) } returns emptyList()

        resource.getDelegatedActionsForGrantor("grantor-1", "delegate-9", "grant-7", 1000)

        coVerify {
            repo.findOnBehalfOf(
                grantorPartyId = "grantor-1",
                delegatePartyId = "delegate-9",
                delegationId = "grant-7",
                limit = 500,
            )
        }
    }

    @Test
    fun `the grantor view reads the customer-facing operation out of the payload`(): Unit = runBlocking {
        coEvery { repo.findOnBehalfOf(any(), any(), any(), any()) } returns listOf(entry())

        val row = rowsOf(resource.getDelegatedActionsForGrantor("grantor-1", null, null, 100)).first()

        assertThat(row["operation"]).isEqualTo("payments.domestic")
        assertThat(row["delegatePartyId"]).isEqualTo("delegate-9")
        assertThat(row["delegationId"]).isEqualTo("grant-7")
        assertThat(row).doesNotContainKey("payload")
    }

    @Test
    fun `an unparseable or operation-less payload yields a null operation, not a failed request`(): Unit =
        runBlocking {
            coEvery { repo.findOnBehalfOf(any(), any(), any(), any()) } returns listOf(
                entry(payload = "not json at all"),
                entry(payload = """{"amount":10}"""),
            )

            val rows = rowsOf(resource.getDelegatedActionsForGrantor("grantor-1", null, null, 100))

            assertThat(rows.map { it["operation"] }).containsExactly(null, null)
        }

    @Test
    fun `integrity reports BROKEN and the first broken entry when the walk fails`(): Unit = runBlocking {
        val broken = UUID.randomUUID()
        coEvery { repo.verifyChain(null) } returns ChainVerification(
            intact = false,
            checked = 12,
            unchained = 3,
            firstBrokenEntryId = broken,
        )

        val body = resource.verifyIntegrity(null).entity as IntegrityResponse

        assertThat(body.chainStatus).isEqualTo("BROKEN")
        assertThat(body.checkedCount).isEqualTo(12)
        assertThat(body.unchainedCount).isEqualTo(3)
        assertThat(body.firstBrokenAt).isEqualTo(broken)
    }

    @Test
    fun `integrity starts the walk at a supplied anchor, whitespace tolerated`(): Unit = runBlocking {
        val from = UUID.randomUUID()
        coEvery { repo.verifyChain(from) } returns ChainVerification(intact = true, checked = 1, unchained = 0)

        val body = resource.verifyIntegrity("  $from  ").entity as IntegrityResponse

        assertThat(body.chainStatus).isEqualTo("INTACT")
        coVerify { repo.verifyChain(from) }
    }

    @Test
    fun `a malformed fromEventId is a 400, and the chain is never walked`(): Unit = runBlocking {
        val response = resource.verifyIntegrity("not-a-uuid")

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity.toString()).contains("must be a valid UUID")
        coVerify(exactly = 0) { repo.verifyChain(any()) }
    }

    @Test
    fun `a verification key is 404 when no anchor was ever signed with that key`(): Unit = runBlocking {
        coEvery { anchors.verificationKey("retired-key") } returns null

        val response = resource.anchorVerificationKey("retired-key")

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `a known key id returns its PEM`(): Unit = runBlocking {
        coEvery { anchors.verificationKey("kms-1") } returns
            AnchorVerificationKey("kms-1", "ECDSA_SHA_256", "-----BEGIN PUBLIC KEY-----")

        val body = resource.anchorVerificationKey("kms-1").entity as AnchorVerificationKey

        assertThat(body.keyId).isEqualTo("kms-1")
        assertThat(body.algorithm).isEqualTo("ECDSA_SHA_256")
    }

    @Test
    fun `an absent keyId query parameter is a 400-class argument failure, not a 500 NPE`(): Unit = runBlocking {
        // JAX-RS injects null for an absent @QueryParam; libs-runtime maps
        // IllegalArgumentException to 400, so the parameter must be nullable and guarded.
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { resource.anchorVerificationKey(null) } }
            .withMessageContaining("keyId is required")
    }

    @Test
    fun `listAnchors delegates the caller's limit to the service that clamps it`(): Unit = runBlocking {
        coEvery { anchors.recent(any()) } returns emptyList()

        resource.listAnchors(4242)

        coVerify { anchors.recent(4242) }
    }
}
