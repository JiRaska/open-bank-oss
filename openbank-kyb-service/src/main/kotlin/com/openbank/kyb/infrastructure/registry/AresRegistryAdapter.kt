// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser
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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * ARES — Administrativní registr ekonomických subjektů (Ministry of Finance CZ). Free, public, no
 * key. Two views are read: the economic-subject record (name, legal form, seat, VAT id, status)
 * and the public-register (*veřejný rejstřík*) record, which carries the statutory body, its
 * members and the *způsob jednání*. Base URL `https://ares.gov.cz/ekonomicke-subjekty-v-be/rest`.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "ares")
@SyntheticTaintExternalBoundary("ARES is a third-party public register; taint must not leave the platform")
interface AresRestClient {
    @GET
    @Path("/ekonomicke-subjekty/{ico}")
    suspend fun economicSubject(@PathParam("ico") ico: String): JsonNode

    @GET
    @Path("/ekonomicke-subjekty-vr/{ico}")
    suspend fun publicRegister(@PathParam("ico") ico: String): JsonNode
}

@ApplicationScoped
// One private mapper per ARES sub-structure (status, seat, representatives, representation rule,
// file reference) plus the two JsonNode readers. Folding them back into map() is exactly the
// 69-line, complexity-22 function they were split out of.
@Suppress("TooManyFunctions")
class AresRegistryAdapter : RegistryAdapter {

    @Inject @RestClient
    lateinit var ares: AresRestClient

    @Inject lateinit var clock: Clock

    @Inject lateinit var packs: CountryPackRegistry

    private val log = Logger.getLogger(AresRegistryAdapter::class.java)

    override val source: String = SOURCE

    override fun supports(scheme: IdentifierScheme): Boolean = scheme == IdentifierScheme.CZ_ICO

