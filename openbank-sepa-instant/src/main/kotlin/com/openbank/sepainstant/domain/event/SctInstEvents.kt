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
