// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.`in`

import com.openbank.sanctions.domain.model.EntityType
import java.time.Instant

/** The body of a registration: the attributes the CUSTOMER screening profile reads. */
data class MonitoredSubjectRequest(
    val entityType: EntityType,
    val name: String,
    // Nullable element for the same reason as ScreenEntityCommand.aliases (#7867): Jackson does
    // not null-check collection elements, so `[null]` must be rejected with a 400, not an NPE.
    val aliases: List<String?> = emptyList(),
    val dateOfBirth: String? = null,
    val nationality: String? = null,
    /** ISO 3166-1 alpha-2; a listed jurisdiction raises a finding whatever the name is. */
    val country: String? = null,
)

/** What a caller learns about its registration. It never says whether anything matched. */
data class MonitoredSubjectRegistration(
    val ownerService: String,
    val subjectRef: String,
    val registeredAt: Instant,
    val updatedAt: Instant,
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val created: Boolean,
)

interface SanctionsMonitoringUseCase {
    /** Idempotent: registering the same attributes again changes nothing; new attributes replace the old. */
    suspend fun register(
        ownerService: String,
        subjectRef: String,
        request: MonitoredSubjectRequest,
    ): MonitoredSubjectRegistration

    /** Deletes the subject and its findings. Idempotent, and allowed while monitoring is disabled. */
    suspend fun deregister(ownerService: String, subjectRef: String): Boolean
}
