// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest.dto

import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import java.time.Instant
import java.util.UUID

data class OpenCeremonyRequest(
    val documentId: UUID,
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of
     * a collection, so `{"signerPartyRefs": [null]}` deserialises happily into a `List<String>`
     * holding a null. Writing the type honestly is what makes [requireSignerPartyRefs] reachable
     * instead of dead code. The guard lives here, on the wire boundary, so the command stays
     * non-nullable.
     */
    val signerPartyRefs: List<String?>,
    val signatureLevel: SignatureLevel = SignatureLevel.ADVANCED,
) {
    /**
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`; no
     * service-local mapper is added (#526).
     */
    fun requireSignerPartyRefs(): List<String> = signerPartyRefs.mapIndexed { index, ref ->
        requireNotNull(ref) { "signerPartyRefs[$index] must not be null" }
    }
}

data class RecordDecisionRequest(
    val partyRef: String,
    val decision: SignerStatus,
    /**
     * SCA challenge/approval reference (ADR-0021) proving [partyRef]'s identity/consent for this
     * decision. Required and verified when [decision] is SIGNED; not required for DECLINED.
     */
    val evidenceRef: String? = null,
)

data class CeremonyResponse(
    val id: UUID,
    val documentId: UUID,
    val signers: List<Signer>,
    val status: CeremonyStatus,
    val signatureLevel: SignatureLevel,
    val createdAt: Instant,
)

fun SignatureCeremony.toResponse() = CeremonyResponse(
    id = id,
    documentId = documentId,
    signers = signers,
    status = status,
    signatureLevel = signatureLevel,
    createdAt = createdAt,
)
