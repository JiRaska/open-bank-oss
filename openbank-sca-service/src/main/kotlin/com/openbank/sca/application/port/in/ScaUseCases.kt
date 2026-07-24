// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.port.`in`

import com.openbank.sca.domain.model.*
import java.util.UUID

data class InitiateScaCommand(
    val partyId: UUID,
    val purpose: ScaPurpose,
    val preferredMethod: ScaMethod?,
    val dynamicLinkingData: DynamicLinkingData?,
    val redirectUrl: String?,
)

data class VerifyScaCommand(val challengeId: UUID, val partyId: UUID, val otp: String?)

data class EnrollDeviceCommand(
    val partyId: UUID,
    val credentialId: String,
    val publicKeySpkiB64: String,
    val algorithm: SignatureAlgorithm,
)

data class RecordDeviceDecisionCommand(
    val challengeId: UUID,
    val credentialId: String,
    val decision: DeviceDecisionType,
    val signatureB64: String,
)

interface InitiateScaUseCase {
    suspend fun initiate(command: InitiateScaCommand): ScaChallenge
}

interface VerifyScaUseCase {
    suspend fun verify(command: VerifyScaCommand): ScaChallenge
}

interface GetScaUseCase {
    suspend fun getChallenge(challengeId: UUID): ScaChallenge

    /** Live challenges awaiting a decision for [partyId] (decoupled/push approval list, ADR-0021). */
    suspend fun listPendingByParty(partyId: UUID): List<ScaChallenge>
}

/** Enrol a device credential to a party so it can later sign decoupled approvals (ADR-0021). */
interface EnrollDeviceUseCase {
    suspend fun enroll(command: EnrollDeviceCommand): EnrolledDevice
}

/**
 * Record an out-of-band approval/denial from the enrolled device. The assertion is
 * signature-verified and dynamic-linking-checked before it is stored; only then can
 * [VerifyScaUseCase.verify] complete a push/biometric challenge.
 */
interface RecordDeviceDecisionUseCase {
    suspend fun recordDecision(command: RecordDeviceDecisionCommand): ScaChallenge
}

/**
 * Atomic compare-and-consume of an approved challenge (ADR-0021 settlement gate).
 * The caller states the operation it is ABOUT TO EXECUTE; the challenge is spent only when
 * the stored, device-signed dynamic-linking data authorises exactly that operation.
 */
data class ConsumeScaCommand(
    val challengeId: UUID,
    val expectedPartyId: UUID,
    val amount: String?,
    val currency: String?,
    val creditor: String?,
    val documentSha256: String? = null,
    val ceremonyId: String? = null,
    val cardId: String? = null,
    val cardAction: String? = null,
)

/**
 * Spend an approved challenge on the operation it authorised. Single-use: a consumed
 * challenge can never gate a second operation (replay protection, RTS Art. 5).
 */
interface ConsumeScaUseCase {
    suspend fun consume(command: ConsumeScaCommand): ScaChallenge
}

data class ListDevicesQuery(val partyId: UUID)

/** List device credentials enrolled to a party (used by the onboarding cockpit, ADR-0068). */
interface ListDevicesUseCase {
    suspend fun listDevices(query: ListDevicesQuery): List<EnrolledDevice>
}
