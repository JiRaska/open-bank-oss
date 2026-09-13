// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.application.port.out.UboAdapter
import com.openbank.kyb.domain.model.BeneficialOwner
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Optional

/**
 * The UK Register of People with Significant Control (ADR-0284 D5), served by the same Companies
 * House API as the company profile — see [CompaniesHouseRegistryAdapter] for the key handling this
 * shares.
 *
 * Two things about the PSC register decide the shape here. It publishes **bands**, never a
 * percentage: `ownership-of-shares-25-to-50-percent` is the finest figure that exists, so
 * [OwnershipBand] is a band too — deriving `37.5%` would invent a precision no register issued and
 * then let a threshold test compare against the invention. And it publishes **statements** as well
 * as people: "no individual PSC identified" is an answer the company filed, not an absence, and an
 * analyst has to see it, so it is carried separately from the (then empty) owner list.
 */
@ApplicationScoped
class CompaniesHousePscAdapter : UboAdapter {

    @Inject @RestClient
    lateinit var companiesHouse: CompaniesHouseRestClient

    @Inject lateinit var clock: Clock

    @Inject
    @ConfigProperty(name = "openbank.kyb.companies-house.api-key")
    lateinit var apiKey: Optional<String>

    private val log = Logger.getLogger(CompaniesHousePscAdapter::class.java)

    override val source: String = SOURCE

    private val key: String? get() = apiKey.orElse(null)?.trim()?.ifEmpty { null }

    /** Same rule as the company adapter: an adapter with no key must not claim a scheme it cannot serve. */
    override fun supports(scheme: IdentifierScheme): Boolean = scheme == IdentifierScheme.GB_CRN && key != null

    @Timeout(PSC_TIMEOUT_MS)
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 10_000,
        failOn = [RegistryUnavailableException::class],
    )
    override suspend fun lookup(identifier: LegalEntityIdentifier, pack: CountryPack): UboFinding? {
        val auth = "Basic " + Base64.getEncoder().encodeToString("${key ?: return null}:".toByteArray())
        val body = try {
            companiesHouse.personsWithSignificantControl(identifier.value, auth, PSC_PAGE)
        } catch (e: jakarta.ws.rs.WebApplicationException) {
            // 404 here means the COMPANY has no PSC resource, which for a live company is itself a
            // finding: the register answered, and it holds nothing. It is not an outage, and it is
            // not "we did not look" — both of which would send an analyst chasing the wrong thing.
            if (e.response?.status == NOT_FOUND) return empty(identifier, pack)
            log.warnf("Companies House PSC answered %s", e.response?.status)
            throw RegistryUnavailableException(SOURCE, e)
        } catch (e: RegistryUnavailableException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw RegistryUnavailableException(SOURCE, e)
        }
        return map(identifier, body, pack)
    }

    internal fun map(identifier: LegalEntityIdentifier, body: JsonNode, pack: CountryPack): UboFinding {
        val items = body.path("items").toList()
        val owners = items
            .filter { it.text("ceased_on") == null && it.text("kind")?.contains("statement") != true }
            .mapNotNull { toOwner(it) }
        // A statement item ("no individual or entity with significant control identified") is an
        // answer the company filed under s.790 — carried as text so the analyst reads the register's
        // own words rather than our summary of them.
        val statements = items.mapNotNull { item ->
            item.text("statement") ?: item.text("kind")?.takeIf { it.contains("statement") }
        }
        return UboFinding(
            identifier = identifier,
            source = UboSource.REGISTER,
            owners = owners,
            registerStatements = statements,
            threshold = pack.uboRegister.threshold,
            registerName = pack.uboRegister.name,
            sourceRef = identifier.value,
            fetchedAt = Instant.now(clock),
        )
    }

    private fun empty(identifier: LegalEntityIdentifier, pack: CountryPack) = UboFinding(
        identifier = identifier,
        source = UboSource.REGISTER,
        owners = emptyList(),
        registerStatements = emptyList(),
        threshold = pack.uboRegister.threshold,
        registerName = pack.uboRegister.name,
        sourceRef = identifier.value,
        fetchedAt = Instant.now(clock),
    )

    private fun toOwner(item: JsonNode): BeneficialOwner? {
        val name = item.text("name") ?: return null
        val natures = item.path("natures_of_control").mapNotNull { it.takeIf { n -> n.isTextual }?.asText() }
        return BeneficialOwner(
            fullName = name,
            // The PSC register publishes month and year only. A reconstructed day would be a fact
            // nobody filed, so the field stays null and identity matching uses the other columns.
            dateOfBirth = null,
            nationality = item.text("nationality"),
            countryOfResidence = item.text("country_of_residence"),
            band = bandOf(natures),
            natureOfControl = natures,
            notifiedOn = item.date("notified_on"),
            corporate = item.text("kind")?.let { it.startsWith("corporate") || it.startsWith("legal-person") } == true,
        )
    }

    private fun bandOf(natures: List<String>): OwnershipBand =
        natures.map(::bandOfOne).maxByOrNull(::rank) ?: OwnershipBand.BELOW_THRESHOLD

    private fun bandOfOne(nature: String): OwnershipBand = when {
        nature.contains("75-to-100-percent") -> OwnershipBand.PCT_75_TO_100
        nature.contains("50-to-75-percent") -> OwnershipBand.PCT_50_TO_75
        nature.contains("25-to-50-percent") -> OwnershipBand.PCT_25_TO_50
        // Right to appoint directors, significant influence: named by the register without a
        // figure. NOT below-threshold — the register named them for a reason.
        else -> OwnershipBand.UNQUANTIFIED
    }

    /**
     * Ordering for "the strongest control this person holds". A person can hold shares in one band
     * and voting rights in another, and the threshold test is about the higher one. UNQUANTIFIED
     * ranks above nothing and below every stated band: an explicit 25-50% is more informative than
     * "influence", never less, so it must not be beaten by it.
     */
    private fun rank(band: OwnershipBand): Int = BAND_RANK.getValue(band)

    private fun JsonNode.text(field: String): String? = path(field).takeIf {
        it.isTextual
    }?.asText()?.trim()?.ifEmpty { null }

    private fun JsonNode.date(field: String): LocalDate? = text(field)?.let {
        runCatching { LocalDate.parse(it.take(DATE_LENGTH)) }.getOrNull()
    }

    companion object {
        const val SOURCE = "companies-house-psc"
        private const val NOT_FOUND = 404
        private const val PSC_TIMEOUT_MS = 4000L
        private const val DATE_LENGTH = 10
        private const val PSC_PAGE = 100

        /**
         * Ordering for "the strongest control this person holds". A person can hold shares in one
         * band and voting rights in another, and the threshold test is about the higher one.
         * UNQUANTIFIED ranks above nothing and below every stated band: an explicit 25-50% is more
         * informative than "influence", never less, so it must not be beaten by it.
         */
        private val BAND_RANK = mapOf(
            OwnershipBand.BELOW_THRESHOLD to 0,
            OwnershipBand.UNQUANTIFIED to 1,
            OwnershipBand.PCT_25_TO_50 to 2,
            OwnershipBand.PCT_50_TO_75 to 3,
            OwnershipBand.PCT_75_TO_100 to 4,
        )
    }
}
