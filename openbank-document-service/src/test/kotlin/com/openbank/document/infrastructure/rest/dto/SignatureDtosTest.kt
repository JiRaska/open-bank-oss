// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [OpenCeremonyRequest.requireSignerPartyRefs] exists because Jackson's Kotlin module null-checks
 * constructor PARAMETERS but not collection ELEMENTS — `{"signerPartyRefs": [null]}` deserialises
 * happily. The deserialisation half is asserted here too, since without it the guard would read as
 * dead code.
 */
class SignatureDtosTest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val documentId: UUID = UUID.randomUUID()

    @Test
    fun `Jackson really does let a null element through into the list`() {
        val json = """{"documentId":"$documentId","signerPartyRefs":["p1",null]}"""

        val request = mapper.readValue(json, OpenCeremonyRequest::class.java)

        assertThat(request.signerPartyRefs).containsExactly("p1", null)
        assertThat(request.signatureLevel).isEqualTo(SignatureLevel.ADVANCED)
    }

    @Test
    fun `a null element is rejected, naming its index`() {
        val request = OpenCeremonyRequest(documentId, listOf("p1", null, "p3"))

        assertThatThrownBy { request.requireSignerPartyRefs() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("signerPartyRefs[1] must not be null")
    }

    @Test
    fun `an all-present list passes through unchanged as a non-null list`() {
        val request = OpenCeremonyRequest(documentId, listOf("p1", "p2"))

        assertThat(request.requireSignerPartyRefs()).containsExactly("p1", "p2")
    }

    @Test
    fun `an empty list is accepted here — emptiness is the ceremony aggregate's rule, not the DTO's`() {
        assertThat(OpenCeremonyRequest(documentId, emptyList()).requireSignerPartyRefs()).isEmpty()
    }

    @Test
    fun `toResponse carries every ceremony field, signer list included`() {
        val now = Instant.now()
        val ceremony = SignatureCeremony(
            id = UUID.randomUUID(),
            documentId = documentId,
            signers = listOf(Signer("p1", 1, SignerStatus.SIGNED, now)),
            status = CeremonyStatus.COMPLETED,
            signatureLevel = SignatureLevel.QUALIFIED,
            createdAt = now,
            version = 3,
        )

        val response = ceremony.toResponse()

        assertThat(response.id).isEqualTo(ceremony.id)
        assertThat(response.documentId).isEqualTo(documentId)
        assertThat(response.status).isEqualTo(CeremonyStatus.COMPLETED)
        assertThat(response.signatureLevel).isEqualTo(SignatureLevel.QUALIFIED)
        assertThat(response.signers).isEqualTo(ceremony.signers)
        assertThat(response.createdAt).isEqualTo(now)
    }

    @Test
    fun `RecordDecisionRequest defaults evidenceRef to null — required only for a SIGNED decision`() {
        val declined = RecordDecisionRequest(partyRef = "p1", decision = SignerStatus.DECLINED)

        assertThat(declined.evidenceRef).isNull()
    }
}
