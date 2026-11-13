// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.out

import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import java.util.UUID

/** Raised when the sanctions service cannot be reached — the gate fails closed (ADR-0032 §C). */
class ScreeningUnavailableException(cause: Throwable) :
    RuntimeException("Sanctions screening is unavailable; conversion held", cause)

/**
 * Outbound port to the sanctions service's synchronous screen
 * (`POST /api/v1/sanctions/screen`). The adapter maps the remote `SanctionsCheck` onto the local
 * [ScreeningResult] and throws [ScreeningUnavailableException] if the service is unreachable so the
 * use-case can hold the conversion rather than settling it un-screened.
 */
interface SanctionsScreeningPort {
    /**
     * Screens every name of [subject] in one call: sanctions-service searches the lists for the
     * primary name and for every alias and reports the worst outcome, under one idempotency key.
     */
    suspend fun screen(subject: ScreeningSubject, role: ScreeningRole, idempotencyKey: String): ScreeningResult
}

/**
 * What kind of subject is screened, in sanctions-service's `EntityType` wire vocabulary. Only the
 * two kinds a bank customer can be; never a default, always derived from the party record.
 */
enum class ScreeningEntityType { INDIVIDUAL, ORGANIZATION }

/**
 * The identity a conversion is screened under: every name the bank holds for the party and what
 * kind of party it is. Built from the party record only — never from anything a caller sent.
 */
data class ScreeningSubject(val names: List<String>, val entityType: ScreeningEntityType) {
    init {
        require(names.isNotEmpty()) { "a screening subject needs at least one name" }
    }

    /** The legal name (a natural person's full name). */
    val primaryName: String get() = names.first()

    /** Further names of the SAME subject, e.g. a trading name beside the legal name. */
    val aliases: List<String> get() = names.drop(1)
}

/**
 * Raised when the converting party's screening identity cannot be established — party-service is
 * unreachable, it holds no such party, the party carries no usable name, or its record does not say
 * whether it is a person or an organisation. Without that there is nothing to screen, so
 * the gate fails closed exactly as it does for [ScreeningUnavailableException]: a conversion whose
 * party could not be named is never "screened clear".
 */
class PartyNameUnavailableException(partyId: UUID, cause: Throwable? = null) :
    RuntimeException("The name of party $partyId is unavailable; conversion held", cause)

/**
 * Outbound port to party-service, the golden source of a party's name. Both screens of a
 * conversion ask it: the workflow because a persisted conversion carries only the party id (and a
 * UUID compared against name lists can never hit), the synchronous `POST /convert` path because
 * the only other name on hand is one the caller typed, and a caller must not choose the name it is
 * screened under.
 *
 * A party is screened under EVERY name it carries: a listed trading name behind a clean legal name
 * must not pass. The record holds a legal name (a natural person's full name is that field) and
 * optionally a trading name; both are returned, the legal name first.
 *
 * @throws PartyNameUnavailableException when no name could be obtained
 */
interface PartyNamePort {
    /**
     * Every non-blank, distinct name of the party and its kind. Never an empty name list: "no
     * name" is the exception.
     */
    suspend fun screeningSubject(partyId: UUID): ScreeningSubject
}

/** Risk grading of an opened AML case, mirrors aml-service `AmlRiskLevel`. */
/** Everything the use-case knows at the conversion boundary to open an AML case. */
data class OpenAmlCaseCommand(
    val idempotencyKey: String,
    val conversionId: UUID,
    val partyId: UUID,
    val accountId: UUID?,
    val customerReference: String,
    val riskLevel: AmlCaseSeverity,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
)

/**
 * Outbound port to the aml-service case store (`POST /api/v1/aml/cases`, idempotent on the
 * Idempotency-Key header). Opening a case is best-effort follow-up signalling — it must never change
 * the screening verdict already rendered.
 */
interface AmlCasePort {
    suspend fun openCase(command: OpenAmlCaseCommand)
}
