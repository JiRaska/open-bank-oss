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
    val signerPartyRefs: List<String>,
    val signatureLevel: SignatureLevel = SignatureLevel.ADVANCED,
)

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
