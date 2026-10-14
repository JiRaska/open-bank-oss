// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.tax

import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.infrastructure.client.AccountServiceClient
import com.openbank.interest.infrastructure.client.PartyAmlProfileClientResponse
import com.openbank.interest.infrastructure.client.PartyDetailClientResponse
import com.openbank.interest.infrastructure.client.PartyServiceClient
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Resolves the withholding-tax profile of an account's interest beneficiary from party-owned
 * facts (ADR-0339 B) — the ADR-0033 §C fast-follow that replaces the constant v1 default:
 *
 * 1. `account-service GET /accounts/{accountId}` → `partyId`.
 * 2. `party-service GET /parties/{partyId}` → `partyType`. **Only `INDIVIDUAL` carries a personal
 *    AML profile** (party-service `requireEligible` answers anything else with 422), so the
 *    taxpayer type is decided here, by the party record, never by the profile:
 *    `INDIVIDUAL` → [TaxpayerType.INDIVIDUAL]; `SOLE_TRADER` / `COMPANY` / `TRUST` →
 *    [TaxpayerType.LEGAL_ENTITY] (interest enters the CIT base gross, §36 — ADR-0033
 *    [com.openbank.interest.domain.tax.WithholdingTreatment.NOT_WITHHELD]).
 * 3. An `INDIVIDUAL` beneficiary additionally reads `GET /parties/{id}/aml-profile` and maps the
 *    declared residency countries: any non-CZ country → [TaxResidency.NON_RESIDENT], CZ-only →
 *    [TaxResidency.RESIDENT]. Only the country codes are consumed — TINs stay in party-service
 *    (GDPR minimization, ADR-0339 A).
 *
 * Inputs the policy supports but the platform does not store yet stay on the statutory-default
 * path (ADR-0339 B): `treatyRate`, `nonCooperatingState` and `exemptCode` are always unset, so a
 * non-resident is withheld at the §36 default 15 % until treaty rates and exemptions become
 * party attributes in their own right.
 *
 * **Fail-safe (ADR-0033 §C): every failure — account/party/profile 404, profile 422, timeout,
 * 5xx — resolves to [TaxProfile.FAIL_SAFE_DEFAULT]** (CZ-resident individual, 15 %). The port
 * contract forbids propagating a resolution failure that would skip withholding: an
 * over-withholding is correctable by the taxpayer, an under-withholding is a bank liability.
 * Every fail-safe resolution is logged and counted so a permanently unavailable party-service
 * is visible instead of silently conservative.
 */
@ApplicationScoped
class PartyTaxProfileProvider @Inject constructor(
    @RestClient private val accountClient: AccountServiceClient,
    @RestClient private val partyClient: PartyServiceClient,
    private val registry: Instance<MeterRegistry>,
) : TaxProfilePort {

    private val log: Logger = Logger.getLogger(PartyTaxProfileProvider::class.java)

    @Timeout(RESOLVE_TIMEOUT_MS)
    override fun resolve(accountId: UUID): Uni<TaxProfile> =
        resolveProfile(accountId).onFailure().recoverWithItem { failure ->
            log.warnf(
                "tax profile for account %s unresolved (%s); failing safe to the " +
                    "CZ-resident-individual default (ADR-0033 §C)",
                accountId,
                failure.message,
            )
            registry.ifResolvable { it.counter(FAIL_SAFE_COUNTER).increment() }
            TaxProfile.FAIL_SAFE_DEFAULT
        }

    private fun resolveProfile(accountId: UUID): Uni<TaxProfile> =
        accountClient.getAccount(accountId).flatMap { account ->
            val partyId = account.partyId
                ?: return@flatMap Uni.createFrom().failure<TaxProfile>(
                    IllegalStateException("account $accountId carries no partyId"),
                )
            partyClient.getParty(partyId).flatMap { party -> profileFor(partyId, party) }
        }

    private fun profileFor(partyId: UUID, party: PartyDetailClientResponse): Uni<TaxProfile> {
        // NOT the AML profile's job: it is person-only and would 422. The party record decides.
        if (!party.partyType.equals(PartyType.INDIVIDUAL.name, ignoreCase = true)) {
            return Uni.createFrom().item(
                TaxProfile(TaxpayerType.LEGAL_ENTITY, TaxResidency.RESIDENT),
            )
        }
        return partyClient.getAmlProfile(partyId).map { profile -> individualProfile(profile) }
    }

    private fun individualProfile(profile: PartyAmlProfileClientResponse): TaxProfile {
        val residency = if (profile.taxResidencies.any { !it.equals(DOMESTIC, ignoreCase = true) }) {
            TaxResidency.NON_RESIDENT
        } else {
            TaxResidency.RESIDENT
        }
        return TaxProfile(TaxpayerType.INDIVIDUAL, residency)
    }

    private fun <T> Instance<T>.ifResolvable(block: (T) -> Unit) {
        if (isResolvable) block(get())
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS: Long = 5000
        const val DOMESTIC = "CZ"

        /** One per fail-safe resolution — alert when it grows: party-service is down and every
         *  account is being withheld at the conservative default. */
        const val FAIL_SAFE_COUNTER = "openbank.interest.tax_profile_resolution.fail_safe.total"

        /** Mirrors party-service `PartyType` — kept as strings-over-the-wire, not an enum, so a
         *  new party type fails the INDIVIDUAL check (→ LEGAL_ENTITY, the no-withholding path)
         *  rather than throwing. */
        enum class PartyType { INDIVIDUAL, }
    }
}