    @Timeout(ARES_TIMEOUT_MS)
    @Retry(maxRetries = 1, delay = 300, retryOn = [RegistryUnavailableException::class])
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 10_000,
        failOn = [RegistryUnavailableException::class],
    )
    override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? {
        val subject = call { ares.economicSubject(identifier.value) } ?: return null
        val now = Instant.now(clock)
        val pack =
            requireNotNull(packs.packFor("CZ", java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC))) {
                "no effective CZ country pack"
            }
        val soleTrader = pack.isSoleTrader(subject.text("pravniForma"))
        // A sole trader is not in the public register (only in the trade-licence register), so
        // the VR call is skipped rather than answered 404 and treated as an outage.
        val vr = if (soleTrader) null else call { ares.publicRegister(identifier.value) }
        return map(identifier, subject, vr, now, pack)
    }

    /** 404 → null (unknown IČO); any other failure → [RegistryUnavailableException]. */
    private suspend fun call(block: suspend () -> JsonNode): JsonNode? = try {
        block()
    } catch (e: WebApplicationException) {
        if (e.response?.status == NOT_FOUND) {
            null
        } else {
            log.warnf("ARES answered %s", e.response?.status)
            throw RegistryUnavailableException(SOURCE, e)
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
        subject: JsonNode,
        vr: JsonNode?,
        now: Instant,
        pack: CountryPack,
    ): RegistryExtract {
        val legalFormCode = subject.text("pravniForma")
        val legalFormClass = pack.classify(legalFormCode)
        val record = currentRecord(vr)
        return RegistryExtract(
            identifier = identifier,
            legalName = subject.text("obchodniJmeno") ?: currentValue(record?.path("obchodniJmeno")).orEmpty(),
            legalFormCode = legalFormCode,
            legalFormClass = legalFormClass,
            status = statusOf(subject, record),
            registeredAddress = addressOf(subject),
            incorporatedOn = subject.date("datumVzniku"),
            taxId = subject.text("dic"),
            representatives = representativesOf(record, legalFormClass, subject),
            representationRule = ruleOf(legalFormClass, record),
            source = SOURCE,
            sourceRef = fileReference(record),
            verification = ExtractVerification.VERIFIED,
            fetchedAt = now,
        )
    }

    /** The primary public-register record, or the first one when none is flagged primary. */
    private fun currentRecord(vr: JsonNode?): JsonNode? =
        vr?.path("zaznamy")?.firstOrNull { it.path("primarniZaznam").asBoolean(true) }
            ?: vr?.path("zaznamy")?.firstOrNull()

    private fun statusOf(subject: JsonNode, record: JsonNode?): EntityStatus = when {
        subject.text("datumZaniku") != null -> EntityStatus.DISSOLVED
        record == null -> EntityStatus.ACTIVE
        record.text("datumVymazu") != null -> EntityStatus.DISSOLVED
        !record.path("insolvence").isEmpty -> EntityStatus.INSOLVENT
        record.text("stavSubjektu")?.contains("likvidac", ignoreCase = true) == true -> EntityStatus.IN_LIQUIDATION
        else -> EntityStatus.ACTIVE
    }

    private fun addressOf(subject: JsonNode): RegisteredAddress? {
        val seat = subject.path("sidlo")
        if (seat.isMissingNode || seat.isNull) return null
        return RegisteredAddress(
            line1 = seat.text("textovaAdresa")
                ?: listOfNotNull(seat.text("nazevUlice"), seat.text("cisloDomovni")).joinToString(" ").ifBlank { null },
            city = seat.text("nazevObce"),
            postalCode = seat.text("psc"),
            countryCode = seat.text("kodStatu") ?: "CZ",
        )
    }

    /**
     * A sole trader has no statutory body: the register lists the person as the subject itself, so
     * they ARE the single representative — which is what makes the SOLE rule true here rather than
     * assumed.
     */
    private fun representativesOf(
        record: JsonNode?,
        legalFormClass: LegalFormClass,
        subject: JsonNode,
    ): List<Representative> {
        val listed = record?.let { representatives(it) }.orEmpty()
        if (legalFormClass != LegalFormClass.SOLE_TRADER || listed.isNotEmpty()) return listed
        return listOf(
            Representative(
                subject.text("obchodniJmeno").orEmpty(),
                null,
                null,
                "podnikatel",
                subject.date("datumVzniku"),
            ),
        )
    }

    private fun ruleOf(legalFormClass: LegalFormClass, record: JsonNode?): RepresentationRule = when {
        legalFormClass == LegalFormClass.SOLE_TRADER -> RepresentationRule.SOLE
        record == null -> RepresentationRule.UNKNOWN
        else -> CzechRepresentationRuleParser.parse(representationText(record))
    }

    /** Court file reference ("Městský soud v Praze C 12345") — the human-checkable pointer at the source. */
    private fun fileReference(record: JsonNode?): String? = record?.path("spisovaZnacka")?.firstOrNull()?.let {
        listOfNotNull(it.text("soud"), it.text("oddil"), it.text("vlozka")).joinToString(" ").trim().ifEmpty { null }
    }

    /** Members of every CURRENT statutory body: an entry with a `datumVymazu` or an ended membership is history. */
    private fun representatives(record: JsonNode): List<Representative> = record.path("statutarniOrgany")
        .filter { it.text("datumVymazu") == null }
        .flatMap { body ->
            val bodyName = body.text("nazevOrganu") ?: body.text("typOrganu")
            body.path("clenoveOrganu")
                .filter {
                    it.text("datumVymazu") == null &&
                        it.path("clenstvi").path("clenstvi").text("zanikClenstvi") == null
                }
                .mapNotNull { member ->
                    val person = member.path("fyzickaOsoba")
                    if (person.isMissingNode || person.isNull) return@mapNotNull null
                    val name = listOfNotNull(
                        person.text("titulPredJmenem"),
                        person.text("jmeno"),
                        person.text("prijmeni"),
                    )
                        .joinToString(" ").trim()
                    if (name.isEmpty()) return@mapNotNull null
                    Representative(
                        fullName = name,
                        dateOfBirth = person.date("datumNarozeni"),
                        body = bodyName,
                        role = member.path("clenstvi").path("funkce").text("nazev") ?: member.text("nazevAngazma"),
                        since =
                        member.path(
                            "clenstvi",
                        ).path("clenstvi").date("vznikClenstvi") ?: member.date("datumZapisu"),
                    )
                }
        }

    private fun representationText(record: JsonNode): String? = record.path("statutarniOrgany")
        .filter { it.text("datumVymazu") == null }
        .firstNotNullOfOrNull { currentValue(it.path("zpusobJednani")) }

    /** ARES VR history lists: the entry without `datumVymazu` is the current one. */
    private fun currentValue(history: JsonNode?): String? = history
        ?.filter { it.text("datumVymazu") == null }
        ?.firstNotNullOfOrNull { it.text("hodnota") }

    private fun JsonNode.text(field: String): String? = path(field).takeIf {
        it.isTextual
    }?.asText()?.trim()?.ifEmpty { null }

    private fun JsonNode.date(field: String): LocalDate? = text(field)?.let {
        runCatching { LocalDate.parse(it.take(DATE_LENGTH)) }.getOrNull()
    }

    companion object {
        const val SOURCE = "ares"
        private const val NOT_FOUND = 404
        private const val ARES_TIMEOUT_MS = 4000L
        private const val DATE_LENGTH = 10
    }
}
