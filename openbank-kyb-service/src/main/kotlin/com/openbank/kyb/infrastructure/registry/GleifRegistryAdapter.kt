// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.libs.web.SyntheticTaintExternalBoundary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * GLEIF — the Global LEI Index look-up API. Free, no registration, worldwide. Gives legal name,
 * ISO 20275 entity legal form, registered address, entity + registration status and the local
 * business-register id (`registeredAs`) — but NO representatives, so an LEI-only onboarding always
 * lands in manual review for the signer list, while the entity itself is verified.
 * Base URL `https://api.gleif.org/api/v1`.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "gleif")
@SyntheticTaintExternalBoundary("GLEIF is a third-party public register; same boundary as ARES")
interface GleifRestClient {
    @GET
    @Path("/lei-records/{lei}")
    suspend fun record(@PathParam("lei") lei: String): JsonNode
}

@ApplicationScoped
class GleifRegistryAdapter : RegistryAdapter {

    @Inject @RestClient
    lateinit var gleif: GleifRestClient

    @Inject lateinit var clock: Clock

    override val source: String = SOURCE

    override fun supports(scheme: IdentifierScheme): Boolean = scheme == IdentifierScheme.LEI

    @Timeout(GLEIF_TIMEOUT_MS)
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 10_000,
        failOn = [RegistryUnavailableException::class],
    )
    override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? {
        val body = try {
            gleif.record(identifier.value)
        } catch (e: WebApplicationException) {
            if (e.response?.status == NOT_FOUND) return null
            throw RegistryUnavailableException(SOURCE, e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw RegistryUnavailableException(SOURCE, e)
        }
        return map(identifier, body, Instant.now(clock))
    }

    internal fun map(identifier: LegalEntityIdentifier, body: JsonNode, now: Instant): RegistryExtract? {
        val attrs = body.path("data").path("attributes")
        if (attrs.isMissingNode) return null
        val entity = attrs.path("entity")
        val addr = entity.path("legalAddress")
        val country = addr.text("country") ?: "XX"
        val elf = entity.path("legalForm").text("id")
        val registeredAs = entity.text("registeredAs")
        val status = when (entity.text("status")?.uppercase()) {
            "ACTIVE" -> EntityStatus.ACTIVE
            "INACTIVE" -> EntityStatus.DISSOLVED
            else -> EntityStatus.UNKNOWN
        }
        val others = buildMap {
            if (registeredAs != null) {
                nationalScheme(country)?.let { put(it, registeredAs) }
            }
        }
        return RegistryExtract(
            identifier = identifier,
            legalName = entity.path("legalName").text("name").orEmpty(),
            legalFormCode = elf,
            legalFormClass = classify(entity.path("category").text(null) ?: "", elf),
            status = status,
            registeredAddress = RegisteredAddress(
                line1 = addr.path("addressLines").mapNotNull {
                    it.asText().takeIf(String::isNotBlank)
                }.joinToString(", ").ifBlank { null },
                city = addr.text("city"),
                postalCode = addr.text("postalCode"),
                countryCode = country,
            ),
            incorporatedOn = entity.text("creationDate")?.let {
                runCatching { LocalDate.parse(it.take(DATE_LENGTH)) }.getOrNull()
            },
            taxId = null,
            representatives = emptyList(),
            representationRule = RepresentationRule.UNKNOWN,
            otherIdentifiers = others,
            source = SOURCE,
            sourceRef = attrs.text("lei"),
            verification = ExtractVerification.VERIFIED,
            fetchedAt = now,
        )
    }

    private fun classify(category: String, elf: String?): LegalFormClass = when {
        category.equals("SOLE_PROPRIETOR", ignoreCase = true) -> LegalFormClass.SOLE_TRADER
        category.equals("BRANCH", ignoreCase = true) -> LegalFormClass.BRANCH
        category.equals("FUND", ignoreCase = true) -> LegalFormClass.OTHER
        elf != null && elf in CZ_LIMITED_ELF -> LegalFormClass.LIMITED_COMPANY
        elf != null && elf in CZ_JOINT_STOCK_ELF -> LegalFormClass.JOINT_STOCK
        else -> LegalFormClass.OTHER
    }

    private fun nationalScheme(country: String): IdentifierScheme? = IdentifierScheme.entries.firstOrNull {
        it.country == country && it != IdentifierScheme.PL_KRS
    }

    private fun JsonNode.text(field: String?): String? =
        (if (field == null) this else path(field)).takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }

    companion object {
        const val SOURCE = "gleif"
        private const val NOT_FOUND = 404
        private const val GLEIF_TIMEOUT_MS = 4000L
        private const val DATE_LENGTH = 10

        /** ISO 20275 ELF codes for the Czech s.r.o. and a.s.; the ELF list is data — extend per jurisdiction. */
        private val CZ_LIMITED_ELF = setOf("ABZH")
        private val CZ_JOINT_STOCK_ELF = setOf("MVAL")
    }
}
