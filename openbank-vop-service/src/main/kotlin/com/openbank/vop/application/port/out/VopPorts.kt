// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.application.port.out

import com.openbank.vop.domain.model.VopVerification
import io.smallrye.mutiny.Uni

/**
 * Raised when a name lookup could not be completed. Unlike the sanctions gate's
 * `ScreeningUnavailableException`, this does NOT hold the payment: VoP fails **open** with a
 * `NO_DATA` warning (ADR-0171 §3), because refusing every payment during a VoP outage would
 * itself breach the IPR execution-time obligation.
 */
class NameLookupUnavailableException(cause: Throwable) :
    RuntimeException("Payee name lookup is unavailable; VoP returns NO_DATA", cause)

/**
 * Resolves the account-holder name held against one of *our* IBANs — the responder side of VoP
 * (ADR-0171 §4). Two hops behind this port: account-service (IBAN → partyId) then party-service
 * (partyId → legal/trading name). `openbank-account-service` holds no name of its own.
 *
 * Returns `null` when the IBAN is ours but no name is resolvable (unknown account, or an account
 * with no party name) — a NO_DATA case, distinct from a lookup failure, which throws.
 */
interface AccountHolderNameLookupPort {
    fun lookupHolderName(iban: String): Uni<String?>
}

/**
 * Routes a VoP request to the payee's PSP through the EPC VoP scheme — the requester side
 * (ADR-0171 §4).
 *
 * This is a seam, not a capability. There is no EPC VoP routing link in this platform, exactly
 * as the interbank rails reach only `openbank-clearing-simulator`. The shipped implementation
 * answers `NO_DATA` / `NO_SCHEME_CONNECTIVITY` for every external IBAN; a real adapter plugs in
 * here without touching the use case.
 */
interface VopSchemeRoutingPort {
    fun verifyExternal(iban: String, suppliedName: String): Uni<VopVerification>
}

/** Evidence store: one row per verification (ADR-0171 §6). Inputs are hashed, never plaintext. */
interface VopVerificationRecordPort {
    fun record(
        ibanHash: String,
        suppliedNameHash: String,
        verification: VopVerification,
        requestedBy: String,
    ): Uni<Void>
}
