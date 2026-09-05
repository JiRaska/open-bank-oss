// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.SignatureAlgorithm
import java.util.UUID

/** Outbound persistence port for the SCA challenge aggregate. */
interface ScaChallengeRepository {

    suspend fun save(challenge: ScaChallenge): ScaChallenge

    suspend fun findById(id: UUID): ScaChallenge?

    /** Live (PENDING, not yet expired) challenges awaiting a decision for [partyId] — newest first. */
    suspend fun findPendingByParty(partyId: UUID): List<ScaChallenge>

    /**
     * Atomically mark the challenge consumed (single-use gate). Returns true when THIS call
     * spent it; false when it was already consumed — the `consumed_at IS NULL` guard in the
     * UPDATE makes concurrent double-spends impossible at the database level.
     */
    suspend fun markConsumed(id: UUID): Boolean
}

/** Generates the one-time password presented to the user during step-up authentication. */
interface OtpGenerator {

    fun generate(): String
}

/** Outbound port for transient storage and verification of one-time passwords. */
interface OtpStore {

    suspend fun store(challengeId: UUID, otp: String, ttlSeconds: Long)

    suspend fun verify(challengeId: UUID, otp: String): Boolean

    suspend fun invalidate(challengeId: UUID)
}

/** Outbound port that maps an idempotency key to a previously created challenge id. */
interface ScaIdempotencyStore {

    suspend fun get(key: String): String?

    suspend fun save(key: String, challengeId: UUID, ttlSeconds: Long)
}

/** Outbound port for delivering SCA challenges to the user out-of-band (push). */
interface NotificationSender {

    suspend fun sendPushNotification(partyId: UUID, challengeId: UUID, message: String)
}

/** Durable store of device credentials enrolled to a party (ADR-0021). */
interface EnrolledDeviceRepository {

    /**
     * Persists [device] and its [outboxMessage] in ONE transaction, so a crash can never leave a
     * device enrolled with `sca.device_enrolled` lost (#8679). The predecessor `save(device)` was
     * called next to a separately-transacted `ScaOutboxRepository.save(message)`; measured on
     * `origin/main`, the device row carried xmin 751 and its outbox row xmin 752 — two writing
     * transactions. Same shape as `DocumentRepositoryPort`/`FxConversionRepository.saveWithOutbox`.
     */
    suspend fun saveWithOutbox(device: EnrolledDevice, outboxMessage: OutboxMessage): EnrolledDevice

    suspend fun findByCredentialId(credentialId: String): EnrolledDevice?

    suspend fun findByPartyId(partyId: UUID): List<EnrolledDevice>
}

/**
 * Transient store of signature-verified device decisions, keyed by challenge id.
 * Mirrors [OtpStore]: a decision only needs to outlive its challenge.
 */
interface ScaDecisionStore {

    suspend fun record(decision: DeviceApprovalDecision, ttlSeconds: Long)

    suspend fun find(challengeId: UUID): DeviceApprovalDecision?
}

/**
 * Verifies a device's cryptographic assertion over the dynamic-linking payload.
 * Implementations must **fail closed**: any malformed key/signature returns false,
 * never throws through to a success path.
 */
interface DeviceAssertionVerifier {

    fun verify(
        publicKeySpkiB64: String,
        algorithm: SignatureAlgorithm,
        payload: ByteArray,
        signatureB64: String,
    ): Boolean
}
