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
     * [aliases] are further names of the SAME subject (a trading name beside the legal name).
     * sanctions-service searches the lists for the name and for every alias and reports the worst
     * outcome, so one call screens them all under one idempotency key.
     */
    suspend fun screen(
        name: String,
        role: ScreeningRole,
        idempotencyKey: String,
        aliases: List<String> = emptyList(),
    ): ScreeningResult
}

/**
 * Raised when the converting party's names cannot be established — party-service is unreachable, it
 * holds no such party, or the party carries no usable name. Without a name there is nothing to screen, so
 * the gate fails closed exactly as it does for [ScreeningUnavailableException]: a conversion whose
 * party could not be named is never "screened clear".
 */
class PartyNameUnavailableException(partyId: UUID, cause: Throwable? = null) :
    RuntimeException("The name of party $partyId is unavailable; conversion held", cause)

/**
 * Outbound port to party-service, the golden source of a party's name. A persisted conversion
 * carries only the party id, so a screen that runs after the request is gone (the conversion
 * workflow) has to ask for the name; screening the id itself compares a UUID against name lists
 * and can never hit.
 *
 * A party is screened under EVERY name it carries: a listed trading name behind a clean legal name
 * must not pass. The record holds a legal name (a natural person's full name is that field) and
 * optionally a trading name; both are returned, the legal name first.
 *
 * @throws PartyNameUnavailableException when no name could be obtained
 */
interface PartyNamePort {
    /** Every non-blank, distinct name of the party. Never empty: "no name" is the exception. */
    suspend fun screeningNames(partyId: UUID): List<String>
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
