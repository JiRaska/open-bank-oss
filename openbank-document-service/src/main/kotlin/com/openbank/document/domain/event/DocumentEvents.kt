// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.event

import java.time.Instant
import java.util.UUID

data class DocumentTemplatePublished(val templateId: UUID, val code: String, val version: String, val at: Instant)

data class DocumentGenerated(
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val at: Instant,
)

data class DocumentSigned(val documentId: UUID, val ceremonyId: UUID, val at: Instant)

data class SignatureCeremonyCompleted(val ceremonyId: UUID, val documentId: UUID, val at: Instant)
