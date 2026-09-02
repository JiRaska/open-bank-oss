// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.application.CaseCapabilityGate
import com.openbank.casecoordinator.application.CaseOpenService
import com.openbank.casecoordinator.application.CaseSignalAuthorizationResult
import com.openbank.casecoordinator.application.CaseSignalAuthorizationService
import com.openbank.casecoordinator.application.CaseThreadService
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The denial bodies must not reflect caller-supplied input back (#4834).
 *
 * A plain unit test rather than a `@QuarkusTest`: `signal()` returns 503 before reaching the
 * capability check whenever Temporal is disabled, and Temporal is disabled in `%test` — so the
 * denial branch is unreachable over HTTP in this module's IT profile. Constructing the resource
 * directly is what actually exercises it.
 */
class CaseCoordinatorResourceDenialTest {

    private val openService = mockk<CaseOpenService>()
    private val threadService = mockk<CaseThreadService>()
    private val gate = mockk<CaseCapabilityGate>()
    private val temporalConfig = mockk<TemporalConfig>()
    private val identity = mockk<SecurityIdentity>(relaxed = true)
    private val signalAuthorization = mockk<CaseSignalAuthorizationService>()

    private val resource = CaseCoordinatorResource(
        openService,
        threadService,
        gate,
        mockk(relaxed = true),
        temporalConfig,
        identity,
        signalAuthorization,
    )

    @Test
    fun `a denied signal does not echo the caller's agentId back`() {
        every { temporalConfig.enabled() } returns true
        // The identity binding must PASS here, or the response under test is the wrong denial:
        // this test is about the capability branch, and the asserted-identity branch now runs
        // first (#4834) and carries its own, differently-worded body.
        every { gate.permitsAssertedIdentity(any(), any()) } returns true
        every { gate.canContribute(any()) } returns false
        io.mockk.coEvery { signalAuthorization.authorize(any(), any(), any()) } returns
            CaseSignalAuthorizationResult.Denied

        val marker = "spoofed-agent-DEADBEEF"
        val response = runBlocking {
            resource.signal(
                "case-incident-response-acct-1",
                CaseCoordinatorResource.SignalRequest(type = "contribute", agentId = marker, summary = "x"),
            )
        }

        assertThat(response.status).isEqualTo(403)
        val body = response.entity.toString()
        assertThat(body)
            .describedAs("the denial must still say WHY, or this test would pass on an empty body")
            .contains("contribute")
        assertThat(body)
            .describedAs("caller-supplied agentId must not be reflected into the response")
            .doesNotContain(marker)
    }
}
