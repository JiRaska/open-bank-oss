// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.application.port.`in`

import com.openbank.vop.domain.model.VopVerification
import io.smallrye.mutiny.Uni

/**
 * Verify a payee name against an IBAN (ADR-0171, IPR Art. 5c).
 *
 * @param iban the payee's IBAN, as supplied by the payer.
 * @param payeeName the payee name, as supplied by the payer.
 * @param requestedBy the caller's principal id — recorded on the evidence row.
 */
data class VerifyPayeeCommand(val iban: String, val payeeName: String, val requestedBy: String)

fun interface VerifyPayeeUseCase {
    fun verify(command: VerifyPayeeCommand): Uni<VopVerification>
}
