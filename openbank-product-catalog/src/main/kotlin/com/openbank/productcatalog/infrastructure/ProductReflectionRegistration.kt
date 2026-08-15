// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure

import com.openbank.productcatalog.domain.CardConfig
import com.openbank.productcatalog.domain.CardNetwork
import com.openbank.productcatalog.domain.CardTier
import com.openbank.productcatalog.domain.EligibilitySegment
import com.openbank.productcatalog.domain.Fee
import com.openbank.productcatalog.domain.InterestPayoutFrequency
import com.openbank.productcatalog.domain.InterestTier
import com.openbank.productcatalog.domain.MultiCurrencyConfig
import com.openbank.productcatalog.domain.OverdraftConfig
import com.openbank.productcatalog.domain.OverdraftType
import com.openbank.productcatalog.domain.Product
import com.openbank.productcatalog.domain.ProductStatus
import com.openbank.productcatalog.domain.ProductType
import com.openbank.productcatalog.domain.ProductVersion
import com.openbank.productcatalog.domain.SavingsConfig
import com.openbank.productcatalog.domain.TermDepositConfig
import com.openbank.productcatalog.domain.TermsAndConditions
import com.openbank.productcatalog.domain.WithdrawalNotice
import io.quarkus.runtime.annotations.RegisterForReflection

/**
 * Native-image registration for the JSONB persistence graph. This framework concern deliberately
 * lives outside the domain (ADR-0002); JVM Jackson does not need it, native deserialization does.
 */
@RegisterForReflection(
    targets = [
        Product::class,
        Fee::class,
        InterestTier::class,
        CardConfig::class,
        MultiCurrencyConfig::class,
        OverdraftConfig::class,
        TermDepositConfig::class,
        SavingsConfig::class,
        TermsAndConditions::class,
        ProductVersion::class,
        ProductStatus::class,
        ProductType::class,
        CardNetwork::class,
        CardTier::class,
        InterestPayoutFrequency::class,
        WithdrawalNotice::class,
        OverdraftType::class,
        EligibilitySegment::class,
    ],
)
class ProductReflectionRegistration
