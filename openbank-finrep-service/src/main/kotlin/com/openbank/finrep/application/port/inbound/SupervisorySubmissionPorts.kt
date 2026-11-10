// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.inbound

import com.openbank.finrep.domain.model.SubmissionAttempt
import com.openbank.finrep.domain.model.SubmissionKey
import com.openbank.finrep.domain.model.SupervisorySubmission
import java.util.UUID

data class RecordAcknowledgementCommand(val accepted: Boolean, val reference: String, val message: String?)

interface SupervisorySubmissionUseCase {
    /** Idempotent per [SubmissionKey]: a repeat returns the existing submission, `created = false`. */
    suspend fun open(key: SubmissionKey, actor: String): Pair<SupervisorySubmission, Boolean>

    suspend fun pkg(id: UUID, actor: String): SupervisorySubmission

    suspend fun submit(id: UUID, actor: String): SupervisorySubmission

    suspend fun acknowledge(id: UUID, command: RecordAcknowledgementCommand, actor: String): SupervisorySubmission

    suspend fun get(id: UUID): SupervisorySubmission

    suspend fun list(templateId: String?): List<SupervisorySubmission>

    suspend fun attempts(id: UUID): List<SubmissionAttempt>
}
