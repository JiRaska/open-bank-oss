// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.domain.event

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

sealed class SctInstEvent {
    abstract val paymentId: UUID
    abstract val occurredAt: OffsetDateTime

    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` (audit-service) as the
     * strongest (EVENT-sourced) attribution — issue #3994/#5256. `EventAttribution.TopicAttribution`
     * already maps `openbank.sepa.instant.events` -> `sepa-instant` correctly, but only as
     * TOPIC-sourced, not the producer's own claim, and audit-service subscribes to this topic
     * today (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics
     * list), so this is a live attribution upgrade. `KafkaSctInstEventPublisher.publish` builds a
     * HAND-BUILT map, not a serialised data class, so this property alone does not reach the
     * wire — it is copied into that map explicitly.
     */
    val sourceService: String = SOURCE_SERVICE

    companion object {
        internal const val SOURCE_SERVICE = "sepa-instant"
    }
}

data class SctInstPaymentSubmitted(
    override val paymentId: UUID,
    val debtorIban: String,
    val creditorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val endToEndId: String,
    override val occurredAt: OffsetDateTime,
) : SctInstEvent()

data class SctInstPaymentSettled(
    override val paymentId: UUID,
    val settledAt: OffsetDateTime,
    override val occurredAt: OffsetDateTime,
) : SctInstEvent()

data class SctInstPaymentRejected(
    override val paymentId: UUID,
    val reason: String,
    override val occurredAt: OffsetDateTime,
) : SctInstEvent()

data class SctInstPaymentTimeout(override val paymentId: UUID, override val occurredAt: OffsetDateTime) : SctInstEvent()

data class SctInstPaymentRecalled(
    override val paymentId: UUID,
    val recallReason: String,
    override val occurredAt: OffsetDateTime,
) : SctInstEvent()
