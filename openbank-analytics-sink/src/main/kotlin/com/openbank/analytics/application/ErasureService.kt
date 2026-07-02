// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.openbank.analytics.application.port.out.CryptoErasure
import com.openbank.libs.analytics.AggregateKey
import com.openbank.libs.analytics.LegalBasis
import com.openbank.libs.analytics.RetentionPolicies
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import org.jboss.logging.Logger

/**
 * Outcome of a GDPR Art. 17 erasure request against the analytics layer (ADR-0023, F6).
 *
 * [erased] = the request was honoured (category erasable, crypto-shred performed). [refused] = a
 * statutory hold (AML/accounting, Art. 17(3)(b)) overrides erasure; [legalBasis] explains why and is
 * the documented, defensible position to show a supervisor / the data subject.
 */
data class ErasureDecision(
    val aggregateType: String,
    val aggregateId: String,
    val erased: Boolean,
    val rowsAffected: Long,
    val legalBasis: String,
    val explanation: String,
    val decidedAt: Instant,
    val decidedBy: String,
)

/**
 * Applies the per-category retention policy to an erasure request. The legal gate (is this category
 * erasable?) is decided by [RetentionPolicies]; only when erasable is the physical [CryptoErasure]
 * invoked. Categories under a legal-obligation hold are refused with an auditable explanation — the
 * analytics layer already masks directly-identifying PII at the sink and keeps only the pseudonymous
 * aggregateId for the regulatory-retention period.
 */
@ApplicationScoped
class ErasureService {

    @Inject lateinit var cryptoErasure: CryptoErasure

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(ErasureService::class.java)

    // CodeQL java/log-injection: aggregateType/aggregateId/requestedBy are caller-supplied and
    // flow straight into log lines below. Strip CR/LF so an attacker can't forge additional
    // log lines (log forging, CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    suspend fun erase(aggregateType: String, aggregateId: String, requestedBy: String): ErasureDecision {
        val category = RetentionPolicies.categoryForAggregateType(aggregateType)
        val policy = RetentionPolicies.of(category)
        val now = Instant.now(clock)

        if (!policy.erasable) {
            log.infof(
                "erasure REFUSED %s/%s category=%s basis=%s by=%s",
                aggregateType.sanitizeForLog(),
                aggregateId.sanitizeForLog(),
                category,
                policy.basis,
                requestedBy.sanitizeForLog(),
            )
            return ErasureDecision(
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                erased = false,
                rowsAffected = 0,
                legalBasis = policy.basis.name,
                explanation = "Erasure refused: $category is under a ${policy.basis} hold " +
                    "(GDPR Art. 17(3)(b) — statutory record-keeping overrides erasure for ${policy.retention}). " +
                    "Directly-identifying PII is already masked at the sink; only the pseudonymous id is retained.",
                decidedAt = now,
                decidedBy = requestedBy,
            )
        }

        val rows = cryptoErasure.erase(AggregateKey(aggregateType, aggregateId))
        log.infof(
            "erasure PERFORMED %s/%s category=%s rows=%d by=%s",
            aggregateType.sanitizeForLog(),
            aggregateId.sanitizeForLog(),
            category,
            rows,
            requestedBy.sanitizeForLog(),
        )
        return ErasureDecision(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            erased = true,
            rowsAffected = rows,
            legalBasis = (if (policy.basis == LegalBasis.CONSENT) "CONSENT_WITHDRAWN" else policy.basis.name),
            explanation = "Crypto-shredded analytics data for $category (no statutory hold).",
            decidedAt = now,
            decidedBy = requestedBy,
        )
    }
}
