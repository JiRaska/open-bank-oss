// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * #8679: `ScaService.enroll` must commit the enrolled device and its `DEVICE_ENROLLED` outbox row
 * in ONE transaction. This is sca's only outbox write, so the whole of the service's event
 * production rides on it.
 *
 * The oracle (#8496) is Postgres's own `xmin` — the id of the transaction that wrote each row
 * version — so "the same transaction wrote both" is read from the database, never inferred from
 * both rows merely existing. That distinction is the whole point: the fleet's older outbox ITs
 * assert the outbox row LANDED, and a two-transaction implementation satisfies every one of them
 * while still being able to lose the event on a crash between the two commits.
 *
 * Measured on unmodified `origin/main` before the fix: device xmin = 751, outbox xmin = 752 — two
 * distinct writing transactions (`EnrolledDeviceRepositoryImpl.save` and
 * `ScaOutboxRepositoryImpl.save` each opened their own `Panache.withTransaction`, and there was
 * no ambient transaction for either to join). After the fix both rows carry the same xmin.
 *
 * The flow is driven through real HTTP (RestAssured + `@TestSecurity`) rather than by calling the
 * repository: a bare `@QuarkusTest` thread has no Vert.x context for a reactive Panache repo
 * (`No current Vertx context found`), and a mocked repository commits nothing at all. The rows
 * are then read with plain JDBC, so the assertion sees what the database kept, not what the
 * session thinks it holds.
 *
 * The dispatcher is off for this run ([OutboxDispatchDisabledProfile]) — its `markSent` UPDATE
 * would rewrite the outbox row's `xmin` and destroy the evidence.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OutboxDispatchDisabledProfile::class)
class ScaEnrollOutboxAtomicityIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `enroll commits the device and its outbox row in the same transaction`() {
        val partyId = UUID.randomUUID()
        val credentialId = "cred-xmin-${UUID.randomUUID()}"

        Given {
            contentType("application/json")
            body(
                """
                {
                  "credentialId": "$credentialId",
                  "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE",
                  "algorithm": "ES256"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sca/parties/$partyId/devices")
        } Then {
            statusCode(201)
            body("credentialId", equalTo(credentialId))
        }

        val xmins = writerTransactions(partyId)
        assertThat(xmins)
            .describedAs("both the device row and its DEVICE_ENROLLED outbox row must exist for %s", partyId)
            .isNotNull
        val (deviceXmin, outboxXmin) = xmins!!
        assertThat(outboxXmin)
            .describedAs(
                "device row xmin (%d) and outbox row xmin (%d) differ — the enrolment and its " +
                    "DEVICE_ENROLLED event committed in DIFFERENT transactions (#8679)",
                deviceXmin,
                outboxXmin,
            )
            .isEqualTo(deviceXmin)
    }

    /**
     * Known-negative for the oracle above. Without it, `writerTransactions` returning a pair of
     * equal values could just as well mean "the query matches nothing and both halves are absent";
     * a party that was never enrolled must come back as no pair at all, which is what proves the
     * query actually discriminates.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a party that never enrolled a device yields no row pair`() {
        assertThat(writerTransactions(UUID.randomUUID()))
            .describedAs("a party id that was never written must produce no device or outbox row")
            .isNull()
    }

    /**
     * The `xmin` of the device row and of its outbox row, or null when either is missing.
     * `xmin` is a system column Postgres stamps with the id of the transaction that wrote the row
     * version, so equal values mean one writing transaction and different values mean two.
     */
    private fun writerTransactions(partyId: UUID): Pair<Long, Long>? = dataSource.connection.use { conn ->
        val deviceXmin = conn.xminForParty(DEVICE_XMIN_SQL, partyId) ?: return null
        val outboxXmin = conn.xminForParty(OUTBOX_XMIN_SQL, partyId) ?: return null
        deviceXmin to outboxXmin
    }

    private fun Connection.xminForParty(sql: String, partyId: UUID): Long? = prepareStatement(sql).use { st ->
        st.setString(1, partyId.toString())
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }

    private companion object {
        const val DEVICE_XMIN_SQL = "SELECT xmin FROM sca_enrolled_devices WHERE party_id = ?::uuid"
        const val OUTBOX_XMIN_SQL =
            "SELECT xmin FROM sca_outbox WHERE aggregate_id = ?::uuid AND event_type = 'DEVICE_ENROLLED'"
    }
}
