// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.shadow

import com.openbank.billing.domain.ProductShadowInput
import com.openbank.billing.domain.ShadowLegacyFee
import com.openbank.billing.domain.ShadowRevisionIdentity
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.productrevision.RevisionIdentity
import java.math.BigDecimal
import java.util.UUID

/** A current-account-shaped IR: one monthly 4.90 fee, waived at a balance of 1 000 or more. */
object ShadowFixtures {
    const val IR = """{"irVersion":1,"productCode":"CA-TEST","currency":"EUR",
"fees":[{"ruleId":"fee.maintenance","feeCode":"MAINT","amount":"4.90","cadence":"MONTHLY","accountingClass":"FEE_INCOME",
"waiver":{"type":"comparison","attribute":"BALANCE","operator":"GTE","threshold":"1000.00","currency":"EUR"}}],
"hooks":{"onOpen":[{"ruleId":"open.schedule-fee","action":{"type":"SCHEDULE","hook":"scheduled","tag":"FEE_MAINT","cadence":"MONTHLY"}}],
"scheduled":[{"ruleId":"scheduled.fee","trigger":"FEE_MAINT","action":{"type":"CHARGE_FEE","feeRuleId":"fee.maintenance"}}]}}"""

    const val SECRET = "test-only-ir-signing-secret-not-for-production"

    fun identity(revisionId: String, json: String = IR): ShadowRevisionIdentity {
        val ir = ProductIrParser.parse(json)
        return ShadowRevisionIdentity(ir.productCode, revisionId, 7, ir.irVersion, ir.revisionHash)
    }

    fun pin(revisionId: String, json: String = IR): RevisionIdentity {
        val identity = identity(revisionId, json)
        return RevisionIdentity(
            identity.productCode,
            UUID.fromString(identity.revisionId),
            identity.revisionNumber,
            identity.irVersion,
            identity.revisionHash,
        )
    }

    fun input(balance: String?, legacy: List<ShadowLegacyFee>, revisionId: String = UUID.randomUUID().toString()) =
        ProductShadowInput(
            cycleId = "2026-10",
            accountId = "acc-1",
            currency = "EUR",
            productRevisionId = revisionId,
            expectedRevision = identity(revisionId),
            balance = balance?.let(::BigDecimal),
            monthlyTurnover = null,
            aggregatePocketBalance = null,
            segment = null,
            legacyFees = legacy,
            evaluatedAt = java.time.Instant.parse("2026-10-20T12:00:00Z"),
            evaluationZone = "Z",
            openedAt = java.time.Instant.parse("2025-01-01T00:00:00Z"),
            scheduledCadence = "MONTHLY",
            scheduledTrigger = "FEE_MAINT",
        )

    fun charged(amount: String = "4.90") = ShadowLegacyFee("MAINT", BigDecimal(amount), waived = false)

    fun waived() = ShadowLegacyFee("MAINT", BigDecimal.ZERO, waived = true)
}
