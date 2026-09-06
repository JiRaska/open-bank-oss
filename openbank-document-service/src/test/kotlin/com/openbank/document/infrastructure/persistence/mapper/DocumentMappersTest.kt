// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.mapper

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Round-trips every entity/domain mapping. These mappers are the only place the JSON columns
 * (`metadata_json`, `signers_json`) are written and read, so a field dropped on either side is
 * invisible to any test that does not go domain -> entity -> domain.
 */
class DocumentMappersTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    private fun template() = DocumentTemplate(
        id = UUID.randomUUID(),
        code = "RAMCOVA_SMLOUVA",
        version = "1.2.0",
        name = "Framework agreement",
        engine = TemplateEngine.HANDLEBARS,
        bodyHtml = "<p>{{party.legalName}}</p>",
        locale = "cs",
        status = TemplateStatus.PUBLISHED,
        productRef = "CURRENT_BASIC",
        classification = "restricted",
        createdAt = now,
        createdBy = "seeder",
    )

    private fun document() = Document(
        id = UUID.randomUUID(),
        templateCode = "RAMCOVA_SMLOUVA",
        templateVersion = "1.2.0",
        sha256 = "a".repeat(64),
        storageKey = "document/rendered/1",
        contentType = "application/pdf",
        sizeBytes = 4096,
        status = DocumentStatus.PENDING_SIGNATURE,
        metadata = mapOf("lang" to "cs", "source" to "onboarding"),
        partyRef = "party-1",
        caseRef = "case-1",
        productRef = "prod-1",
        retainUntil = LocalDate.of(2035, 1, 31),
        createdAt = now,
        idempotencyKey = "onboarding:acc-1",
    )

    private fun ceremony() = SignatureCeremony(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        signers = listOf(
            Signer(partyRef = "p1", order = 1, status = SignerStatus.SIGNED, signedAt = now),
            Signer(partyRef = "p2", order = 2, status = SignerStatus.PENDING, signedAt = null),
        ),
        status = CeremonyStatus.PARTIALLY_SIGNED,
        signatureLevel = SignatureLevel.QUALIFIED,
        createdAt = now,
        version = 7,
    )

    @Test
    fun `template round-trips through the entity with every field preserved`() {
        val original = template()
        assertThat(original.toEntity().toDomain()).isEqualTo(original)
    }

    @Test
    fun `document round-trips including the metadata map serialised into metadata_json`() {
        val original = document()
        val entity = original.toEntity(mapper)

        // The map only exists on the wire as JSON text — assert it actually got serialised, not
        // left at the entity default of "{}".
        assertThat(entity.metadataJson).contains("\"lang\":\"cs\"", "\"source\":\"onboarding\"")
        assertThat(entity.toDomain(mapper)).isEqualTo(original)
    }

    @Test
    fun `an empty metadata map survives the round-trip as an empty map, not null`() {
        val original = document().copy(metadata = emptyMap(), idempotencyKey = null, retainUntil = null)
        val back = original.toEntity(mapper).toDomain(mapper)

        assertThat(back.metadata).isEmpty()
        assertThat(back.idempotencyKey).isNull()
        assertThat(back.retainUntil).isNull()
    }

    @Test
    fun `ceremony round-trips with the ordered signer list and the optimistic-lock version`() {
        val original = ceremony()
        val entity = original.toEntity(mapper)

        // version is the optimistic-lock counter — dropping it would silently disable the
        // stale-write rejection recordDecision relies on.
        assertThat(entity.version).isEqualTo(7)
        val back = entity.toDomain(mapper)
        assertThat(back).isEqualTo(original)
        assertThat(back.signers.map { it.order }).containsExactly(1, 2)
    }

    @Test
    fun `a ceremony with no signers serialises to an empty JSON array and maps back to an empty list`() {
        val original = ceremony().copy(signers = emptyList(), status = CeremonyStatus.DRAFT)
        val entity = original.toEntity(mapper)

        assertThat(entity.signersJson).isEqualTo("[]")
        assertThat(entity.toDomain(mapper).signers).isEmpty()
    }
}
