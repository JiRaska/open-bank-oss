// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.notification.integration

import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DeviceListContractIT.AdvisoryAuthzProfile::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_ADMIN"])
class DeviceListContractIT {
    class AdvisoryAuthzProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
            "quarkus.datasource.jdbc.enabled" to "true",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `bounded device list keeps items and total response shape`() {
        Given {
            queryParam("partyId", UUID.randomUUID())
            queryParam("limit", 21)
        } When {
            get("/api/v1/devices")
        } Then {
            statusCode(200)
            body("items.size()", equalTo(0))
            body("total", equalTo(0))
        }
    }

    @Test
    fun `device list rejects invalid limit`() {
        Given {
            queryParam("partyId", UUID.randomUUID())
            queryParam("limit", 0)
        } When {
            get("/api/v1/devices")
        } Then {
            statusCode(400)
        }
    }

    @Test
    fun `bounded device list returns newest slice and full total from database`() {
        val partyId = UUID.randomUUID()
        val ids = List(22) { UUID.randomUUID() }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into device_tokens
                  (device_id, party_id, app_instance, platform, token, status,
                   registered_at, created_at, updated_at)
                values (?, ?, 'graph-contract-it', 'IOS', ?, 'ACTIVE', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                ids.forEachIndexed { index, id ->
                    val at = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()))
                    statement.setObject(1, id)
                    statement.setObject(2, partyId)
                    statement.setString(3, "graph-contract-it-$id")
                    statement.setTimestamp(4, at)
                    statement.setTimestamp(5, at)
                    statement.setTimestamp(6, at)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        Given {
            queryParam("partyId", partyId)
            queryParam("limit", 21)
        } When {
            get("/api/v1/devices")
        } Then {
            statusCode(200)
            body("items.size()", equalTo(21))
            body("total", equalTo(22))
            body("items[0].id", equalTo(ids.last().toString()))
        }
    }
}
