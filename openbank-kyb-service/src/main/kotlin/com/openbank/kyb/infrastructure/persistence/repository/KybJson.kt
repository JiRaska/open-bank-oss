// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.Signer
import java.time.Instant
import java.time.LocalDate

/**
 * The persisted JSON shape of an extract and of the signer list. Explicit DTOs rather than
 * serialising the domain types: [LegalEntityIdentifier] has a private constructor (it is only
 * valid through `parse`), and a stored document must survive a domain-class rename.
 */
internal object KybJson {
    val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    data class ExtractDoc(
        val scheme: String,
        val value: String,
        val legalName: String,
        val legalFormCode: String?,
        val legalFormClass: String,
        val status: String,
        val address: AddressDoc?,
        val incorporatedOn: LocalDate?,
        val taxId: String?,
        val representatives: List<RepresentativeDoc>,
        val ruleMode: String,
        val ruleRequired: Int?,
        val ruleText: String?,
        val otherIdentifiers: Map<String, String>,
        val source: String,
        val sourceRef: String?,
        val verification: String,
        val fetchedAt: Instant,
    )

    data class AddressDoc(val line1: String?, val city: String?, val postalCode: String?, val countryCode: String)

    data class RepresentativeDoc(
        val fullName: String,
        val dateOfBirth: LocalDate?,
        val body: String?,
        val role: String?,
        val since: LocalDate?,
    )

    fun write(extract: RegistryExtract): String = mapper.writeValueAsString(
        ExtractDoc(
            scheme = extract.identifier.scheme.name,
            value = extract.identifier.value,
            legalName = extract.legalName,
            legalFormCode = extract.legalFormCode,
            legalFormClass = extract.legalFormClass.name,
            status = extract.status.name,
            address = extract.registeredAddress?.let { AddressDoc(it.line1, it.city, it.postalCode, it.countryCode) },
            incorporatedOn = extract.incorporatedOn,
            taxId = extract.taxId,
            representatives = extract.representatives.map {
                RepresentativeDoc(it.fullName, it.dateOfBirth, it.body, it.role, it.since)
            },
            ruleMode = extract.representationRule.mode.name,
            ruleRequired = extract.representationRule.requiredSigners,
            ruleText = extract.representationRule.sourceText,
            otherIdentifiers = extract.otherIdentifiers.mapKeys { it.key.name },
            source = extract.source,
            sourceRef = extract.sourceRef,
            verification = extract.verification.name,
            fetchedAt = extract.fetchedAt,
        ),
    )

    fun readExtract(json: String): RegistryExtract {
        val d: ExtractDoc = mapper.readValue(json)
        return RegistryExtract(
            identifier = LegalEntityIdentifier.of(IdentifierScheme.valueOf(d.scheme), d.value),
            legalName = d.legalName,
            legalFormCode = d.legalFormCode,
            legalFormClass = com.openbank.kyb.domain.model.LegalFormClass.valueOf(d.legalFormClass),
            status = com.openbank.kyb.domain.model.EntityStatus.valueOf(d.status),
            registeredAddress = d.address?.let {
                com.openbank.kyb.domain.model.RegisteredAddress(it.line1, it.city, it.postalCode, it.countryCode)
            },
            incorporatedOn = d.incorporatedOn,
            taxId = d.taxId,
            representatives = d.representatives.map {
                com.openbank.kyb.domain.model.Representative(it.fullName, it.dateOfBirth, it.body, it.role, it.since)
            },
            representationRule = com.openbank.kyb.domain.model.RepresentationRule(
                com.openbank.kyb.domain.model.RepresentationMode.valueOf(d.ruleMode),
                d.ruleRequired,
                d.ruleText,
            ),
            otherIdentifiers = d.otherIdentifiers.mapKeys { IdentifierScheme.valueOf(it.key) },
            source = d.source,
            sourceRef = d.sourceRef,
            verification = com.openbank.kyb.domain.model.ExtractVerification.valueOf(d.verification),
            fetchedAt = d.fetchedAt,
        )
    }

    fun writeSigners(signers: List<Signer>): String = mapper.writeValueAsString(signers)

    fun readSigners(json: String): List<Signer> = mapper.readValue(json)
}
