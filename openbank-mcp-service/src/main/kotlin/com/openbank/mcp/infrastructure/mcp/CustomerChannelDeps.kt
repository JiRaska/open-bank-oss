// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.infrastructure.customer.CustomerChannel
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Everything the customer-channel branch of `/mcp` ([McpCustomerBranch]) collaborates with, held in
 * one bean so [McpEndpoint] injects a single field for the whole channel (ADR-0383 / ADR-0385).
 *
 * Why a holder: each customer-channel slice used to add one more collaborator, which meant one more
 * [McpCustomerBranch] constructor parameter AND one more `lateinit` plus wiring line in [McpEndpoint]
 * — the same three lines in every slice, so parallel slices conflicted with each other on every
 * rebase. A slice now adds one field-injected property here (each in its own block, appended in any
 * order) and reads it from [McpCustomerBranch]; neither the branch's constructor nor the endpoint's
 * wiring changes.
 *
 * Field injection, not a constructor, for exactly that reason: a new property is an independent
 * hunk, never an edit to a shared parameter list.
 */
@ApplicationScoped
class CustomerChannelDeps {
    /** The channel switch and, when on, its admission policy (`mcp.customer.enabled`). */
    @Inject
    lateinit var channel: CustomerChannel

    @Inject
    lateinit var auditor: McpCallAuditor

    @Inject
    lateinit var metrics: McpMetricsPort

    @Inject
    lateinit var limiter: McpRateLimiter

    /** The subject-less audit key (#16847). */
    @Inject
    lateinit var clientAddress: ClientAddressResolver
}
