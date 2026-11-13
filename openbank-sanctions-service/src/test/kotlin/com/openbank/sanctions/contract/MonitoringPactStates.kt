// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.contract

import com.openbank.sanctions.application.usecase.SanctionsMonitoringSettings
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.security.TestIdentityAssociation
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Duration
import javax.sql.DataSource

/**
 * Provider states of the kyb-service ongoing-monitoring pact (ADR-0376), defined once for the
 * `@PactFolder` and `@PactBroker` twins.
 *
 * These interactions differ from every other one this provider replays in WHO calls. The registry
 * takes the owner of a registration from the caller's own service-account token and refuses
 * anything else, so the class-level `@TestSecurity` principal (`pact-verifier`, not a token) is
 * answered 403 — correctly. [actAs] therefore replaces the test identity for the one interaction
 * whose state names a caller, with what the service sees in production: a verified token whose
 * `azp` is bound to `service-account-<azp>`, carrying `ROLE_API` and nothing else. The next
 * interaction starts from the class-level identity again.
 *
 * Public, not internal: the verification classes are compiled a second time in the provider-pact
 * source set, which is a different Kotlin module.
 */
object MonitoringPactStates {
    const val KYB_CALLER = "ongoing monitoring is enabled and the caller is kyb-service under its own service account"
    const val KYB_SUBJECT_REGISTERED = "kyb-service has registered a subject for ongoing monitoring"
    const val KYB_SUBJECT_MATCHED = "a subject registered by kyb-service matches a changed list entry"
    const val SHARED_CLIENT = "the caller presents the fleet's shared client"

    const val KYB_CLIENT = "openbank-kyb"
    private const val SHARED_FLEET_CLIENT = "openbank-services"

    /** The references the consumer pact pins, one per interaction. */
    const val REGISTERED_REF = "7c1d5b0e-2a54-4c8b-9f0e-5d1e6a7b8c01"
    const val WITHDRAWN_REF = "7c1d5b0e-2a54-4c8b-9f0e-5d1e6a7b8c02"
    const val MATCHED_REF = "7c1d5b0e-2a54-4c8b-9f0e-5d1e6a7b8c03"

    private val CALLERS = mapOf(
        KYB_CALLER to KYB_CLIENT,
        KYB_SUBJECT_REGISTERED to KYB_CLIENT,
        SHARED_CLIENT to SHARED_FLEET_CLIENT,
    )

    /** Replaces the test identity when one of [states] names a caller; leaves it alone otherwise. */
    fun actAs(states: Collection<String>, association: TestIdentityAssociation) {
        val client = states.firstNotNullOfOrNull { CALLERS[it] } ?: return
        association.setTestIdentity(serviceAccount(client))
    }

    private fun serviceAccount(clientId: String): SecurityIdentity =
        QuarkusSecurityIdentity.builder().setPrincipal(ServiceAccountToken(clientId)).addRole("ROLE_API").build()

    /** Monitoring ships disabled; the registry answers 503 unless the interaction switches it on. */
    fun enableMonitoring() {
        QuarkusMock.installMockForType(
            SanctionsMonitoringSettings(
                enabled = true,
                deltaMaxEntriesPerRun = 5,
                deltaMaxPending = 20_000,
                deltaMaxCandidatesPerEntry = 5_000,
                sweepBatchSize = 25,
                sweepInterval = Duration.ofDays(30),
                claimLease = Duration.ofMinutes(15),
            ),
            SanctionsMonitoringSettings::class.java,
        )
    }

    /** [KYB_CALLER]: monitoring on and the reference free, so the registration answers 201 on every replay. */
    fun kybCallerWithMonitoringEnabled(dataSource: DataSource) {
        enableMonitoring()
        forget(dataSource, REGISTERED_REF)
    }

    /** [KYB_SUBJECT_REGISTERED]: a registry row of kyb-service for the withdrawal to delete. */
    fun kybSubjectRegistered(dataSource: DataSource) {
        forget(dataSource, WITHDRAWN_REF)
        execute(
            dataSource,
            "WITH s AS (INSERT INTO sanctions_monitored_subjects " +
                "(id, owner_service, subject_ref, entity_type, name, search_names, registered_at, updated_at) " +
                "VALUES (gen_random_uuid(), '$KYB_CLIENT', '$WITHDRAWN_REF', 'INDIVIDUAL', 'Harmless Nobodyson', " +
                "ARRAY['harmless nobodyson'], now(), now()) RETURNING id, search_names) " +
                "INSERT INTO sanctions_monitored_subject_names (subject_id, search_name) " +
                "SELECT s.id, n FROM s CROSS JOIN unnest(s.search_names) AS n",
        )
    }

    private fun forget(dataSource: DataSource, subjectRef: String) = execute(
        dataSource,
        "DELETE FROM sanctions_monitored_subjects WHERE owner_service = '$KYB_CLIENT' AND subject_ref = '$subjectRef'",
    )

    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private class ServiceAccountToken(clientId: String) : JsonWebToken {
        private val claims = mapOf(
            "azp" to clientId,
            "preferred_username" to "service-account-$clientId",
            "sub" to "00000000-0000-4000-8000-000000000000",
        )

        override fun getName(): String = claims.getValue("preferred_username")

        override fun getClaimNames(): Set<String> = claims.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T> getClaim(claimName: String): T? = claims[claimName] as? T
    }
}
