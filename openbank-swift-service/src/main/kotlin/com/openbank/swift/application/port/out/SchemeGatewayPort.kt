// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.port.out

import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.model.SwiftMessage

/**
 * The SWIFT rail's single exit to "the network" (ADR-0104 D4): convert the MT103 SWIFT message
 * to an ISO 20022 `pacs.008`, hand it to the scheme, and return the `pacs.002` verdict. Only
 * implementation today is the `openbank-clearing-simulator`; a real SWIFT SWIFTNet adapter slots
 * in behind the same port unchanged. Also populates `rawMt` on the message.
 */
interface SchemeGatewayPort {
    suspend fun submit(message: SwiftMessage): SchemeSubmissionOutcome

    /**
     * Build the `pacs.009` (FICdtTrf, CORE) for a bank-own [transfer], XSD-validate it, submit it
     * on the transfer's rail (CERTIS / TARGET2 / SWIFT) and map the `pacs.002`. Throws
     * [SchemeGatewayUnavailableException] when the scheme cannot be reached (retryable) and
     * [NonConformingMessageException] when the built message fails its schema (not retryable).
     * The default exists only so test doubles of the MT103 path keep compiling; the production
     * adapter overrides it.
     */
    suspend fun submitInstitutionTransfer(transfer: InstitutionTransfer): InstitutionTransferSubmission =
        throw UnsupportedOperationException("this gateway does not carry bank-own transfers")
}

/** The scheme's verdict on a `pacs.009`, from the `pacs.002` `TxSts`. */
enum class InstitutionTransferVerdict {
    /** `RCVD` / `ACSP`: taken for processing, settlement not yet confirmed. */
    IN_PROCESS,

    /** `ACSC`: settled on the rail. */
    SETTLED,

    /** `RJCT`, carrying the reason code. */
    REJECTED,
}

/** Outcome of [SchemeGatewayPort.submitInstitutionTransfer]; [rawPacs009] is what went on the wire. */
data class InstitutionTransferSubmission(
    val verdict: InstitutionTransferVerdict,
    val reasonCode: String?,
    val rawPacs009: String,
)

/** The rail built a message its own schema rejects — a defect, never retried. */
class NonConformingMessageException(message: String) : IllegalStateException(message)

/**
 * The scheme's verdict on a submitted credit transfer, mapped from the `pacs.002` `TxSts`.
 * [accepted] is true for `ACSC`; [reasonCode] carries the `ExternalStatusReason1Code` on reject.
 * [rawMt] is the ISO 20022 pacs.008 XML that was actually submitted on the wire.
 */
data class SchemeSubmissionOutcome(val accepted: Boolean, val reasonCode: String?, val rawMt: String)

/** Thrown when the scheme gateway is unreachable; the rail holds the message in VALIDATED. */
class SchemeGatewayUnavailableException(cause: Throwable) : RuntimeException("scheme gateway unavailable", cause)
