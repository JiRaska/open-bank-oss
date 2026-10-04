// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.approval

import com.openbank.libs.authz.ApprovalSummaryRenderer
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.infrastructure.rest.ConsumeScaRequest
import com.openbank.sca.infrastructure.rest.EnrollDeviceRequest
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Renders the checker-facing summary of an SCA four-eyes approval from the arguments the request
 * fingerprint binds ([ScaApprovalSummaries] defines the content). The lookups (device for a
 * revocation, challenge for a consume) run once, at approval creation; the stored text is what the
 * checker reads afterwards, so a later revocation or consumption does not rewrite it.
 *
 * Actions it does not cover keep the shared generic summary (`null`).
 */
@ApplicationScoped
class ScaApprovalSummaryRenderer(
    private val devices: EnrolledDeviceRepository,
    private val challenges: ScaChallengeRepository,
) : ApprovalSummaryRenderer {

    override suspend fun render(action: String, resourceId: String?, arguments: Map<String, Any?>): String? =
        when (action) {
            "device.enroll" -> {
                val partyId = arguments["partyId"] as? UUID
                val request = arguments["request"] as? EnrollDeviceRequest
                if (partyId == null || request == null) {
                    null
                } else {
                    ScaApprovalSummaries.enroll(partyId, request.credentialId, request.publicKey, request.algorithm)
                }
            }
            "device.revoke" -> {
                val partyId = arguments["partyId"] as? UUID
                val deviceId = arguments["deviceId"] as? UUID
                if (partyId == null || deviceId == null) {
                    null
                } else {
                    val device = devices.findByPartyId(partyId).find { it.id == deviceId }
                    ScaApprovalSummaries.revoke(partyId, deviceId, device)
                }
            }
            "scaChallenge.consume" -> {
                val challengeId = arguments["id"] as? UUID
                val request = arguments["request"] as? ConsumeScaRequest
                if (challengeId == null || request == null) {
                    null
                } else {
                    ScaApprovalSummaries.consume(
                        challengeId = challengeId,
                        partyId = request.partyId,
                        challenge = challenges.findById(challengeId)?.takeIf { it.partyId == request.partyId },
                        amount = request.amount,
                        currency = request.currency,
                        creditor = request.creditor,
                        cardAction = request.cardAction,
                        documentSha256 = request.documentSha256,
                    )
                }
            }
            else -> null
        }
}
