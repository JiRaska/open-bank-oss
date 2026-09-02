// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Broker-sourced provider verification for case-coordinator-agent — the published-result counterpart to
 * [CaseCoordinatorPactProviderVerificationTest].
 *
 * `@PactFolder` reads pacts off disk: it never contacts the broker, publishes no verification
 * result and creates no provider version. A provider carrying only that half is invisible to
 * `can-i-deploy`, and a broker version row with zero pacts makes the question *unanswerable*
 * rather than negative — every consumer paired with it resolves `UNVERIFIABLE`. document-service
 * sat in exactly that state for 24 days and blocked three consumers, two of them money-path
 * (#7621, fixed by #7738).
 *
 * The sibling's KDoc anticipated this class — "can be added when the coordinator reaches the
 * deploy path". That condition now holds: the agent carries a gitops image pin.
 *
 * Gated on `pactbroker.url`, so it is skipped locally and on the PR lane — `_service-ci.yml`
 * blanks `PACT_BROKER_URL` because the broker has no public ingress (ADR-0056) — and runs on
 * main-push. The `@PactFolder` sibling stays ungated, so PR-time replay (#2327/#2338) is
 * unchanged.
 *
 * The state handlers below are duplicated from the sibling rather than inherited. A subclass was
 * tried first and rejected on evidence: with parent and subclass both present, Quarkus fails with
 * `TestInstantiationException` and the sibling's own tests go red. Duplication is the shape every
 * other pair in the fleet uses.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-case-coordinator-agent")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@TestSecurity(user = "pact-viewer", roles = ["ROLE_VIEWER"])
class CaseCoordinatorPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun before(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port.toInt())
    }

    @State("an open case exists")
    fun seedOpenCase() = seedCase(status = "OPEN", contestedRate = "0.0")

    @State("a closed case with a thread exists")
    fun seedClosedCase() = seedCase(status = "CLOSED", contestedRate = "0.5")

    private fun seedCase(status: String, contestedRate: String) {
        val caseUuid = UUID.nameUUIDFromBytes(WORKFLOW_ID.toByteArray(StandardCharsets.UTF_8))
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            deleteCaseFixtures(conn, caseUuid)
            insertCaseFixtures(conn, caseUuid, status, contestedRate)
            conn.commit()
        }
    }

    private fun deleteCaseFixtures(conn: java.sql.Connection, caseUuid: UUID) {
        conn.prepareStatement("DELETE FROM case_contribution WHERE case_id = ?").use { ps ->
            ps.setObject(1, caseUuid)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM case_outbox WHERE aggregate_id = ?").use { ps ->
            ps.setObject(1, caseUuid)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM case_workflow WHERE id = ?").use { ps ->
            ps.setObject(1, caseUuid)
            ps.executeUpdate()
        }
    }

    private fun insertCaseFixtures(conn: java.sql.Connection, caseUuid: UUID, status: String, contestedRate: String) {
        conn.prepareStatement(
            """
            INSERT INTO case_workflow
                (id, workflow_id, case_class, disposition_target, opened_at, deadline_at,
                 status, budget_tokens, budget_contributions, contested_rate)
            VALUES (?, ?, 'INCIDENT_RESPONSE', 'alert-7', ?, ?, ?, 200000, 40, ?::numeric)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, caseUuid)
            ps.setString(2, WORKFLOW_ID)
            ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(OPENED_MS)))
            ps.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(OPENED_MS + DEADLINE_MS)))
            ps.setString(5, status)
            ps.setString(6, contestedRate)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """
            INSERT INTO case_contribution
                (id, case_id, agent_id, contributed_at, tokens_used, preemption_vote,
                 summary, draft_version, contested, evidence_refs)
            VALUES (?, ?, 'fraud-agent', ?, 0, NULL, 'velocity spike', 1, true, '["tx-1"]'::jsonb)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, caseUuid)
            ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(OPENED_MS + CONTRIBUTED_MS)))
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """
            INSERT INTO case_outbox
                (event_id, aggregate_id, event_type, payload, status, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'case-synthesis', '{}', 'SENT', 1, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, PROPOSAL_UUID)
            ps.setObject(2, caseUuid)
            ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(OPENED_MS + EMITTED_MS)))
            ps.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(OPENED_MS + EMITTED_MS)))
            ps.executeUpdate()
        }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun pactVerificationTestTemplate(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    private companion object {
        const val WORKFLOW_ID = "case-incident-response-alert-7"
        const val OPENED_MS = 1_760_000_000_000L
        const val DEADLINE_MS = 1_200_000L
        const val CONTRIBUTED_MS = 60_000L
        const val EMITTED_MS = 120_000L
        val PROPOSAL_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
