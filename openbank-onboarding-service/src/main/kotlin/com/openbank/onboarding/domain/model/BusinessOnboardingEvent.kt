// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The parsed shape of one `openbank.kyb.events` message (contract:
 * `openbank-contracts/openbank-kyb-service/asyncapi.yaml`). Framework-free — the consumer maps
 * JSON into this, the projection consumes only this.
 *
 * Every field except [caseId], [status] and [occurredAt] is nullable because the wire contract says
 * so: a case that never reached the register carries no legal name, and a case with an unparsed
 * representation rule carries no signature count. Nothing here is defaulted to a plausible value —
 * a zero signature requirement would show an operator a case that needs nobody.
 */
data class BusinessOnboardingEvent(
    val eventType: String,
    val caseId: UUID,
    val status: BusinessCaseStage,
    val identifierScheme: String,
    val identifier: String,
    val country: String?,
    val legalName: String?,
    val legalFormClass: String?,
    val initiatorPartyId: UUID,
    val entityPartyId: UUID?,
    val requiredSignatures: Int?,
    val signedCount: Int,
    val reviewReason: String?,
    val occurredAt: Instant,
)
