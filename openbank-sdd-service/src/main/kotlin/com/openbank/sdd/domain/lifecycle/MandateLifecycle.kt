// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.domain.lifecycle

import com.openbank.sdd.domain.model.MandateAmendment
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SequenceType
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Raised when a lifecycle transition is not permitted from the current state (ADR-0036 §B). */
class IllegalMandateTransition(from: MandateStatus, to: String) :
    RuntimeException("Illegal mandate transition: $from -> $to")

/**
 * Pure mandate state machine (ADR-0036 §B). Every function returns a new [SddMandate] or throws
 * [IllegalMandateTransition]; no framework, no wall-clock — callers pass `asOf`.
 */
object MandateLifecycle {

    /** EPC auto-expiry: a mandate with no collection for 36 months lapses. */
    const val IDLE_EXPIRY_MONTHS = 36L

    /** B2B confirmation (or explicit Core activation): PENDING_CONFIRMATION -> ACTIVE. */
    fun confirm(m: SddMandate): SddMandate {
        if (m.status != MandateStatus.PENDING_CONFIRMATION) {
            throw IllegalMandateTransition(m.status, "ACTIVE(confirm)")
        }
        return m.copy(status = MandateStatus.ACTIVE, b2bConfirmed = true)
    }

    /** ACTIVE -> SUSPENDED (debtor or bank temporarily parks the mandate). */
    fun suspend(m: SddMandate): SddMandate {
        if (m.status != MandateStatus.ACTIVE) throw IllegalMandateTransition(m.status, "SUSPENDED")
        return m.copy(status = MandateStatus.SUSPENDED)
    }

    /** SUSPENDED -> ACTIVE. */
    fun resume(m: SddMandate): SddMandate {
        if (m.status != MandateStatus.SUSPENDED) throw IllegalMandateTransition(m.status, "ACTIVE(resume)")
        return m.copy(status = MandateStatus.ACTIVE)
    }

    /** Any non-terminal state -> CANCELLED (debtor revocation). Terminal. */
    fun cancel(m: SddMandate): SddMandate {
        if (m.isTerminal) throw IllegalMandateTransition(m.status, "CANCELLED")
        return m.copy(status = MandateStatus.CANCELLED)
    }

    /** Record an amendment; mandate must be live (ACTIVE/SUSPENDED). Status is unchanged. */
    fun amend(m: SddMandate, field: String, oldValue: String, newValue: String, asOf: Instant): SddMandate {
        if (m.isTerminal || m.status == MandateStatus.PENDING_CONFIRMATION) {
            throw IllegalMandateTransition(m.status, "AMEND")
        }
        val amendment = MandateAmendment(field, oldValue, newValue, asOf)
        return m.copy(amendments = m.amendments + amendment)
    }

    /** Stamp a settled collection: advances FRST -> RCUR and records the date (drives idle-expiry). */
    fun recordCollection(m: SddMandate, date: LocalDate): SddMandate {
        val nextSeq = if (m.sequenceType == SequenceType.FRST) SequenceType.RCUR else m.sequenceType
        return m.copy(sequenceType = nextSeq, lastCollectionDate = date)
    }

    /** Record a creditor pre-notification date (≥14 days before due, tracked not enforced — §E). */
    fun recordPreNotification(m: SddMandate, date: LocalDate): SddMandate =
        m.copy(lastPreNotificationDate = date)

    /** Auto-expire a live mandate idle for [IDLE_EXPIRY_MONTHS]; otherwise returned unchanged. */
    fun expireIfIdle(m: SddMandate, asOf: LocalDate): SddMandate {
        if (m.status != MandateStatus.ACTIVE && m.status != MandateStatus.SUSPENDED) return m
        return if (isIdle(lastActivity(m), asOf)) m.copy(status = MandateStatus.EXPIRED) else m
    }

    /** The activity anchor for idle-expiry: last collection, else the signature date. */
    fun lastActivity(m: SddMandate): LocalDate = m.lastCollectionDate ?: m.signatureDate

    /** True once [IDLE_EXPIRY_MONTHS] have fully elapsed since [lastActivity]. */
    fun isIdle(lastActivity: LocalDate, asOf: LocalDate): Boolean =
        ChronoUnit.MONTHS.between(lastActivity, asOf) >= IDLE_EXPIRY_MONTHS
}
