// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Acceptance of the migrated ownership through the real packaged governance resources. */
class DomainOwnershipMigrationTest {

    @Test
    fun `ledger and transaction tools belong to financial accounting`() {
        assertDomains(
            "Financial accounting",
            "list_ledger_journals",
            "get_trial_balance",
            "list_transactions",
            "get_transaction",
        )
    }

    @Test
    fun `product catalog tools belong to products and pricing`() {
        assertDomains("Products & pricing", "list_products", "get_product", "get_product_fees", "get_catalog_revision")
    }

    @Test
    fun `dispute tools belong to cards`() {
        assertDomains("Cards", "dispute_list", "dispute_get", "dispute_list_by_account", "dispute_get_timeline")
    }

    @Test
    fun `all legacy SEPA service aliases retain payments ownership`() {
        assertDomains("Payments", "sepa_instant_list", "sepa_instant_get", "sepa_instant_list_by_debtor")
    }

    @Test
    fun `agent local HITL tools belong to channels`() {
        assertDomains("Channels", "draft_ticket", "flip_feature_flag")
    }

    private fun assertDomains(expected: String, vararg tools: String) {
        tools.forEach { tool ->
            assertThat(ToolServiceCatalog.domainOf(tool))
                .describedAs("packaged ownership of %s", tool)
                .isEqualTo(expected)
        }
    }
}
