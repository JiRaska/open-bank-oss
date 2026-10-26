// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.e2e

import com.openbank.communication.it.CommunicationPostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the communication E2E suite: real Postgres (the module's
 * [CommunicationPostgresTestResource]) and nothing else — this service has no downstream HTTP
 * clients and its `communication.persona.published.v1` transport is deliberately UNWIRED
 * (ADR-0285 D5), so there is no WireMock to stand up. The E2E evidence is the durable kind:
 * HTTP responses plus JDBC reads of the rows and the `communication_audit_event` trail the
 * domain actually wrote.
 *
 * `authz.enforce=false` is already the module's %test default; the OPA-mediated four-eyes
 * 202/X-Approval-Id pause is the shared AuthorizeInterceptor's own tested mechanism, so this
 * suite proves what THIS service owns: the maker!=checker domain guard, the one-published
 * invariant, the versioned publish/retire chain and the loud-not-silent unwired-publisher
 * outcome recorded in the audit trail.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container) across the whole suite.
 */
class CommunicationE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(CommunicationPostgresTestResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
    )

    override fun runMainMethod(): Boolean = false
}
