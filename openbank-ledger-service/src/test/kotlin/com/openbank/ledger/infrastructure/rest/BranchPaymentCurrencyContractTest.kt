// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.libs.market.BranchPaymentCurrency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class BranchPaymentCurrencyContractTest {
    @Test
    fun `the published branch payment denominations match the shared accounting capability`() {
        val document = requireNotNull(javaClass.getResourceAsStream("/openapi.yaml")).use {
            Yaml().load<Map<*, *>>(it)
        }
        val properties =
            child(child(child(child(document, "components"), "schemas"), "BranchPaymentRequest"), "properties")
        val values = child(properties, "currency")["enum"] as? List<*> ?: error("Missing branch currency vocabulary")
        val published = values.map { it as? String ?: error("Currency must be a string") }.toSet()
        assertThat(published).isEqualTo(BranchPaymentCurrency.supportedCodes())
    }

    private fun child(parent: Map<*, *>, name: String): Map<*, *> =
        parent[name] as? Map<*, *> ?: error("Missing schema node $name")
}
