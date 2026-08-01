// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.intake

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.util.Optional

/**
 * Customer self-service origination intake (ADR-0211: "Customer intake + signature | customer edge
 * (ADR-0065) + SCA (ADR-0021)"). Every value here is a REFUSAL boundary, not a preference — the
 * request body carries no price, no jurisdiction and no product, because a customer-supplied
 * nominal rate or jurisdiction would let the applicant choose which compliance pack judges them.
 *
 * Default OFF. When on, [callerPrincipal] must name the one M2M identity permitted to submit on a
 * customer's behalf; an empty value refuses every call rather than admitting any operator (see
 * [CustomerIntakeResource] for why that check cannot live in rego alone).
 */
@ApplicationScoped
@Suppress("LongParameterList")
class CustomerIntakeConfig(
    @param:ConfigProperty(name = "lending.intake.enabled", defaultValue = "false")
    val enabled: Boolean = false,
    /** The ONLY principal allowed to submit on a customer's behalf. Blank ⇒ refuse everything. */
    @param:ConfigProperty(name = "lending.intake.caller-principal")
    val callerPrincipal: Optional<String> = Optional.empty(),
    @param:ConfigProperty(name = "lending.intake.jurisdiction", defaultValue = "CZ")
    val jurisdiction: String = "CZ",
    @param:ConfigProperty(name = "lending.intake.product-type", defaultValue = "CONSUMER_CREDIT")
    val productType: String = "CONSUMER_CREDIT",
    @param:ConfigProperty(name = "lending.intake.currency", defaultValue = "CZK")
    val currency: String = "CZK",
    /** Offered nominal annual rate. No default: an unpriced product must refuse, never guess. */
    @param:ConfigProperty(name = "lending.intake.nominal-annual-rate")
    val nominalAnnualRate: Optional<BigDecimal> = Optional.empty(),
    @param:ConfigProperty(name = "lending.intake.min-amount", defaultValue = "5000")
    val minAmount: BigDecimal = BigDecimal("5000"),
    @param:ConfigProperty(name = "lending.intake.max-amount", defaultValue = "1000000")
    val maxAmount: BigDecimal = BigDecimal("1000000"),
    @param:ConfigProperty(name = "lending.intake.min-term-months", defaultValue = "6")
    val minTermMonths: Int = 6,
    @param:ConfigProperty(name = "lending.intake.max-term-months", defaultValue = "120")
    val maxTermMonths: Int = 120,
)
