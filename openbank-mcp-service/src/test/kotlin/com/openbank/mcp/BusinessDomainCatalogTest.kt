// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.mcp.application.BusinessDomainCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BusinessDomainCatalogTest {
    @Test
    fun `ownership labels come from the reviewed source declarations`() {
        val expected = mapOf(
            "openbank-account-service" to "Accounts & deposits",
            "openbank-balance-service" to "Accounts & deposits",
            "openbank-statement-service" to "Accounts & deposits",
            "openbank-transaction-service" to "Financial accounting",
            "openbank-consent-service" to "Open banking",
            "openbank-mcp-service" to "Channels",
        )
        expected.forEach { (module, label) ->
            assertThat(BusinessDomainCatalog.forServices(module)).describedAs(module).isEqualTo(label)
        }
    }

    @Test
    fun `a multi-service tool advertises a domain only when every owner agrees`() {
        assertThat(BusinessDomainCatalog.forServices("openbank-sepa-payment", "openbank-domestic-payment"))
            .isEqualTo("Payments")
        assertThat(BusinessDomainCatalog.forServices("openbank-account-service", "openbank-balance-service"))
            .isEqualTo("Accounts & deposits")
        assertThat(BusinessDomainCatalog.forServices("openbank-account-service", "openbank-transaction-service"))
            .isEqualTo("Unclassified")
        assertThat(BusinessDomainCatalog.forServices("openbank-account-service", "openbank-unknown-service"))
            .isEqualTo("Unclassified")
    }

    @Test
    fun `absent unknown and invalid module selectors do not fabricate ownership`() {
        assertThat(BusinessDomainCatalog.forServices()).isEqualTo("Unclassified")
        for (module in listOf("openbank-unknown-service", "account-service", "openbank-../account-service")) {
            assertThat(BusinessDomainCatalog.forServices(module)).describedAs(module).isEqualTo("Unclassified")
        }
    }
}
