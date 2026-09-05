// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sca.application.port.`in`.ConsumeScaCommand
import com.openbank.sca.application.port.`in`.ConsumeScaUseCase
import com.openbank.sca.application.port.`in`.EnrollDeviceCommand
import com.openbank.sca.application.port.`in`.EnrollDeviceUseCase
import com.openbank.sca.application.port.`in`.GetScaUseCase
import com.openbank.sca.application.port.`in`.InitiateScaCommand
import com.openbank.sca.application.port.`in`.InitiateScaUseCase
import com.openbank.sca.application.port.`in`.ListDevicesQuery
import com.openbank.sca.application.port.`in`.ListDevicesUseCase
import com.openbank.sca.application.port.`in`.RecordDeviceDecisionCommand
import com.openbank.sca.application.port.`in`.RecordDeviceDecisionUseCase
import com.openbank.sca.application.port.`in`.VerifyScaCommand
import com.openbank.sca.application.port.`in`.VerifyScaUseCase
import com.openbank.sca.application.port.out.DeviceAssertionVerifier
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.application.port.out.OtpGenerator
import com.openbank.sca.application.port.out.OtpStore
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.application.port.out.ScaDecisionStore
import com.openbank.sca.application.port.out.ScaIdempotencyStore
import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.domain.model.dynamicLinkingPayload
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class ScaChallengeNotFoundException(id: UUID) : RuntimeException("SCA challenge not found: $id")
class ScaChallengeExpiredException(id: UUID) : RuntimeException("SCA challenge expired: $id")
class ScaChallengeMaxAttemptsException(id: UUID) : RuntimeException("Max attempts exceeded for challenge: $id")
class ScaVerificationFailedException(id: UUID) : RuntimeException("SCA verification failed for challenge: $id")
class ScaChallengeNotAwaitingException(id: UUID) : RuntimeException("SCA challenge is not awaiting a decision: $id")
class ScaChallengeNotApprovedException(id: UUID) : RuntimeException("SCA challenge is not approved: $id")
class ScaChallengeAlreadyConsumedException(id: UUID) : RuntimeException("SCA challenge already consumed: $id")
class ScaChallengePartyMismatchException(id: UUID) : RuntimeException("SCA challenge belongs to another party: $id")
class ScaDynamicLinkingMismatchException(id: UUID) :
    RuntimeException("Operation does not match what the device signed for challenge: $id")
class DeviceNotEnrolledException(credentialId: String) :
    RuntimeException("Device credential not enrolled: $credentialId")
class DeviceOwnershipMismatchException(credentialId: String) :
    RuntimeException("Device credential does not belong to the challenge party: $credentialId")
class CredentialAlreadyEnrolledException(credentialId: String) :
    RuntimeException("Credential '$credentialId' is already enrolled by another party")
class InvalidDeviceAssertionException(id: UUID) : RuntimeException("Invalid device assertion for challenge: $id")

