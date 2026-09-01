// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import java.time.Instant
import java.util.Currency

/** Pure bank-v1 invariant gate. Generic industry validation belongs to the v2 type schema. */
object ProductValidation {
    private val codePattern = Regex("^[A-Z][A-Z0-9_]{1,63}$")
    private const val PERCENT_MAX = 100.0
    private const val CURRENCY_CODE_LENGTH = 3

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun requireValid(product: Product) {
        require(codePattern.matches(product.code)) {
            "code must contain 2-64 uppercase letters, digits or underscores and start with a letter"
        }
        require(product.name.isNotBlank()) { "name must not be blank" }
        requireNotNull(runCatching { ProductType.valueOf(product.type) }.getOrNull()) {
            "type '${product.type}' is not a supported banking product type"
        }
        requireCurrency(product.currency, "currency")
        require(product.validFrom == null || product.validTo == null || !product.validTo.isBefore(product.validFrom)) {
            "validTo must not be before validFrom"
        }
        requireFinite(product.baseRate, "baseRate")
        requireFinite(product.fee, "fee")
        product.minBalance?.let { requireFinite(it, "minBalance") }
        product.maxBalance?.let { requireFinite(it, "maxBalance") }
        require(product.fee >= 0.0) { "fee must not be negative" }
        require(product.minBalance == null || product.minBalance >= 0.0) { "minBalance must not be negative" }
        require(product.maxBalance == null || product.maxBalance >= 0.0) { "maxBalance must not be negative" }
        require(product.minBalance == null || product.maxBalance == null || product.minBalance <= product.maxBalance) {
            "minBalance must not exceed maxBalance"
        }
        require(product.tags.none(String::isBlank)) { "tags must not contain blanks" }
        require(product.tags.distinct().size == product.tags.size) { "tags must be unique" }
        require(product.eligibilitySegments.distinct().size == product.eligibilitySegments.size) {
            "eligibilitySegments must be unique"
        }
        require(product.fees.map(Fee::id).distinct().size == product.fees.size) { "fee ids must be unique" }
        product.fees.forEach(::requireValidFee)
        product.termsAndConditions.forEach {
            require(it.version.isNotBlank()) { "termsAndConditions.version must not be blank" }
            require(it.url.isNotBlank()) { "termsAndConditions.url must not be blank" }
            require(it.language.isNotBlank()) { "termsAndConditions.language must not be blank" }
            require(it.effectiveTo == null || !it.effectiveTo.isBefore(it.effectiveFrom)) {
                "termsAndConditions.effectiveTo must not be before effectiveFrom"
            }
        }
        product.cardConfig?.let {
            require(it.minCards >= 0) { "cardConfig.minCards must not be negative" }
            require(it.maxCards >= it.minCards) { "cardConfig.maxCards must not be below minCards" }
            require(it.monthlyFeePerCard >= 0.0) { "cardConfig.monthlyFeePerCard must not be negative" }
            requireFinite(it.monthlyFeePerCard, "cardConfig.monthlyFeePerCard")
            it.cardCurrency?.let { currency -> requireCurrency(currency, "cardConfig.cardCurrency") }
        }
        product.multiCurrencyConfig?.let {
            requireFinite(it.fxMarginPct, "multiCurrencyConfig.fxMarginPct")
            it.fxMarginBuyPct?.let { value -> requireFinite(value, "multiCurrencyConfig.fxMarginBuyPct") }
            it.fxMarginSellPct?.let { value -> requireFinite(value, "multiCurrencyConfig.fxMarginSellPct") }
            requireCurrency(it.defaultCurrency, "multiCurrencyConfig.defaultCurrency")
            val currencies = it.requireSupportedCurrencies()
            currencies.forEach { currency ->
                requireCurrency(currency, "multiCurrencyConfig.supportedCurrencies")
            }
            require(currencies.distinct().size == currencies.size) {
                "multiCurrencyConfig.supportedCurrencies must be unique"
            }
            require(!it.enabled || it.defaultCurrency in currencies) {
                "enabled multi-currency product must include its defaultCurrency"
            }
        }
        product.overdraftConfig?.let {
            requireFinite(it.maxLimitAmount, "overdraftConfig.maxLimitAmount")
            requireFinite(it.interestRateAnnual, "overdraftConfig.interestRateAnnual")
            it.unarrangedDailyFee?.let { value -> requireFinite(value, "overdraftConfig.unarrangedDailyFee") }
            it.unarrangedRateAnnual?.let { value -> requireFinite(value, "overdraftConfig.unarrangedRateAnnual") }
            require(it.maxLimitAmount > 0.0) { "overdraftConfig.maxLimitAmount must be positive" }
            require(it.gracePeriodDays >= 0) { "overdraftConfig.gracePeriodDays must not be negative" }
            require(it.unarrangedDailyFee == null || it.unarrangedDailyFee >= 0.0) {
                "overdraftConfig.unarrangedDailyFee must not be negative"
            }
        }
        product.termDepositConfig?.let {
            requireFinite(it.interestRateAnnual, "termDepositConfig.interestRateAnnual")
            requireFinite(it.earlyWithdrawalPenaltyPct, "termDepositConfig.earlyWithdrawalPenaltyPct")
            require(it.termMonths > 0) { "termDepositConfig.termMonths must be positive" }
            require(it.minTermMonths == null || it.minTermMonths > 0) {
                "termDepositConfig.minTermMonths must be positive"
            }
            require(it.maxTermMonths == null || it.maxTermMonths >= it.termMonths) {
                "termDepositConfig.maxTermMonths must not be below termMonths"
            }
            require(it.earlyWithdrawalPenaltyPct in 0.0..PERCENT_MAX) {
                "termDepositConfig.earlyWithdrawalPenaltyPct must be between 0 and 100"
            }
        }
        product.savingsConfig?.let {
            requireFinite(it.excessWithdrawalFee, "savingsConfig.excessWithdrawalFee")
            it.bonusRateAnnual?.let { value -> requireFinite(value, "savingsConfig.bonusRateAnnual") }
            val tiers = it.requireInterestTiers()
            tiers.forEach { tier ->
                requireFinite(tier.fromAmount, "savingsConfig.interestTiers.fromAmount")
                tier.toAmount?.let { value -> requireFinite(value, "savingsConfig.interestTiers.toAmount") }
                requireFinite(tier.rateAnnual, "savingsConfig.interestTiers.rateAnnual")
            }
            require(it.freeWithdrawalsPerMonth >= 0) {
                "savingsConfig.freeWithdrawalsPerMonth must not be negative"
            }
            require(it.excessWithdrawalFee >= 0.0) { "savingsConfig.excessWithdrawalFee must not be negative" }
            tiers.zipWithNext().forEach { (previous, next) ->
                require(previous.toAmount != null && previous.toAmount <= next.fromAmount) {
                    "savingsConfig.interestTiers must be ordered and non-overlapping"
                }
            }
        }
    }

