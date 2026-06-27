// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sca.application.port.`in`.ConsumeScaCommand
import com.openbank.sca.application.port.`in`.EnrollDeviceCommand
import com.openbank.sca.application.port.`in`.InitiateScaCommand
import com.openbank.sca.application.port.`in`.ListDevicesQuery
import com.openbank.sca.application.port.`in`.RecordDeviceDecisionCommand
import com.openbank.sca.application.port.`in`.VerifyScaCommand
import com.openbank.sca.application.port.out.DeviceAssertionVerifier
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.application.port.out.OtpGenerator
import com.openbank.sca.application.port.out.OtpStore
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.application.port.out.ScaDecisionStore
import com.openbank.sca.application.port.out.ScaIdempotencyStore
import com.openbank.sca.application.port.out.ScaOutboxRepository
import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.domain.model.SignatureAlgorithm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.PersistenceException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ScaServiceTest {

    private val fixedInstant = Instant.parse("2024-01-01T00:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val now = OffsetDateTime.now(fixedClock)

    private val repository = mockk<ScaChallengeRepository>()
    private val otpGenerator = mockk<OtpGenerator>()
    private val otpStore = mockk<OtpStore>()
    private val notificationDispatchGuard = mockk<NotificationDispatchGuard>(relaxed = true)
    private val idempotencyStore = mockk<ScaIdempotencyStore>()
    private val enrolledDeviceRepository = mockk<EnrolledDeviceRepository>()
    private val decisionStore = mockk<ScaDecisionStore>()
    private val assertionVerifier = mockk<DeviceAssertionVerifier>()
    private val outboxRepository = mockk<ScaOutboxRepository>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val metrics = mockk<DomainMetrics>(relaxed = true)

    private lateinit var service: ScaService

    @BeforeEach
    fun setUp() {
        service = ScaService(
            repository = repository,
            otpGenerator = otpGenerator,
            otpStore = otpStore,
            notificationDispatchGuard = notificationDispatchGuard,
            idempotencyStore = idempotencyStore,
            enrolledDeviceRepository = enrolledDeviceRepository,
            decisionStore = decisionStore,
            assertionVerifier = assertionVerifier,
            outboxRepository = outboxRepository,
            objectMapper = objectMapper,
            metrics = metrics,
            idempotencyTtlSeconds = 300L,
            clock = fixedClock,
        )
    }

    @Test
    fun `initiate creates challenge with PUSH_NOTIFICATION as default method`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val savedChallenge = challenge(partyId = partyId, method = ScaMethod.PUSH_NOTIFICATION)

        coEvery { idempotencyStore.get(any()) } returns null
        coEvery { repository.save(any()) } answers { firstArg() }
        coEvery { idempotencyStore.save(any(), any(), any()) } returns Unit
        coEvery { notificationDispatchGuard.sendPushNotification(any(), any(), any()) } returns Unit

        val result = service.initiate(
            InitiateScaCommand(
                partyId = partyId,
                purpose = ScaPurpose.LOGIN,
                preferredMethod = null,
                dynamicLinkingData = null,
                redirectUrl = null,
            ),
        )

        assertThat(result.method).isEqualTo(ScaMethod.PUSH_NOTIFICATION)
        coVerify(exactly = 1) {
            repository.save(
                match {
                    it.method == ScaMethod.PUSH_NOTIFICATION &&
                        it.partyId == partyId
                },
            )
        }
        coVerify(exactly = 1) { notificationDispatchGuard.sendPushNotification(partyId, any(), any()) }
        coVerify(exactly = 1) { idempotencyStore.save(any(), any(), 300L) }
    }

    @Test
    fun `initiate returns existing challenge for idempotent request`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = challenge(partyId = partyId)

        coEvery { idempotencyStore.get(any()) } returns existing.id.toString()
        coEvery { repository.findById(existing.id) } returns existing

        val result = service.initiate(
            InitiateScaCommand(
                partyId = partyId,
                purpose = ScaPurpose.LOGIN,
                preferredMethod = null,
                dynamicLinkingData = null,
                redirectUrl = null,
            ),
        )

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 1) { repository.findById(existing.id) }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { idempotencyStore.save(any(), any(), any()) }
    }

    @Test
    fun `initiate generates OTP for SMS_OTP method`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        every { otpGenerator.generate() } returns "123456"
        coEvery { idempotencyStore.get(any()) } returns null
        coEvery { repository.save(any()) } answers { firstArg() }
        coEvery { idempotencyStore.save(any(), any(), any()) } returns Unit
        coEvery { otpStore.store(any(), any(), any()) } returns Unit
        coEvery { notificationDispatchGuard.sendSmsOtp(any(), any()) } returns Unit

        val result = service.initiate(
            InitiateScaCommand(
                partyId = partyId,
                purpose = ScaPurpose.LOGIN,
                preferredMethod = ScaMethod.SMS_OTP,
                dynamicLinkingData = null,
                redirectUrl = null,
            ),
        )

        assertThat(result.method).isEqualTo(ScaMethod.SMS_OTP)
        coVerify(exactly = 1) { otpStore.store(result.id, "123456", 300L) }
        coVerify(exactly = 1) { notificationDispatchGuard.sendSmsOtp(partyId, "123456") }
    }

    @Test
    fun `verify completes challenge on successful OTP verification`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.SMS_OTP)

        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { otpStore.verify(challenge.id, "123456") } returns true
        coEvery { otpStore.invalidate(challenge.id) } returns Unit
        coEvery { repository.save(any()) } answers { firstArg() }

        val result = service.verify(
            VerifyScaCommand(
                challengeId = challenge.id,
                partyId = challenge.partyId,
                otp = "123456",
            ),
        )

        assertThat(result.status).isEqualTo(ScaStatus.COMPLETED)
        assertThat(result.completedAt).isNotNull()
        coVerify(exactly = 1) { otpStore.verify(challenge.id, "123456") }
        coVerify(exactly = 1) { otpStore.invalidate(challenge.id) }
        coVerify(exactly = 1) { repository.save(match { it.status == ScaStatus.COMPLETED }) }
    }

    @Test
    fun `verify throws ScaChallengeNotFoundException for unknown id`(): Unit = runBlocking {
        val challengeId = UUID.randomUUID()

        coEvery { repository.findById(challengeId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.verify(
                    VerifyScaCommand(
                        challengeId = challengeId,
                        partyId = UUID.randomUUID(),
                        otp = "123456",
                    ),
                )
            }
        }.isInstanceOf(ScaChallengeNotFoundException::class.java)
    }

    @Test
    fun `verify throws ScaChallengeExpiredException for expired challenge`(): Unit = runBlocking {
        val challenge = challenge(expiresAt = now.minusSeconds(1))

        coEvery { repository.findById(challenge.id) } returns challenge

        assertThatThrownBy {
            runBlocking {
                service.verify(
                    VerifyScaCommand(
                        challengeId = challenge.id,
                        partyId = challenge.partyId,
                        otp = "123456",
                    ),
                )
            }
        }.isInstanceOf(ScaChallengeExpiredException::class.java)
    }

    @Test
    fun `verify fails challenge on invalid OTP`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.SMS_OTP)

        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { otpStore.verify(challenge.id, "bad-otp") } returns false
        coEvery { repository.save(any()) } answers { firstArg() }

        val result = service.verify(
            VerifyScaCommand(
                challengeId = challenge.id,
                partyId = challenge.partyId,
                otp = "bad-otp",
            ),
        )

        assertThat(result.attemptCount).isEqualTo(1)
        assertThat(result.status).isEqualTo(ScaStatus.PENDING)
        coVerify(exactly = 1) { otpStore.verify(challenge.id, "bad-otp") }
        coVerify(exactly = 1) {
            repository.save(
                match {
                    it.attemptCount == 1 &&
                        it.status == ScaStatus.PENDING
                },
            )
        }
    }

    @Test
    fun `getChallenge returns challenge`(): Unit = runBlocking {
        val challenge = challenge()

        coEvery { repository.findById(challenge.id) } returns challenge

        val result = service.getChallenge(challenge.id)

        assertThat(result).isEqualTo(challenge)
    }

    @Test
    fun `getChallenge throws ScaChallengeNotFoundException`(): Unit = runBlocking {
        val challengeId = UUID.randomUUID()

        coEvery { repository.findById(challengeId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.getChallenge(challengeId)
            }
        }.isInstanceOf(ScaChallengeNotFoundException::class.java)
    }

    // --- Decoupled device approval (ADR-0021) ---

    @Test
    fun `verify push challenge stays PENDING and never auto-approves without a decision`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns null

        val result = service.verify(VerifyScaCommand(challenge.id, challenge.partyId, otp = null))

        assertThat(result.status).isEqualTo(ScaStatus.PENDING)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `verify biometric challenge stays PENDING without a decision`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.BIOMETRIC)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns null

        val result = service.verify(VerifyScaCommand(challenge.id, challenge.partyId, otp = null))

        assertThat(result.status).isEqualTo(ScaStatus.PENDING)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `verify push challenge completes on an APPROVED decision`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns decision(challenge.id, DeviceDecisionType.APPROVED)
        coEvery { repository.save(any()) } answers { firstArg() }

        val result = service.verify(VerifyScaCommand(challenge.id, challenge.partyId, otp = null))

        assertThat(result.status).isEqualTo(ScaStatus.COMPLETED)
        coVerify(exactly = 1) { repository.save(match { it.status == ScaStatus.COMPLETED }) }
    }

    @Test
    fun `verify push challenge fails on a DENIED decision`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION, maxAttempts = 1)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns decision(challenge.id, DeviceDecisionType.DENIED)
        coEvery { repository.save(any()) } answers { firstArg() }

        assertThatThrownBy {
            runBlocking { service.verify(VerifyScaCommand(challenge.id, challenge.partyId, otp = null)) }
        }.isInstanceOf(ScaVerificationFailedException::class.java)
    }

    @Test
    fun `recordDecision verifies the signature and records an APPROVED decision`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        val device = device(partyId = challenge.partyId)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns null
        coEvery { enrolledDeviceRepository.findByCredentialId(device.credentialId) } returns device
        every { assertionVerifier.verify(any(), any(), any(), any()) } returns true
        coEvery { decisionStore.record(any(), any()) } returns Unit

        val result = service.recordDecision(
            RecordDeviceDecisionCommand(
                challengeId = challenge.id,
                credentialId = device.credentialId,
                decision = DeviceDecisionType.APPROVED,
                signatureB64 = "sig",
            ),
        )

        // recordDecision records only; verify() is the completion authority.
        assertThat(result.status).isEqualTo(ScaStatus.PENDING)
        coVerify(exactly = 1) { decisionStore.record(match { it.decision == DeviceDecisionType.APPROVED }, any()) }
    }

    @Test
    fun `recordDecision is write-once — rejects a second call even with a valid signature`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns decision(challenge.id, DeviceDecisionType.DENIED)

        assertThatThrownBy {
            runBlocking {
                service.recordDecision(
                    RecordDeviceDecisionCommand(
                        challengeId = challenge.id,
                        credentialId = "cred-1",
                        decision = DeviceDecisionType.APPROVED,
                        signatureB64 = "sig",
                    ),
                )
            }
        }.isInstanceOf(ScaChallengeNotAwaitingException::class.java)
        coVerify(exactly = 0) { decisionStore.record(any(), any()) }
    }

    @Test
    fun `recordDecision rejects an invalid signature and records nothing`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        val device = device(partyId = challenge.partyId)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns null
        coEvery { enrolledDeviceRepository.findByCredentialId(device.credentialId) } returns device
        every { assertionVerifier.verify(any(), any(), any(), any()) } returns false

        assertThatThrownBy {
            runBlocking {
                service.recordDecision(
                    RecordDeviceDecisionCommand(
                        challengeId = challenge.id,
                        credentialId = device.credentialId,
                        decision = DeviceDecisionType.APPROVED,
                        signatureB64 = "bad",
                    ),
                )
            }
        }.isInstanceOf(InvalidDeviceAssertionException::class.java)
        coVerify(exactly = 0) { decisionStore.record(any(), any()) }
    }

    @Test
    fun `recordDecision throws when the device is not enrolled`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns null
        coEvery { enrolledDeviceRepository.findByCredentialId("unknown") } returns null

        assertThatThrownBy {
            runBlocking {
                service.recordDecision(
                    RecordDeviceDecisionCommand(
                        challengeId = challenge.id,
                        credentialId = "unknown",
                        decision = DeviceDecisionType.APPROVED,
                        signatureB64 = "sig",
                    ),
                )
            }
        }.isInstanceOf(DeviceNotEnrolledException::class.java)
    }

    @Test
    fun `recordDecision rejects a device that belongs to another party`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.PUSH_NOTIFICATION)
        val device = device(partyId = UUID.randomUUID())
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { decisionStore.find(challenge.id) } returns null
        coEvery { enrolledDeviceRepository.findByCredentialId(device.credentialId) } returns device

        assertThatThrownBy {
            runBlocking {
                service.recordDecision(
                    RecordDeviceDecisionCommand(
                        challengeId = challenge.id,
                        credentialId = device.credentialId,
                        decision = DeviceDecisionType.APPROVED,
                        signatureB64 = "sig",
                    ),
                )
            }
        }.isInstanceOf(DeviceOwnershipMismatchException::class.java)
    }

    @Test
    fun `enroll saves the device credential`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { enrolledDeviceRepository.findByCredentialId("cred-1") } returns null
        coEvery { enrolledDeviceRepository.save(any()) } answers { firstArg() }

        val result = service.enroll(EnrollDeviceCommand(partyId, "cred-1", "pk", SignatureAlgorithm.ES256))

        assertThat(result.partyId).isEqualTo(partyId)
        assertThat(result.credentialId).isEqualTo("cred-1")
        coVerify(exactly = 1) {
            enrolledDeviceRepository.save(
                match {
                    it.credentialId == "cred-1" &&
                        it.partyId == partyId
                },
            )
        }
        coVerify(exactly = 1) {
            outboxRepository.save(
                match {
                    it.eventType == "DEVICE_ENROLLED" &&
                        it.aggregateId == partyId
                },
            )
        }
    }

    @Test
    fun `enroll returns existing device idempotently for same-party re-enroll`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = device(partyId = partyId, credentialId = "cred-dup")
        coEvery { enrolledDeviceRepository.findByCredentialId("cred-dup") } returns existing

        val result = service.enroll(EnrollDeviceCommand(partyId, "cred-dup", "pk", SignatureAlgorithm.ES256))

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { enrolledDeviceRepository.save(any()) }
        coVerify(exactly = 0) { outboxRepository.save(any()) }
    }

    @Test
    fun `enroll throws CredentialAlreadyEnrolledException when credential belongs to another party`(): Unit =
        runBlocking {
            val otherPartyId = UUID.randomUUID()
            val existing = device(partyId = otherPartyId, credentialId = "cred-taken")
            coEvery { enrolledDeviceRepository.findByCredentialId("cred-taken") } returns existing

            assertThatThrownBy {
                runBlocking {
                    service.enroll(
                        EnrollDeviceCommand(UUID.randomUUID(), "cred-taken", "pk", SignatureAlgorithm.ES256),
                    )
                }
            }.isInstanceOf(CredentialAlreadyEnrolledException::class.java)
            coVerify(exactly = 0) { enrolledDeviceRepository.save(any()) }
        }

    @Test
    fun `enroll throws CredentialAlreadyEnrolledException on TOCTOU unique-constraint race`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { enrolledDeviceRepository.findByCredentialId("cred-race") } returns null
        coEvery { enrolledDeviceRepository.save(any()) } throws
            PersistenceException("duplicate key violates unique constraint (23505)")

        assertThatThrownBy {
            runBlocking {
                service.enroll(EnrollDeviceCommand(partyId, "cred-race", "pk", SignatureAlgorithm.ES256))
            }
        }.isInstanceOf(CredentialAlreadyEnrolledException::class.java)
        coVerify(exactly = 0) { outboxRepository.save(any()) }
    }

    @Test
    fun `listDevices returns enrolled devices for party`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val devices = listOf(device(partyId = partyId), device(partyId = partyId, credentialId = "cred-2"))
        coEvery { enrolledDeviceRepository.findByPartyId(partyId) } returns devices

        val result = service.listDevices(ListDevicesQuery(partyId))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.partyId }).allMatch { it == partyId }
        coVerify(exactly = 1) { enrolledDeviceRepository.findByPartyId(partyId) }
    }

    @Test
    fun `listDevices returns empty list when party has no devices`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { enrolledDeviceRepository.findByPartyId(partyId) } returns emptyList()

        val result = service.listDevices(ListDevicesQuery(partyId))

        assertThat(result).isEmpty()
    }

    // --- Domain metrics (ADR-0077 / ADR-0079) ---

    @Test
    fun `initiate emits scaChallengeIssued tagged with the method`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { idempotencyStore.get(any()) } returns null
        coEvery { repository.save(any()) } answers { firstArg() }
        coEvery { idempotencyStore.save(any(), any(), any()) } returns Unit

        service.initiate(
            InitiateScaCommand(
                partyId = partyId,
                purpose = ScaPurpose.LOGIN,
                preferredMethod = ScaMethod.PUSH_NOTIFICATION,
                dynamicLinkingData = null,
                redirectUrl = null,
            ),
        )

        verify(exactly = 1) { metrics.scaChallengeIssued("PUSH_NOTIFICATION") }
    }

    @Test
    fun `verify emits scaChallengeResolved completed on a successful OTP verification`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.SMS_OTP)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { otpStore.verify(challenge.id, "123456") } returns true
        coEvery { otpStore.invalidate(challenge.id) } returns Unit
        coEvery { repository.save(any()) } answers { firstArg() }

        service.verify(
            VerifyScaCommand(
                challengeId = challenge.id,
                partyId = challenge.partyId,
                otp = "123456",
            ),
        )

        verify(exactly = 1) { metrics.scaChallengeResolved("SMS_OTP", "completed") }
    }

    @Test
    fun `verify emits scaChallengeResolved failed when the last OTP attempt fails`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.SMS_OTP, maxAttempts = 1)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { otpStore.verify(challenge.id, "bad") } returns false
        coEvery { repository.save(any()) } answers { firstArg() }

        assertThatThrownBy {
            runBlocking {
                service.verify(
                    VerifyScaCommand(
                        challengeId = challenge.id,
                        partyId = challenge.partyId,
                        otp = "bad",
                    ),
                )
            }
        }.isInstanceOf(ScaVerificationFailedException::class.java)

        verify(exactly = 1) { metrics.scaChallengeResolved("SMS_OTP", "failed") }
    }

    @Test
    fun `verify does not emit scaChallengeResolved for a retryable OTP failure`(): Unit = runBlocking {
        val challenge = challenge(method = ScaMethod.SMS_OTP, maxAttempts = 3)
        coEvery { repository.findById(challenge.id) } returns challenge
        coEvery { otpStore.verify(challenge.id, "bad") } returns false
        coEvery { repository.save(any()) } answers { firstArg() }

        service.verify(
            VerifyScaCommand(
                challengeId = challenge.id,
                partyId = challenge.partyId,
                otp = "bad",
            ),
        )

        verify(exactly = 0) { metrics.scaChallengeResolved(any(), any()) }
    }

    // --- consume (ADR-0021 settlement gate) ---

    @Test
    fun `consume marks an approved challenge as spent`(): Unit = runBlocking {
        val ch = challenge(status = ScaStatus.COMPLETED)
        coEvery { repository.findById(ch.id) } returns ch
        coEvery { repository.markConsumed(ch.id) } returns true

        val result = service.consume(ConsumeScaCommand(ch.id, ch.partyId, null, null, null))

        assertThat(result.consumedAt).isNotNull()
        coVerify(exactly = 1) { repository.markConsumed(ch.id) }
        verify(exactly = 1) { metrics.scaChallengeResolved(ch.method.name, "consumed") }
    }

    @Test
    fun `consume throws ScaChallengeNotFoundException for unknown id`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns null
        assertThatThrownBy {
            runBlocking { service.consume(ConsumeScaCommand(UUID.randomUUID(), UUID.randomUUID(), null, null, null)) }
        }.isInstanceOf(ScaChallengeNotFoundException::class.java)
    }

    @Test
    fun `consume throws ScaChallengePartyMismatchException when party does not match`(): Unit = runBlocking {
        val ch = challenge(status = ScaStatus.COMPLETED)
        coEvery { repository.findById(ch.id) } returns ch
        assertThatThrownBy {
            runBlocking { service.consume(ConsumeScaCommand(ch.id, UUID.randomUUID(), null, null, null)) }
        }.isInstanceOf(ScaChallengePartyMismatchException::class.java)
    }

    @Test
    fun `consume throws ScaChallengeAlreadyConsumedException when already spent`(): Unit = runBlocking {
        val ch = challenge(status = ScaStatus.COMPLETED).copy(consumedAt = now)
        coEvery { repository.findById(ch.id) } returns ch
        assertThatThrownBy {
            runBlocking { service.consume(ConsumeScaCommand(ch.id, ch.partyId, null, null, null)) }
        }.isInstanceOf(ScaChallengeAlreadyConsumedException::class.java)
    }

    @Test
    fun `consume throws ScaChallengeNotApprovedException when challenge is FAILED`(): Unit = runBlocking {
        val ch = challenge(status = ScaStatus.FAILED)
        coEvery { repository.findById(ch.id) } returns ch
        assertThatThrownBy {
            runBlocking { service.consume(ConsumeScaCommand(ch.id, ch.partyId, null, null, null)) }
        }.isInstanceOf(ScaChallengeNotApprovedException::class.java)
    }

    @Test
    fun `consume throws ScaDynamicLinkingMismatchException when amount does not match`(): Unit = runBlocking {
        val ch = challenge(status = ScaStatus.COMPLETED).copy(
            dynamicLinkingData = DynamicLinkingData("100.00", "CZK", null, null, null),
        )
        coEvery { repository.findById(ch.id) } returns ch
        assertThatThrownBy {
            runBlocking { service.consume(ConsumeScaCommand(ch.id, ch.partyId, "999.00", "CZK", null)) }
        }.isInstanceOf(ScaDynamicLinkingMismatchException::class.java)
    }

    @Test
    fun `consume throws ScaChallengeAlreadyConsumedException on concurrent double-spend`(): Unit = runBlocking {
        val ch = challenge(status = ScaStatus.COMPLETED)
        coEvery { repository.findById(ch.id) } returns ch
        coEvery { repository.markConsumed(ch.id) } returns false
        assertThatThrownBy {
            runBlocking { service.consume(ConsumeScaCommand(ch.id, ch.partyId, null, null, null)) }
        }.isInstanceOf(ScaChallengeAlreadyConsumedException::class.java)
    }

    private fun device(partyId: UUID = UUID.randomUUID(), credentialId: String = "cred-1") = EnrolledDevice(
        partyId = partyId,
        credentialId = credentialId,
        publicKeySpkiB64 = "pk",
        algorithm = SignatureAlgorithm.ES256,
        createdAt = now,
    )

    private fun decision(challengeId: UUID, type: DeviceDecisionType) = DeviceApprovalDecision(
        challengeId = challengeId,
        credentialId = "cred-1",
        decision = type,
        signatureB64 = "sig",
        decidedAt = now,
    )

    private fun challenge(
        id: UUID = UUID.randomUUID(),
        partyId: UUID = UUID.randomUUID(),
        method: ScaMethod = ScaMethod.SMS_OTP,
        expiresAt: OffsetDateTime = now.plusMinutes(5),
        attemptCount: Int = 0,
        maxAttempts: Int = 3,
        status: ScaStatus = ScaStatus.PENDING,
    ) = ScaChallenge(
        id = id,
        partyId = partyId,
        purpose = ScaPurpose.LOGIN,
        method = method,
        status = status,
        expiresAt = expiresAt,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        dynamicLinkingData = DynamicLinkingData(null, null, null, null, null),
        redirectUrl = null,
        createdAt = now.minusMinutes(1),
    )
}
