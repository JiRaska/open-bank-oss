// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.domain.event

import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/**
 * `interest.rate.changed.v1` (ADR-0314 D5): a rate configuration was created, deactivated, or had
 * its validity cut short by a newer one. The risk engine needs every such change to reprice
 * deposits as of any date, and until this event existed the only way to learn one was to poll
 * `GET /rates`.
 *
 * The payload is the FULL configuration after the change, not a diff, so a consumer never needs a
 * previous event to understand this one. The outbox row is always written in the same transaction
 * as the configuration row it describes.
 *
 * Built by hand, like this service's other payloads, so the wire shape is fixed here and not by an
 * ObjectMapper configuration elsewhere.
 */
object InterestRateChanged {
    const val EVENT_TYPE = "interest.rate.changed.v1"

    enum class Change {
        /** A new configuration, from the operator API or a catalog profile. */
        CREATED,

        /** An operator deactivated it; it no longer applies from any date. */
        DEACTIVATED,

        /** A newer catalog configuration took over, so `effectiveTo` was cut to the day before. */
        SUPERSEDED,
    }

    fun outboxMessage(config: InterestRateConfig, change: Change, occurredAt: Instant): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = config.id,
        eventType = EVENT_TYPE,
        payload = payload(config, change, occurredAt),
        createdAt = occurredAt,
    )

    internal fun payload(c: InterestRateConfig, change: Change, occurredAt: Instant): String = buildString {
        append("{\"schemaVersion\":1")
        field("change", change.name)
        field("configId", c.id.toString())
        field("productId", c.productId)
        field("accountId", c.accountId?.toString())
        field("currency", c.currency)
        field("rateType", c.rateType.name)
        field("annualRate", c.annualRate.toPlainString())
        field("rateIndex", c.rateIndex?.name)
        field("spread", c.spread?.toPlainString())
        field("minBalance", c.minBalance.toPlainString())
        field("maxBalance", c.maxBalance?.toPlainString())
        field("dayCount", c.dayCount.name)
        field("effectiveFrom", c.effectiveFrom.toString())
        field("effectiveTo", c.effectiveTo?.toString())
        append(",\"active\":").append(c.active)
        field("occurredAt", occurredAt.toString())
        field("sourceService", "interest-service")
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String?) {
        append(",\"").append(name).append("\":")
        if (value == null) append("null") else append('"').append(escape(value)).append('"')
    }

    private fun escape(s: String): String = buildString {
        s.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }
}