    private fun requireValidFee(fee: Fee) {
        require(fee.id.isNotBlank()) { "fee.id must not be blank" }
        require(fee.name.isNotBlank()) { "fee.name must not be blank" }
        require(fee.type.isNotBlank()) { "fee.type must not be blank" }
        require(fee.frequency.isNotBlank()) { "fee.frequency must not be blank" }
        require(fee.amount >= 0.0) { "fee.amount must not be negative" }
        requireFinite(fee.amount, "fee.amount")
        requireCurrency(fee.currency, "fee.currency")
    }

    private fun requireFinite(value: Double, field: String) {
        require(value.isFinite()) { "$field must be a finite number" }
    }

    private fun requireCurrency(value: String, field: String) {
        require(
            value.length == CURRENCY_CODE_LENGTH &&
                value == value.uppercase() &&
                runCatching {
                    Currency.getInstance(value)
                }.isSuccess,
        ) {
            "$field must be an uppercase ISO 4217 currency code"
        }
    }
}

fun Product.activate(at: Instant): Product = when (status) {
    ProductStatus.DRAFT, ProductStatus.INACTIVE -> copy(status = ProductStatus.ACTIVE, updatedAt = at)
    ProductStatus.ACTIVE -> this
    ProductStatus.DEPRECATED, ProductStatus.ARCHIVED ->
        error("Product $id cannot be activated from $status")
}

fun Product.deactivate(at: Instant): Product = when (status) {
    ProductStatus.ACTIVE -> copy(status = ProductStatus.INACTIVE, updatedAt = at)
    ProductStatus.INACTIVE -> this
    ProductStatus.DRAFT, ProductStatus.DEPRECATED, ProductStatus.ARCHIVED ->
        error("Product $id cannot be deactivated from $status")
}
