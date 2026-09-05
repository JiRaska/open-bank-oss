// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.libs.web.SyntheticTaintExternalBoundary
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional

/**
 * Companies House (UK). Free of charge, but unlike ARES it needs a registered API key, sent as the
 * HTTP Basic *username* with an empty password. Two views are read: the company profile (name,
 * type, status, incorporation date, registered office) and the officers list, which is what makes
 * a UK signer list possible at all.
 *
 * Base URL `https://api.company-information.service.gov.uk`.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "companies-house")
@SyntheticTaintExternalBoundary("Companies House is a third-party public register; same boundary as ARES")
interface CompaniesHouseRestClient {
    @GET
    @Path("/company/{number}")
    suspend fun company(
        @PathParam("number") number: String,
        @HeaderParam("Authorization") authorization: String,
    ): JsonNode

    @GET
    @Path("/company/{number}/officers")
    suspend fun officers(
        @PathParam("number") number: String,
        @HeaderParam("Authorization") authorization: String,
        @QueryParam("items_per_page") itemsPerPage: Int,
    ): JsonNode
}

/**
 * The UK half of ADR-0284 D2, and the reason the country pack is an abstraction rather than a
 * Czech special case: the register answers a *different shape*. There is no `způsob jednání` — UK
 * law puts the power to bind the company in the articles, not in the register — so
 * [RepresentationRule] cannot be read off the extract at all. It is derived from the legal form
 * under the model articles, and the pack says so in `representationRuleParser: null`, which is
 * what makes the absence a declared fact rather than a missing field.
 *
 * `@Startup`: the "no API key configured" warning below is a boot-time gate. An `@ApplicationScoped`
 * bean is created lazily on first use, so without this the warning would first appear on the first
 * GB lookup — which for a bank with no UK customers yet is never, and the fallback to manual
 * attestation would look like a decision instead of a missing key.
 */
@Startup
@ApplicationScoped
// One private mapper per profile sub-structure (status, address, officers, rule) plus the auth
// header and the three JsonNode readers. Folding them back into map() reproduces exactly the
// oversized function they were split out of — the same reason AresRegistryAdapter carries this.
@Suppress("TooManyFunctions")
class CompaniesHouseRegistryAdapter : RegistryAdapter {

    @Inject @RestClient
    lateinit var companiesHouse: CompaniesHouseRestClient

    @Inject lateinit var clock: Clock

    @Inject lateinit var packs: CountryPackRegistry

    /**
     * Optional on purpose: with no key this adapter declines every scheme and [RegistryRouter]
     * falls through to manual attestation. `Optional<String>` rather than a `defaultValue`, because
     * an empty default is NOT a way to make a property optional (SRCFG00014) and a blank key would
     * be indistinguishable from a real one at the call site.
     */
    @Inject
    @ConfigProperty(name = "openbank.kyb.companies-house.api-key")
    lateinit var apiKey: Optional<String>

    private val log = Logger.getLogger(CompaniesHouseRegistryAdapter::class.java)

    override val source: String = SOURCE

    private val key: String? get() = apiKey.orElse(null)?.trim()?.ifEmpty { null }

    @jakarta.annotation.PostConstruct
    fun announce() {
        if (key == null) {
            log.warn(
                "openbank.kyb.companies-house.api-key is not set: GB company numbers cannot be verified " +
                    "against Companies House and every GB case will fall back to manual attestation",
            )
        }
    }

    /**
     * A scheme this adapter cannot actually serve must not be claimed. Declining when the key is
     * absent is what routes the case to manual attestation instead of failing the lookup — the
     * difference between "we could not check" and "the register said no".
     */
    override fun supports(scheme: IdentifierScheme): Boolean = scheme == IdentifierScheme.GB_CRN && key != null