@ApplicationScoped
// One use-case interface per operation keeps callers narrow; the aggregate implementation
// crosses the function-count threshold by design.
@Suppress("TooManyFunctions", "LongParameterList")
class ScaService(
    private val repository: ScaChallengeRepository,
    private val otpGenerator: OtpGenerator,
    private val otpStore: OtpStore,
    private val notificationDispatchGuard: NotificationDispatchGuard,
    private val idempotencyStore: ScaIdempotencyStore,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val decisionStore: ScaDecisionStore,
    private val assertionVerifier: DeviceAssertionVerifier,
    private val objectMapper: ObjectMapper,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.sca.idempotency-ttl-seconds", defaultValue = "300")
    private val idempotencyTtlSeconds: Long,
    private val clock: Clock,
) : InitiateScaUseCase,
    VerifyScaUseCase,
    GetScaUseCase,
    EnrollDeviceUseCase,
    RecordDeviceDecisionUseCase,
    ListDevicesUseCase,
    ConsumeScaUseCase {

    @Inject
    constructor(
        repository: ScaChallengeRepository,
        otpGenerator: OtpGenerator,
        otpStore: OtpStore,
        notificationDispatchGuard: NotificationDispatchGuard,
        idempotencyStore: ScaIdempotencyStore,
        enrolledDeviceRepository: EnrolledDeviceRepository,
        decisionStore: ScaDecisionStore,
        assertionVerifier: DeviceAssertionVerifier,
        objectMapper: ObjectMapper,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.sca.idempotency-ttl-seconds", defaultValue = "300")
        idempotencyTtlSeconds: Long,
    ) : this(
        repository,
        otpGenerator,
        otpStore,
        notificationDispatchGuard,
        idempotencyStore,
        enrolledDeviceRepository,
        decisionStore,
        assertionVerifier,
        objectMapper,
        metrics,
        idempotencyTtlSeconds,
        Clock.systemUTC(),
    )

    override suspend fun initiate(command: InitiateScaCommand): ScaChallenge {
        val now = OffsetDateTime.now(clock)
        val method = command.preferredMethod ?: ScaMethod.PUSH_NOTIFICATION
        val ttlSeconds = 300L
        val idempotencyKey = buildIdempotencyKey(command, method)

        idempotencyStore.get(idempotencyKey)?.let { existingId ->
            // Only replay an idempotent challenge while it is still actionable. A rapid
            // double-submit of the SAME request (before any decision is recorded) must dedupe to
            // one challenge — but a fresh attempt made AFTER the previous identical challenge
            // resolved, was consumed, expired, or already carries a (write-once) device decision
            // is a NEW authorisation and must mint a new challenge. Otherwise initiate() hands
            // back a spent challenge and the very next recordDecision() throws
            // ScaChallengeNotAwaitingException → 409 ("not awaiting a decision") — exactly what
            // repeating the same payment within the TTL hit (sign succeeds once, payment fails or
            // the challenge is consumed, every retry then 409s on the reused challenge).
            repository.findById(UUID.fromString(existingId))?.let { existing ->
                if (existing.isReplayable(now) && decisionStore.find(existing.id) == null) {
                    return existing
                }
            }
        }

        val challenge = ScaChallenge(
            partyId = command.partyId,
            purpose = command.purpose,
            method = method,
            expiresAt = now.plusSeconds(ttlSeconds),
            dynamicLinkingData = command.dynamicLinkingData,
            redirectUrl = command.redirectUrl,
            createdAt = now,
        )

        val saved = repository.save(challenge)
        metrics.scaChallengeIssued(challenge.method.name)

        idempotencyStore.save(idempotencyKey, saved.id, minOf(ttlSeconds, idempotencyTtlSeconds))

        when (method) {
            ScaMethod.TOTP -> {
                val otp = otpGenerator.generate()
                otpStore.store(saved.id, otp, ttlSeconds)
            }
            ScaMethod.PUSH_NOTIFICATION, ScaMethod.BIOMETRIC -> {
                notificationDispatchGuard.sendPushNotification(
                    command.partyId,
                    saved.id,
                    buildPushMessage(command.purpose, command.dynamicLinkingData),
                )
            }
        }

        return saved
    }

    override suspend fun verify(command: VerifyScaCommand): ScaChallenge {
        val now = OffsetDateTime.now(clock)
        val challenge = repository.findById(command.challengeId)
            ?: throw ScaChallengeNotFoundException(command.challengeId)

        if (challenge.isExpired(now)) throw ScaChallengeExpiredException(command.challengeId)
        if (!challenge.canAttempt(now)) throw ScaChallengeMaxAttemptsException(command.challengeId)

        return when (challenge.method) {
            ScaMethod.TOTP -> verifyOtp(challenge, command, now)
            // Decoupled methods (ADR-0021): never auto-approve. The authentication happened
            // out-of-band on the enrolled device; we consult the signature-verified decision
            // recorded by recordDecision(). No decision yet => the challenge stays PENDING
            // (no attempt consumed) — "not yet approved", strictly safer than a bypass (audit K2).
            ScaMethod.PUSH_NOTIFICATION, ScaMethod.BIOMETRIC -> verifyDecoupled(challenge, now)
        }
    }

    private suspend fun verifyOtp(
        challenge: ScaChallenge,
        command: VerifyScaCommand,
        now: OffsetDateTime,
    ): ScaChallenge {
        val otp = command.otp ?: throw IllegalArgumentException("OTP required for ${challenge.method}")
        return if (otpStore.verify(command.challengeId, otp)) {
            otpStore.invalidate(command.challengeId)
            repository.save(challenge.complete(now)).also { recordResolution(it) }
        } else {
            repository.save(challenge.fail("Invalid OTP", now)).also {
                recordResolution(it)
                if (it.status == ScaStatus.FAILED) throw ScaVerificationFailedException(command.challengeId)
            }
        }
    }

    private suspend fun verifyDecoupled(challenge: ScaChallenge, now: OffsetDateTime): ScaChallenge {
        val decision = decisionStore.find(challenge.id) ?: return challenge // PENDING — awaiting device
        return when (decision.decision) {
            DeviceDecisionType.APPROVED -> repository.save(challenge.complete(now)).also { recordResolution(it) }
            DeviceDecisionType.DENIED -> repository.save(challenge.fail("Denied on device", now)).also {
                recordResolution(it)
                if (it.status == ScaStatus.FAILED) throw ScaVerificationFailedException(challenge.id)
            }
        }
    }

    /**
     * Emit the `openbank.sca.completions` counter once a challenge reaches a **terminal** state,
     * reading the outcome from the persisted challenge (ADR-0077 / ADR-0079). A still-PENDING
     * challenge (e.g. a failed-but-retryable OTP attempt, or an undecided decoupled push) is not a
     * resolution and is not counted. Only low-cardinality tags: the closed `ScaMethod` enum + a
     * closed outcome set — never a challenge id or party id.
     */
    private fun recordResolution(challenge: ScaChallenge) {
        val outcome = when (challenge.status) {
            ScaStatus.COMPLETED -> "completed"
            ScaStatus.FAILED -> "failed"
            ScaStatus.EXPIRED -> "expired"
            ScaStatus.CANCELLED -> "cancelled"
            ScaStatus.PENDING -> return // not yet resolved
        }
        metrics.scaChallengeResolved(challenge.method.name, outcome)
    }

    override suspend fun enroll(command: EnrollDeviceCommand): EnrolledDevice {
        enrolledDeviceRepository.findByCredentialId(command.credentialId)?.let { existing ->
            if (existing.partyId == command.partyId) return existing
            throw CredentialAlreadyEnrolledException(command.credentialId)
        }
        val now = OffsetDateTime.now(clock)
        val device = EnrolledDevice(
            partyId = command.partyId,
            credentialId = command.credentialId,
            publicKeySpkiB64 = command.publicKeySpkiB64,
            algorithm = command.algorithm,
            createdAt = now,
        )
        // The device row and its DEVICE_ENROLLED event commit in ONE transaction (#8679). They
        // used to be two — `enrolledDeviceRepository.save(...)` followed by
        // `outboxRepository.save(...)`, each opening its own `Panache.withTransaction`, measured
        // as xmin 751 vs 752 — so a crash in between enrolled the device and lost the event with
        // nothing to retry it. This is sca's only outbox write.
        return try {
            enrolledDeviceRepository.saveWithOutbox(
                device,
                OutboxMessage(
                    aggregateId = device.partyId,
                    eventType = DEVICE_ENROLLED_EVENT_TYPE,
                    payload = objectMapper.writeValueAsString(
                        mapOf(
                            // The discriminator MUST be in the payload body, not only in the outbox
                            // column: every consumer of these topics receives the serialized payload
                            // alone and switches on `eventType`. party-service and kyc-service both
                            // embed it (PartyEvent.kt, KycEvent.kt); this publisher did not, so
                            // onboarding-service's `parseScaEvent` read "" and fell to `else -> null`,
                            // discarding the message on the quiet path (`?: return`, no log, no error).
                            // Result: DEVICE_ENROLLED had never once been projected — 15 events
                            // published and SENT, and every onboarding_records row still read
                            // sca_enrolled=false / device_count=0, with 11 parties genuinely enrolled
                            // (measured on the sandbox 2026-08-13, issue #4353).
                            "eventType" to DEVICE_ENROLLED_EVENT_TYPE,
                            "deviceId" to device.id.toString(),
                            "partyId" to device.partyId.toString(),
                            "credentialId" to device.credentialId,
                            "algorithm" to device.algorithm.name,
                            "occurredAt" to device.createdAt.toString(),
                            "sourceService" to SOURCE_SERVICE,
                        ),
                    ),
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // TOCTOU: concurrent enroll for the same credentialId won the DB unique constraint race.
            if (e.causedByUniqueViolation()) throw CredentialAlreadyEnrolledException(command.credentialId)
            throw e
        }
    }

    override suspend fun listDevices(query: ListDevicesQuery): List<EnrolledDevice> =
        enrolledDeviceRepository.findByPartyId(query.partyId)

    override suspend fun recordDecision(command: RecordDeviceDecisionCommand): ScaChallenge {
        val now = OffsetDateTime.now(clock)
        val challenge = repository.findById(command.challengeId)
            ?: throw ScaChallengeNotFoundException(command.challengeId)
        if (challenge.isExpired(now)) throw ScaChallengeExpiredException(command.challengeId)
        if (challenge.status != ScaStatus.PENDING) throw ScaChallengeNotAwaitingException(command.challengeId)

        // P2 idempotency: a decision is write-once. Reject any second call so a DENIED cannot
        // be overwritten with APPROVED by re-sending a valid signature (even though that would
        // require a valid signed assertion, it is a better design principle to be immutable).
        if (decisionStore.find(command.challengeId) != null) throw ScaChallengeNotAwaitingException(command.challengeId)

        val device = enrolledDeviceRepository.findByCredentialId(command.credentialId)
            ?: throw DeviceNotEnrolledException(command.credentialId)
        if (device.partyId != challenge.partyId) throw DeviceOwnershipMismatchException(command.credentialId)

        // Dynamic linking (RTS Art. 5): the device must have signed THIS challenge's amount+payee.
        val payload = challenge.dynamicLinkingPayload(command.decision)
        val signatureValid = assertionVerifier.verify(
            publicKeySpkiB64 = device.publicKeySpkiB64,
            algorithm = device.algorithm,
            payload = payload,
            signatureB64 = command.signatureB64,
        )
        if (!signatureValid) throw InvalidDeviceAssertionException(command.challengeId)

        val ttl = maxOf(1L, java.time.Duration.between(now, challenge.expiresAt).seconds)
        decisionStore.record(
            DeviceApprovalDecision(
                challengeId = command.challengeId,
                credentialId = command.credentialId,
                decision = command.decision,
                signatureB64 = command.signatureB64,
                decidedAt = now,
            ),
            ttlSeconds = ttl,
        )
        return challenge
    }

    override suspend fun getChallenge(challengeId: UUID): ScaChallenge =
        repository.findById(challengeId) ?: throw ScaChallengeNotFoundException(challengeId)

    override suspend fun listPendingByParty(partyId: UUID): List<ScaChallenge> = repository.findPendingByParty(partyId)

    /**
     * Settlement gate (ADR-0021): spend an approved challenge on exactly the operation the
     * device signed. Order matters — every check happens BEFORE the atomic compare-and-consume,
     * so a refused consume never burns the challenge; and the consume happens BEFORE the caller
     * executes the operation, so a replayed request finds the challenge already spent.
     */
    override suspend fun consume(command: ConsumeScaCommand): ScaChallenge {
        val now = OffsetDateTime.now(clock)
        var challenge = repository.findById(command.challengeId)
            ?: throw ScaChallengeNotFoundException(command.challengeId)
        if (challenge.partyId != command.expectedPartyId) {
            throw ScaChallengePartyMismatchException(command.challengeId)
        }
        if (challenge.consumedAt != null) throw ScaChallengeAlreadyConsumedException(command.challengeId)
        // A decoupled challenge may hold a signature-verified device decision that nobody has
        // promoted yet (verify() is a separate call) — resolve it now rather than refusing.
        if (challenge.status == ScaStatus.PENDING &&
            (challenge.method == ScaMethod.PUSH_NOTIFICATION || challenge.method == ScaMethod.BIOMETRIC)
        ) {
            if (challenge.isExpired(now)) throw ScaChallengeExpiredException(command.challengeId)
            challenge = verifyDecoupled(challenge, now)
        }
        if (challenge.status != ScaStatus.COMPLETED) throw ScaChallengeNotApprovedException(command.challengeId)
        val linking = challenge.dynamicLinkingData
        // A challenge that signed nothing cannot authorise a money movement, a document
        // signature OR a card operation; one that signed amount+payee (or a document
        // hash+ceremony, or a card+action) must match the operation exactly (RTS Art. 5 dynamic
        // linking, extended to documents by ADR-0169 D2 and to card management here).
        val authorised = linking?.authorises(
            command.amount,
            command.currency,
            command.creditor,
            command.documentSha256,
            command.ceremonyId,
            command.cardId,
            command.cardAction,
        ) ?: (command.amount == null && command.documentSha256 == null && command.cardId == null)
        if (!authorised) throw ScaDynamicLinkingMismatchException(command.challengeId)
        if (!repository.markConsumed(command.challengeId)) {
            throw ScaChallengeAlreadyConsumedException(command.challengeId)
        }
        metrics.scaChallengeResolved(challenge.method.name, "consumed")
        return challenge.copy(consumedAt = now)
    }

    private fun buildPushMessage(purpose: ScaPurpose, data: DynamicLinkingData?): String = when (purpose) {
        ScaPurpose.PAYMENT_INITIATION ->
            "Potvrďte platbu ${data?.amount} ${data?.currency} pro ${data?.creditorName}"
        ScaPurpose.CONSENT_GRANT ->
            "Potvrďte udělení přístupu k vašemu účtu"
        ScaPurpose.LOGIN ->
            "Potvrďte přihlášení do OpenBank"
        ScaPurpose.AGENT_ACTION ->
            "Potvrďte akci bankovního agenta"
        ScaPurpose.SENSITIVE_DATA_ACCESS ->
            "Potvrďte přístup k citlivým údajům"
        ScaPurpose.DOCUMENT_SIGNING ->
            "Potvrďte podpis dokumentu"
        ScaPurpose.CARD_MANAGEMENT ->
            "Potvrďte operaci s platební kartou"
        ScaPurpose.DELEGATION_GRANT ->
            "Potvrďte sdílení přístupu k vašemu produktu"
        ScaPurpose.DELEGATION_ACCEPT ->
            "Potvrďte přijetí sdíleného přístupu"
        ScaPurpose.SAVINGS_WITHDRAW_APPROVAL ->
            "Potvrďte výběr ze spořicího cíle"
    }

    private fun buildIdempotencyKey(command: InitiateScaCommand, method: ScaMethod): String {
        val dl = command.dynamicLinkingData
        val base = listOf(
            command.partyId,
            command.purpose,
            method,
            dl?.amount,
            dl?.currency,
            dl?.creditorIban,
            dl?.creditorName,
            dl?.reference,
            command.redirectUrl,
        )
        // A CARD_MANAGEMENT challenge carries none of the payment fields above, so without the
        // card binding every card challenge for a party would collapse to the SAME key: raising
        // card A's limit would replay the still-PENDING challenge signed for card B's PAN reveal.
        // Appended only when present, so payment/document/login keys keep the exact string they
        // already hash to (a key change would silently drop dedupe across a rolling deploy).
        val cardSegments = if (dl?.cardId != null || dl?.cardAction != null) {
            listOf(dl.cardId, dl.cardAction)
        } else {
            emptyList()
        }
        return (base + cardSegments).joinToString(":") { it?.toString() ?: "-" }
    }
}

/**
 * An idempotent challenge may be replayed only while it is still actionable: still PENDING,
 * not yet consumed, and not expired. A challenge that already carries a (write-once) device
 * decision is additionally excluded by the caller via the decision store.
 */
private fun ScaChallenge.isReplayable(now: OffsetDateTime): Boolean =
    status == ScaStatus.PENDING && consumedAt == null && !isExpired(now)

/**
 * The `openbank.sca.events` discriminator. Declared once so the outbox column and the payload
 * body cannot drift apart again — they are read by different consumers and only the body
 * reaches onboarding-service.
 */
private const val DEVICE_ENROLLED_EVENT_TYPE = "DEVICE_ENROLLED"

/**
 * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
 * (EVENT-sourced) attribution — issue #3994/#5256. `eventType` ("DEVICE_ENROLLED") is unchanged
 * (load-bearing for onboarding-service's `OnboardingEventConsumer`, and customer-edge already
 * writes its own distinct "SCA_DEVICE_ENROLLED" eventType for a different event on a different
 * topic, so there is no fleet-wide collision to worry about). `sourceService` has no consumer
 * today, so it is safe to add net-new. Value matches the fleet's audit convention: the module
 * directory without the `openbank-` prefix — audit-service does not currently subscribe to
 * `openbank.sca.events` at all (absent from both `TopicAttribution` and its consumed-topics
 * list), so this field is forward-looking rather than fixing a live "unknown" row today.
 */
private const val SOURCE_SERVICE = "sca-service"

private fun Throwable.causedByUniqueViolation(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t.message?.contains("23505") == true) return true
        t = t.cause
    }
    return false
}
