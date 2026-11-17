// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sca.application.port.`in`.ConsumeScaCommand
import com.openbank.sca.application.port.`in`.InitiateScaCommand
import com.openbank.sca.application.port.`in`.RecordDeviceDecisionCommand
import com.openbank.sca.application.port.out.DeviceAssertionVerifier
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.application.port.out.ScaDecisionStore
import com.openbank.sca.application.port.out.ScaIdempotencyStore
import com.openbank.sca.domain.model.ConsumerScope
import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ReservedNamespace
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.domain.model.SignatureAlgorithm
import com.openbank.sca.domain.model.dynamicLinkingPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0385 D3 delivery checks that live in sca-service ("SCA linking" and the 72 h rule of D2.3).
 * One test per check; each names the rule whose removal makes it fail.
 */
class McpClientConnectScaTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)
    private val partyId = UUID.randomUUID()

    private val challenges = ConcurrentHashMap<UUID, ScaChallenge>()
    private val keys = ConcurrentHashMap<String, UUID>()
    private val pushed = mutableListOf<String>()

    private val repository = mockk<ScaChallengeRepository>().also { repo ->
        coEvery { repo.save(any()) } answers { firstArg<ScaChallenge>().also { challenges[it.id] = it } }
        coEvery { repo.findById(any()) } answers { challenges[firstArg()] }
        coEvery { repo.markConsumed(any()) } answers {
            val id = firstArg<UUID>()
            val ch = challenges[id]
            if (ch == null || ch.consumedAt != null) {
                false
            } else {
                challenges[id] = ch.copy(consumedAt = now)
                true
            }
        }
    }
    private val idempotencyStore = mockk<ScaIdempotencyStore>().also { store ->
        coEvery { store.get(any()) } answers { keys[firstArg()]?.toString() }
        coEvery { store.save(any(), any(), any()) } answers { keys[firstArg()] = secondArg() }
    }
    private val decisionStore = mockk<ScaDecisionStore>().also { store ->
        coEvery { store.find(any()) } returns null
        coEvery { store.record(any(), any()) } returns true
    }
    private val devices = mockk<EnrolledDeviceRepository>()
    private val verifier = mockk<DeviceAssertionVerifier>().also {
        every { it.verify(any(), any(), any(), any()) } returns true
    }
    private val guard = mockk<NotificationDispatchGuard>().also { g ->
        coEvery { g.sendPushNotification(any(), any(), any()) } answers { pushed += thirdArg<String>() }
    }

    private val service = ScaService(
        repository = repository,
        otpGenerator = mockk(),
        otpStore = mockk(),
        notificationDispatchGuard = guard,
        idempotencyStore = idempotencyStore,
        enrolledDeviceRepository = devices,
        decisionStore = decisionStore,
        assertionVerifier = verifier,
        partyTypeLookup = mockk(),
        objectMapper = ObjectMapper(),
        metrics = mockk<DomainMetrics>(relaxed = true),
        idempotencyTtlSeconds = 300L,
        clock = clock,
    )

    private val mcpScope = ConsumerScope.Reserved(ReservedNamespace.MCP_CONNECT)

    // Rule: requireMcpConnectLinking.
    @Test
    fun `initiate without approvalRequestId or payloadSha256 is refused before a challenge exists`() {
        listOf(
            null,
            linking(requestId = null),
            linking(sha = null),
            linking(sha = "not-hex"),
            linking(requestId = "par-123"), // outside the reserved namespace
            linking().copy(amount = "10.00", currency = "CZK", creditorIban = "CZ00"),
        ).forEach { dl ->
            assertThatThrownBy { runBlocking { service.initiate(command(dl)) } }
                .`as`(dl.toString())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(challenges).isEmpty()
    }

    // Rule: the mcp-connect: prefix is refused on every other purpose (requireMcpConnectLinking).
    @Test
    fun `no other purpose may initiate with an mcp-connect id`() {
        ScaPurpose.entries.filter { it != ScaPurpose.MCP_CLIENT_CONNECT }.forEach { purpose ->
            assertThatThrownBy { runBlocking { service.initiate(command(linking()).copy(purpose = purpose, scope = ConsumerScope.General)) } }
                .`as`(purpose.name)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved to MCP_CLIENT_CONNECT")
        }
        assertThat(challenges).isEmpty()
    }

    // Rule: STATIC_PUSH_MESSAGES entry (buildPushMessage's checkNotNull).
    @Test
    fun `initiate succeeds and pushes neutral text`(): Unit = runBlocking {
        val ch = service.initiate(command(linking()))

        assertThat(ch.purpose).isEqualTo(ScaPurpose.MCP_CLIENT_CONNECT)
        assertThat(pushed).containsExactly(STATIC_PUSH_MESSAGES.getValue(ScaPurpose.MCP_CLIENT_CONNECT))
    }

    // Rule: buildIdempotencyKey includes approvalRequestId + payloadSha256.
    @Test
    fun `two concurrent connect flows get two challenges, a double submit of one flow gets one`(): Unit = runBlocking {
        val a = service.initiate(command(linking(requestId = "mcp-connect:req-A", sha = "a".repeat(64))))
        val b = service.initiate(command(linking(requestId = "mcp-connect:req-B", sha = "b".repeat(64))))
        val sameRequestOtherContext =
            service.initiate(command(linking(requestId = "mcp-connect:req-A", sha = "c".repeat(64))))
        val aAgain = service.initiate(command(linking(requestId = "mcp-connect:req-A", sha = "a".repeat(64))))

        assertThat(setOf(a.id, b.id, sameRequestOtherContext.id)).hasSize(3)
        assertThat(aAgain.id).isEqualTo(a.id)
    }

    // Rule: the device signs purpose|approvalRequestId|payloadSha256 (approvalLinkingPayload).
    @Test
    fun `the device signs the connect context under its own purpose segment`(): Unit = runBlocking {
        val ch = service.initiate(command(linking(requestId = "mcp-connect:req-A", sha = "A".repeat(64))))

        assertThat(String(ch.dynamicLinkingPayload(DeviceDecisionType.APPROVED)))
            .isEqualTo("${ch.id}|APPROVED|MCP_CLIENT_CONNECT|mcp-connect:req-A|${"a".repeat(64)}")
    }

    // Rule: consume compares approvalRequestId + payloadSha256 (DynamicLinkingData.authorises).
    @Test
    fun `an approval for request A cannot release request B, and A is spent exactly once`(): Unit = runBlocking {
        val a = approved(linking(requestId = "mcp-connect:req-A", sha = "a".repeat(64)))
        approved(linking(requestId = "mcp-connect:req-B", sha = "b".repeat(64)))

        listOf(
            consume(a, "mcp-connect:req-B", "b".repeat(64)),
            consume(a, "mcp-connect:req-A", "b".repeat(64)),
            consume(a, "mcp-connect:req-B", "a".repeat(64)),
            consume(a, null, null),
        ).forEach { cmd ->
            assertThatThrownBy { runBlocking { service.consume(cmd) } }
                .isInstanceOf(ScaDynamicLinkingMismatchException::class.java)
        }
        assertThatThrownBy {
            runBlocking {
                service.consume(
                    consume(a, "mcp-connect:req-A", "a".repeat(64)).copy(expectedPartyId = UUID.randomUUID()),
                )
            }
        }.isInstanceOf(ScaChallengePartyMismatchException::class.java)
        assertThat(challenges.getValue(a.id).consumedAt).isNull()

        service.consume(consume(a, "mcp-connect:req-A", "a".repeat(64)))
        assertThatThrownBy { runBlocking { service.consume(consume(a, "mcp-connect:req-A", "a".repeat(64))) } }
            .isInstanceOf(ScaChallengeAlreadyConsumedException::class.java)
    }

    // Rule: ReservedNamespace.MCP_CONNECT (ownsPurposes) — General is refused.
    @Test
    fun `a general consumer cannot spend a connect challenge and does not burn it`(): Unit = runBlocking {
        val a = approved(linking())
        assertThatThrownBy {
            runBlocking {
                service.consume(consume(a, a.link().approvalRequestId, a.link().payloadSha256, ConsumerScope.General))
            }
        }.isInstanceOf(ScaConsumerScopeViolationException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.consume(
                    consume(
                        a,
                        a.link().approvalRequestId,
                        a.link().payloadSha256,
                        ConsumerScope.Reserved(ReservedNamespace.PENSION),
                    ),
                )
            }
        }.isInstanceOf(ScaConsumerScopeViolationException::class.java)
        assertThat(challenges.getValue(a.id).consumedAt).isNull()
    }

    // Rule: MCP_CONNECT_MIN_DEVICE_AGE check in recordDecision.
    @Test
    fun `a device enrolled less than 72 h ago cannot approve a connect, an older one can`(): Unit = runBlocking {
        val ch = service.initiate(command(linking()))
        val young = device(enrolledAgo = Duration.ofHours(72).minusMinutes(1))
        coEvery { devices.findByCredentialId(young.credentialId) } returns young

        assertThatThrownBy {
            runBlocking {
                service.recordDecision(
                    RecordDeviceDecisionCommand(ch.id, young.credentialId, DeviceDecisionType.APPROVED, "sig"),
                )
            }
        }.isInstanceOf(DeviceTooRecentlyEnrolledException::class.java)
        coVerify(exactly = 0) { decisionStore.record(any(), any()) }

        val old = device(enrolledAgo = Duration.ofHours(72), credentialId = "old")
        coEvery { devices.findByCredentialId(old.credentialId) } returns old
        service.recordDecision(RecordDeviceDecisionCommand(ch.id, old.credentialId, DeviceDecisionType.APPROVED, "sig"))
        coVerify(exactly = 1) { decisionStore.record(any(), any()) }
    }

    @Test
    fun `a young device may still deny a connect, and other purposes keep no age floor`(): Unit = runBlocking {
        val young = device(enrolledAgo = Duration.ZERO)
        coEvery { devices.findByCredentialId(young.credentialId) } returns young
        val connect = service.initiate(command(linking()))
        service.recordDecision(
            RecordDeviceDecisionCommand(connect.id, young.credentialId, DeviceDecisionType.DENIED, "sig"),
        )

        val login = service.initiate(command(null).copy(purpose = ScaPurpose.LOGIN, scope = ConsumerScope.General))
        service.recordDecision(
            RecordDeviceDecisionCommand(login.id, young.credentialId, DeviceDecisionType.APPROVED, "sig"),
        )
        coVerify(exactly = 2) { decisionStore.record(any(), any()) }
    }

    // --- helpers ---

    private suspend fun approved(dl: DynamicLinkingData): ScaChallenge {
        val ch = service.initiate(command(dl))
        val completed = ch.copy(status = ScaStatus.COMPLETED, completedAt = now)
        challenges[ch.id] = completed
        return completed
    }

    private fun ScaChallenge.link() = requireNotNull(dynamicLinkingData)

    private fun consume(ch: ScaChallenge, requestId: String?, sha: String?, scope: ConsumerScope = mcpScope) =
        ConsumeScaCommand(
            challengeId = ch.id,
            expectedPartyId = ch.partyId,
            amount = null,
            currency = null,
            creditor = null,
            approvalRequestId = requestId,
            payloadSha256 = sha,
            scope = scope,
        )

    // Rule: requireInitiateScope. The authorization server's consumer raises connect challenges
    // and nothing else — never a payment or approval prompt on a customer's device.
    @Test
    fun `the scoped connect consumer may initiate a connect challenge`() {
        val challenge = runBlocking { service.initiate(command(linking()).copy(scope = mcpScope)) }
        assertThat(challenge.purpose).isEqualTo(ScaPurpose.MCP_CLIENT_CONNECT)
    }

    @Test
    fun `the scoped connect consumer may not initiate any other purpose`() {
        listOf(ScaPurpose.APPROVAL, ScaPurpose.PAYMENT_INITIATION, ScaPurpose.CONSENT_GRANT).forEach { purpose ->
            assertThatThrownBy {
                runBlocking { service.initiate(command(linking(requestId = "other-op:1")).copy(purpose = purpose, scope = mcpScope)) }
            }.`as`(purpose.name).isInstanceOf(ScaInitiateScopeViolationException::class.java)
        }
        assertThat(challenges).isEmpty()
    }

    // Rule: requireInitiateScope (converse). Only the authorization server's consumer raises a
    // connect prompt; a General initiator (e.g. the edge, which holds scaChallenge.initiate) and
    // another reserved consumer (pension) are refused, and nothing is minted or pushed.
    @Test
    fun `a general or other reserved initiator may not raise a connect challenge`() {
        listOf(ConsumerScope.General, ConsumerScope.Reserved(ReservedNamespace.PENSION)).forEach { scope ->
            assertThatThrownBy { runBlocking { service.initiate(command(linking()).copy(scope = scope)) } }
                .`as`(scope.toString())
                .isInstanceOf(ScaInitiateScopeViolationException::class.java)
        }
        assertThat(challenges).isEmpty()
        assertThat(pushed).isEmpty()
    }

    private fun command(dl: DynamicLinkingData?) = InitiateScaCommand(
        partyId = partyId,
        purpose = ScaPurpose.MCP_CLIENT_CONNECT,
        preferredMethod = null,
        dynamicLinkingData = dl,
        redirectUrl = null,
        // The authorization server's consumer: the only initiator a connect challenge accepts.
        scope = mcpScope,
    )

    private fun linking(requestId: String? = "mcp-connect:req-1", sha: String? = "d".repeat(64)) =
        DynamicLinkingData(null, null, null, null, null, approvalRequestId = requestId, payloadSha256 = sha)

    private fun device(enrolledAgo: Duration, credentialId: String = "young") = EnrolledDevice(
        partyId = partyId,
        credentialId = credentialId,
        publicKeySpkiB64 = "pk",
        algorithm = SignatureAlgorithm.ES256,
        createdAt = now.minus(enrolledAgo),
    )
}
