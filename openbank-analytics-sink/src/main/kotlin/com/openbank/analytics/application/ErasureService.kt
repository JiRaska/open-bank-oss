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
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant

/**
 * Outcome of a GDPR Art. 17 erasure request against the analytics layer (ADR-0023, F6).
 *
 * [erased] = the request was honoured (category erasable, crypto-shred performed). [refused] = a
 * statutory hold (AML/accounting, Art. 17(3)(b)) overrides erasure; [legalBasis] explains why and is
 * the documented, defensible position to show a supervisor / the data subject.
 */
enum class ErasureOutcome {
    /** Key material was destroyed by a real backend. */
    ERASED,

    /** A statutory hold overrides erasure (GDPR Art. 17(3)(b)). */
    REFUSED_LEGAL_HOLD,

    /**
     * No erasure backend is present in this build, so nothing was destroyed.
     *
     * Its own value rather than a `false` shared with [REFUSED_LEGAL_HOLD], because the two are
     * opposite kinds of answer: one is a defensible legal position, the other is a gap in the
     * deployment. Folding them together is the same mistake as the `erased` flag it replaces —
     * see #4348, where `PushResult.skipped()` carrying `success = true` counted undelivered
     * pushes as delivered until a customer reported it.
     */
    NO_BACKEND,
}

data class ErasureDecision(
    val aggregateType: String,
    val aggregateId: String,
    val outcome: ErasureOutcome,
    val rowsAffected: Long,
    val legalBasis: String,
    val explanation: String,
    val decidedAt: Instant,
    val decidedBy: String,
) {
    /**
     * Kept for the wire and for readers, but DERIVED — it cannot disagree with [outcome].
     *
     * It used to be a constructor parameter passed the literal `true` on every non-refusal path,
     * which is how a no-op erasure reported success (#9671).
     */
    val erased: Boolean
        get() = outcome == ErasureOutcome.ERASED
}

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
                outcome = ErasureOutcome.REFUSED_LEGAL_HOLD,
                rowsAffected = 0,
                legalBasis = policy.basis.name,
                explanation = "Erasure refused: $category is under a ${policy.basis} hold " +
                    "(GDPR Art. 17(3)(b) — statutory record-keeping overrides erasure for ${policy.retention}). " +
                    "Directly-identifying PII is already masked at the sink; only the pseudonymous id is retained.",
                decidedAt = now,
                decidedBy = requestedBy,
            )
        }

        // The category is erasable, so the legal gate is open. Whether anything is actually
        // destroyed is a property of the BINDING, and this build may not have a real one: the
        // vault adapter is `@IfBuildProperty(openbank.analytics.erasure.backend=vault)`, resolved
        // at augmentation, and the default is a logged no-op. Asking the port rather than assuming
        // is the whole fix for #9671 — `rows == 0` cannot stand in for it, because a real backend
        // returns 0 for a subject with no warehouse data.
        if (!cryptoErasure.performsErasure) return noBackendDecision(aggregateType, aggregateId, requestedBy, now)

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
            outcome = ErasureOutcome.ERASED,
            rowsAffected = rows,
            legalBasis = (if (policy.basis == LegalBasis.CONSENT) "CONSENT_WITHDRAWN" else policy.basis.name),
            explanation = "Crypto-shredded analytics data for $category (no statutory hold).",
            decidedAt = now,
            decidedBy = requestedBy,
        )
    }

    /**
     * The category is erasable and the legal gate is open, but this build has no backend that can
     * destroy key material — so the request stands unfulfilled and must not be reported as done.
     *
     * Extracted because inlining it pushed [erase] past detekt's `LongMethod` (67 vs 60); the class
     * has room for another function where [AnalyticsConsumer] did not.
     */
    private fun noBackendDecision(
        aggregateType: String,
        aggregateId: String,
        requestedBy: String,
        now: Instant,
    ): ErasureDecision {
        val category = RetentionPolicies.categoryForAggregateType(aggregateType)
        log.warnf(
            "erasure NOT PERFORMED (no backend in this build) %s/%s category=%s by=%s",
            aggregateType.sanitizeForLog(),
            aggregateId.sanitizeForLog(),
            category,
            requestedBy.sanitizeForLog(),
        )
        return ErasureDecision(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            outcome = ErasureOutcome.NO_BACKEND,
            rowsAffected = 0,
            legalBasis = RetentionPolicies.of(category).basis.name,
            explanation = "No erasure backend is configured in this build, so no analytics data " +
                "was crypto-shredded for $category. This is NOT a refusal on legal grounds: the " +
                "category is erasable and the request stands unfulfilled (ADR-0023 F6, #9671).",
            decidedAt = now,
            decidedBy = requestedBy,
        )
    }
}
