// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.event

import java.time.Instant
import java.util.UUID

/*
 * `occurredAt`, not `at`: it is the fleet's single accepted spelling for a domain event's business
 * time, and the only one `openbank-audit-service`'s AuditConsumer reads (#3907). While these events
 * spelled it `at`, every audit row for `openbank.documents.document.event` recorded the CONSUMER's
 * ingest clock as the business time, and `openbank-analytics-sink` — a second consumer of the same
 * topic, with the same `node["occurredAt"] ?: now` fallback — measured the ingest lag of these
 * events as zero by construction (#3914).
 *
 * All four events carry the name even though only DocumentGenerated and SignatureCeremonyCompleted
 * reach the outbox today: one vocabulary in one file is what stops the next event that IS wired up
 * from being copied off the wrong sibling.
 */

data class DocumentTemplatePublished(
    val templateId: UUID,
    val code: String,
    val version: String,
    val occurredAt: Instant,
)

data class DocumentGenerated(
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val occurredAt: Instant,
)

data class DocumentSigned(val documentId: UUID, val ceremonyId: UUID, val occurredAt: Instant)

data class SignatureCeremonyCompleted(val ceremonyId: UUID, val documentId: UUID, val occurredAt: Instant)
