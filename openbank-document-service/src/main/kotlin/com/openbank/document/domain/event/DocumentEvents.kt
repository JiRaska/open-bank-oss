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

/**
 * Producing service, read by `AuditConsumer.resolveSourceService` (audit-service) as the
 * strongest (EVENT-sourced) attribution — issue #3994/#5256. `EventAttribution.TopicAttribution`
 * already maps `openbank.documents.document.event` -> `document-service` correctly, but only as
 * TOPIC-sourced, not the producer's own claim, and audit-service subscribes to this topic today
 * (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics list), so this
 * is a live attribution upgrade for the two event types that actually reach the outbox
 * ([DocumentGenerated], [SignatureCeremonyCompleted]). Declared on every type here, not just the
 * two wired ones, for the same reason the file-level comment above gives for `occurredAt`: one
 * vocabulary in one file is what stops the next event that IS wired up from being copied off a
 * sibling missing the field.
 */
private const val SOURCE_SERVICE = "document-service"

data class DocumentTemplatePublished(
    val templateId: UUID,
    val code: String,
    val version: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
)

data class DocumentGenerated(
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
)

data class DocumentSigned(
    val documentId: UUID,
    val ceremonyId: UUID,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
)

data class SignatureCeremonyCompleted(
    val ceremonyId: UUID,
    val documentId: UUID,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
)
