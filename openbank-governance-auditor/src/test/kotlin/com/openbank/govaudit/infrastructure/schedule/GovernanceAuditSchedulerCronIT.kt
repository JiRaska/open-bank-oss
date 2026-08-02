// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.schedule

import com.openbank.govaudit.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Proves the agent has a WORKING SCHEDULE, by letting the real cron fire.
 *
 * Deliberately not a direct call to [GovernanceAuditScheduler.runScheduledAudit]. A direct call proves the method
 * body works and says nothing about whether anything ever invokes it — it passes identically
 * against the service as it shipped, which had no schedule at all and had therefore audited
 * nothing since it was deployed. The bug lives entirely in the wiring, so the wiring is what has
 * to be exercised.
 *
 * The profile shrinks the cron to every second and leaves Temporal disabled: the assertion is that
 * the SCHEDULER fires, not that a workflow starts.
 *
 * Note the literal cron string. A `QuarkusTestProfile` loads in a DIFFERENT classloader from the
 * test class, so anything computed in `getConfigOverrides()` initialises twice and the scheduler
 * and the assertion can end up holding different values.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(GovernanceAuditSchedulerCronIT.EverySecondProfile::class)
class GovernanceAuditSchedulerCronIT {

    class EverySecondProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Literals only — see the class KDoc.
            "openbank.governance-auditor.audit-cron" to "*/1 * * * * ?",
            "openbank.temporal.enabled" to "false",
        )
    }

    @Inject
    lateinit var scheduler: GovernanceAuditScheduler

    @Test
    fun `the real cron fires the scheduled sweep`() {
        val before = scheduler.fireCount
        val deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS))

        while (Instant.now().isBefore(deadline) && scheduler.fireCount == before) {
            Thread.sleep(POLL_MILLIS)
        }

        assertTrue(
            scheduler.fireCount > before,
            "The scheduled governance-auditor sweep never fired within $TIMEOUT_SECONDS s. The cron " +
                "expression is not wired to the scheduler, which is the defect this test exists " +
                "for: the agent then audits nothing while its empty findings table reads as " +
                "'nothing to act on'.",
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 200L
    }
}