    @Timeout(CH_TIMEOUT_MS)
    @Retry(maxRetries = 1, delay = 300, retryOn = [RegistryUnavailableException::class])
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 10_000,
        failOn = [RegistryUnavailableException::class],
    )
    override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? {
        val auth = basic(key ?: throw RegistryUnavailableException("$SOURCE: no api key"))
        val profile = call { companiesHouse.company(identifier.value, auth) } ?: return null
        val now = Instant.now(clock)
        val pack = requireNotNull(packs.packFor("GB", LocalDate.ofInstant(now, ZoneOffset.UTC))) {
            "no effective GB country pack"
        }
        // A 404 on the officers list is not "no such company" — the company profile already
        // answered — so it is mapped to an EMPTY list, which lands the case in manual review for
        // the signer list rather than reporting the company as unknown.
        val officers = call { companiesHouse.officers(identifier.value, auth, OFFICER_PAGE) }
        return map(identifier, profile, officers, now, pack)
    }

    private suspend fun call(block: suspend () -> JsonNode): JsonNode? = try {
        block()
    } catch (e: WebApplicationException) {
        when (e.response?.status) {
            NOT_FOUND -> null
            UNAUTHORIZED -> {
                log.warn("Companies House rejected the API key (401)")
                throw RegistryUnavailableException(SOURCE, e)
            }
            else -> {
                log.warnf("Companies House answered %s", e.response?.status)
                throw RegistryUnavailableException(SOURCE, e)
            }
        }
    } catch (e: RegistryUnavailableException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        throw RegistryUnavailableException(SOURCE, e)
    }

    internal fun map(
        identifier: LegalEntityIdentifier,
        profile: JsonNode,
        officers: JsonNode?,
        now: Instant,
        pack: CountryPack,
    ): RegistryExtract {
        val type = profile.text("type")
        val legalFormClass = pack.classify(type)
        val representatives = officersOf(officers)
        return RegistryExtract(
            identifier = identifier,
            legalName = profile.text("company_name").orEmpty(),
            legalFormCode = type,
            legalFormClass = legalFormClass,
            status = statusOf(profile),
            registeredAddress = addressOf(profile),
            incorporatedOn = profile.date("date_of_creation"),
            taxId = null,
            representatives = representatives,
            representationRule = ruleOf(legalFormClass, representatives),
            source = SOURCE,
            sourceRef = identifier.value,
            verification = ExtractVerification.VERIFIED,
            fetchedAt = now,
        )
    }

    private fun statusOf(profile: JsonNode): EntityStatus = when (profile.text("company_status")) {
        "active", "open" -> EntityStatus.ACTIVE
        "liquidation", "receivership", "voluntary-arrangement" -> EntityStatus.IN_LIQUIDATION
        "administration", "insolvency-proceedings" -> EntityStatus.INSOLVENT
        "dissolved", "closed", "converted-closed", "removed" -> EntityStatus.DISSOLVED
        else -> EntityStatus.ACTIVE
    }

    private fun addressOf(profile: JsonNode): RegisteredAddress? {
        val office = profile.path("registered_office_address")
        if (office.isMissingNode || office.isNull) return null
        return RegisteredAddress(
            line1 = listOfNotNull(office.text("address_line_1"), office.text("address_line_2"))
                .joinToString(", ").ifBlank { null },
            city = office.text("locality"),
            postalCode = office.text("postal_code"),
            countryCode = "GB",
        )
    }

    /**
     * Current appointments only, and only the roles that can bind the company. A secretary is an
     * officer and cannot; including one would offer a signer the contract is invalid without.
     */
    private fun officersOf(officers: JsonNode?): List<Representative> = officers?.path("items").orEmpty()
        .filter { it.text("resigned_on") == null }
        .filter { it.text("officer_role") in BINDING_ROLES }
        .mapNotNull { officer ->
            val name = officer.text("name") ?: return@mapNotNull null
            Representative(
                fullName = name,
                // Companies House publishes month and year only — never a full date of birth.
                // Nothing here reconstructs one: the field stays null and identity matching
                // uses the name plus the person's own verified onboarding.
                dateOfBirth = null,
                body = "board",
                role = officer.text("officer_role"),
                since = officer.date("appointed_on"),
            )
        }

    /**
     * The UK register carries no rule to parse. Under the model articles any single director may
     * bind the company, so a company with directors is SOLE; a company whose officers we could not
     * read is UNKNOWN, which routes to manual review rather than assuming one signature is enough.
     */
    private fun ruleOf(legalFormClass: LegalFormClass, representatives: List<Representative>): RepresentationRule =
        when {
            legalFormClass == LegalFormClass.SOLE_TRADER -> RepresentationRule.SOLE
            representatives.isEmpty() -> RepresentationRule.UNKNOWN
            else -> RepresentationRule.SOLE
        }

    private fun basic(apiKey: String): String = "Basic " + Base64.getEncoder().encodeToString("$apiKey:".toByteArray())

    private fun JsonNode.text(field: String): String? = path(field).takeIf {
        it.isTextual
    }?.asText()?.trim()?.ifEmpty { null }

    private fun JsonNode.date(field: String): LocalDate? = text(field)?.let {
        runCatching { LocalDate.parse(it.take(DATE_LENGTH)) }.getOrNull()
    }

    private fun JsonNode?.orEmpty(): List<JsonNode> = this?.toList().orEmpty()

    companion object {
        const val SOURCE = "companies-house"
        private const val NOT_FOUND = 404
        private const val UNAUTHORIZED = 401
        private const val CH_TIMEOUT_MS = 4000L
        private const val DATE_LENGTH = 10
        private const val OFFICER_PAGE = 100

        /** Roles that can bind the company. A secretary cannot; an LLP member can. */
        private val BINDING_ROLES = setOf(
            "director",
            "corporate-director",
            "llp-member",
            "llp-designated-member",
            "corporate-llp-member",
            "corporate-llp-designated-member",
            "member-of-a-management-organ",
            "general-partner-in-a-limited-partnership",
        )
    }
}
