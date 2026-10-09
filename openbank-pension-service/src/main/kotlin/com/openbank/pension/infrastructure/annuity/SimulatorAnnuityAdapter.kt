// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity

import com.openbank.pension.application.annuity.AnnuityApplication
import com.openbank.pension.application.annuity.AnnuityProviderAdapter
import com.openbank.pension.application.annuity.AnnuityQuoteRequest
import com.openbank.pension.application.annuity.PartnerCancellationReason
import com.openbank.pension.application.annuity.PartnerPolicyState
import com.openbank.pension.application.annuity.PartnerPolicyStatus
import com.openbank.pension.domain.annuity.AnnuityOffer
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.ApprovedPartner
import com.openbank.pension.domain.exit.ExitMoney
import io.quarkus.arc.profile.IfBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.ConcurrentHashMap

/**
 * REFERENCE SIMULATOR partner (#12383) — ILLUSTRATIVE, NOT AN INSURER PRICE. Exists only in dev
 * and test builds (`@IfBuildProfile`), so a production registry entry naming `simulator` has no
 * adapter and cannot be activated.
 *
 * Pricing is deterministic and deliberately MORTALITY-TABLE-FREE: an assumed payout horizon of
 * `horizonAge - age` years (at least [MIN_MONTHS] months), divided into the premium net of the
 * one-off fee, times a partner `yieldFactor`, adjusted per annuity type. Two registry entries with
 * different settings behave as two different partners — which is what the comparison needs.
 *
 * Settings (all optional, registry `adapterSettings`): `yieldFactor` (1.10), `horizonAge` (100),
 * `oneOffFeeRate` (0.01), `annualFeeRate` (0.005), `indexationRate` (0.02), `offerValidityHours`
 * (72), `refusePolicy` ("true" = refuse after the premium arrives, returning it).
 */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class SimulatorAnnuityAdapter(private val clock: Clock) : AnnuityProviderAdapter {

    override val kind: String = KIND

    private data class Application(
        val partnerId: String,
        val ref: String,
        val monthly: BigDecimal,
        var state: PartnerPolicyState,
    )

    private val applications = ConcurrentHashMap<String, Application>()

    override suspend fun quote(provider: ApprovedPartner, request: AnnuityQuoteRequest): List<AnnuityOffer> {
        val s = provider.terms.adapterSettings
        val age = Period.between(request.birthDate, request.startDate).years
        val horizonMonths = maxOf(MIN_MONTHS, (s.int("horizonAge", DEFAULT_HORIZON_AGE) - age) * MONTHS_PER_YEAR)
        val fee = ExitMoney.round(request.premium.multiply(s.dec("oneOffFeeRate", "0.01")))
        val invested = request.premium - fee
        val yieldFactor = s.dec("yieldFactor", "1.10")
        val validUntil = clock.instant().plus(
            Duration.ofHours(s.int("offerValidityHours", DEFAULT_VALIDITY_HOURS).toLong()),
        )
        return request.types.intersect(provider.terms.supportedTypes).sortedBy { it.ordinal }.map { type ->
            val months = if (type ==
                AnnuityType.FIXED_TERM
            ) {
                request.termMonths ?: DEFAULT_TERM_MONTHS
            } else {
                horizonMonths
            }
            val adjustment = when (type) {
                AnnuityType.LIFELONG, AnnuityType.FIXED_TERM -> BigDecimal.ONE
                AnnuityType.GUARANTEE_PERIOD -> BigDecimal("0.97")
                AnnuityType.JOINT_LIFE -> BigDecimal.ONE.divide(
                    BigDecimal.ONE + (request.survivorShare ?: DEFAULT_SURVIVOR).multiply(BigDecimal("0.25")),
                    MathContext.DECIMAL64,
                )
                AnnuityType.INDEXED ->
                    BigDecimal.ONE -
                        s.dec("indexationRate", "0.02").multiply(BigDecimal(INDEX_DISCOUNT_YEARS))
            }
            val monthly = ExitMoney.round(
                invested.divide(BigDecimal(months), MathContext.DECIMAL64).multiply(yieldFactor).multiply(adjustment),
            )
            AnnuityOffer(
                offerId = "SIM-${provider.partnerId}-${type.name}-${request.requestId.takeLast(OFFER_SUFFIX)}",
                partnerId = provider.partnerId,
                partnerName = provider.terms.legalName,
                type = type,
                premium = request.premium,
                currency = request.currency,
                monthlyAmount = monthly,
                guaranteeMonths = if (type ==
                    AnnuityType.GUARANTEE_PERIOD
                ) {
                    request.guaranteeMonths ?: DEFAULT_GUARANTEE
                } else {
                    0
                },
                termMonths = if (type == AnnuityType.FIXED_TERM) months else null,
                indexationRate = if (type == AnnuityType.INDEXED) s.dec("indexationRate", "0.02") else BigDecimal.ZERO,
                survivorShare = if (type == AnnuityType.JOINT_LIFE) request.survivorShare ?: DEFAULT_SURVIVOR else null,
                oneOffFee = fee,
                annualFeeRate = s.dec("annualFeeRate", "0.005"),
                validUntil = validUntil,
                illustrative = true,
            )
        }
    }

    override suspend fun purchase(provider: ApprovedPartner, application: AnnuityApplication): PartnerPolicyStatus {
        val app = applications.computeIfAbsent("${provider.partnerId}|${application.idempotencyKey}") {
            Application(
                provider.partnerId,
                "SIMAPP-${provider.partnerId}-${application.requestId}",
                BigDecimal.ZERO,
                PartnerPolicyState.APPLIED,
            )
        }
        return PartnerPolicyStatus(app.ref, app.state)
    }

    override suspend fun status(provider: ApprovedPartner, applicationRef: String): PartnerPolicyStatus {
        val app = byRef(provider, applicationRef)
            ?: return PartnerPolicyStatus(applicationRef, PartnerPolicyState.REFUSED, reason = "unknown application")
        if (app.state == PartnerPolicyState.APPLIED) {
            // The simulator assumes the premium arrived when asked after the transfer.
            app.state = if (provider.terms.adapterSettings["refusePolicy"] == "true") {
                PartnerPolicyState.REFUSED
            } else {
                PartnerPolicyState.ACTIVE
            }
        }
        return describe(app)
    }

    override suspend fun cancel(
        provider: ApprovedPartner,
        applicationRef: String,
        reason: PartnerCancellationReason,
        idempotencyKey: String,
    ): PartnerPolicyStatus {
        val app = byRef(provider, applicationRef)
            ?: return PartnerPolicyStatus(applicationRef, PartnerPolicyState.REFUSED, reason = "unknown application")
        app.state = PartnerPolicyState.CANCELLED
        return describe(app)
    }

    private fun byRef(provider: ApprovedPartner, ref: String) =
        applications.values.firstOrNull { it.partnerId == provider.partnerId && it.ref == ref }

    private fun describe(app: Application) = when (app.state) {
        PartnerPolicyState.ACTIVE -> PartnerPolicyStatus(
            app.ref,
            PartnerPolicyState.ACTIVE,
            policyRef = app.ref.replace("SIMAPP", "SIMPOL"),
            issuedOn = LocalDate.now(clock),
        )
        PartnerPolicyState.REFUSED -> PartnerPolicyStatus(
            app.ref,
            app.state,
            reason = "simulated refusal",
            refundRef = "SIMREF-${app.ref}",
        )
        PartnerPolicyState.CANCELLED -> PartnerPolicyStatus(
            app.ref,
            app.state,
            reason = "cancelled",
            refundRef = "SIMREF-${app.ref}",
        )
        else -> PartnerPolicyStatus(app.ref, app.state)
    }

    private fun Map<String, String>.dec(key: String, default: String) = BigDecimal(this[key] ?: default)

    private fun Map<String, String>.int(key: String, default: Int) = this[key]?.toInt() ?: default

    companion object {
        const val KIND = "simulator"
        private const val MIN_MONTHS = 120
        private const val MONTHS_PER_YEAR = 12
        private const val DEFAULT_HORIZON_AGE = 100
        private const val DEFAULT_VALIDITY_HOURS = 72
        private const val DEFAULT_TERM_MONTHS = 120
        private const val DEFAULT_GUARANTEE = 120
        private const val INDEX_DISCOUNT_YEARS = 6
        private const val OFFER_SUFFIX = 12
        private val DEFAULT_SURVIVOR = BigDecimal("0.6")
    }
}
