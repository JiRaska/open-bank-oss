// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.integration

import com.openbank.notification.it.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ManagedTemplateResourceIT.AdvisoryAuthzProfile::class)
@TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
class ManagedTemplateResourceIT {
    class AdvisoryAuthzProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Inject
    lateinit var dataSource: AgroalDataSource

    @Test
    fun `operator can preview and create a draft with exact placeholders`() {
        val draft = """{"template":"TRANSACTION_COMPLETED","language":"CS","channel":"EMAIL",
            "subject":"Pohyb na účtu","body":"Přijato {{amount}} {{currency}}"}"""
        given().contentType("application/json")
            .body("""{"draft":$draft,"variables":{"amount":"<10>","currency":"CZK"}}""")
            .post("/api/v1/notification-templates/preview")
            .then().statusCode(200).body("body", equalTo("<p>Přijato &lt;10&gt; CZK</p>"))

        val id = given().contentType("application/json").body(draft)
            .post("/api/v1/notification-templates")
            .then().statusCode(201).body("state", equalTo("DRAFT"))
            .extract().path<String>("id")

        given().get("/api/v1/notification-templates/$id")
            .then().statusCode(200).body("createdBy", equalTo("checker"))
        given().queryParam("template", "TRANSACTION_COMPLETED")
            .get("/api/v1/notification-templates")
            .then().statusCode(200)
    }

    @Test
    fun `different operator publishes immutable revision and maker cannot self publish`() {
        val makerDraft = insertDraft("maker")
        given().post("/api/v1/notification-templates/$makerDraft/publish")
            .then().statusCode(200)
            .body("state", equalTo("PUBLISHED"))
            .body("publishedBy", equalTo("checker"))
        val ownDraft = insertDraft("checker")
        given().post("/api/v1/notification-templates/$ownDraft/publish")
            .then().statusCode(409)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT state, published_by FROM managed_notification_templates WHERE id = ?")
                .use { statement ->
                    statement.setObject(1, makerDraft)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("PUBLISHED")
                        assertThat(rows.getString(2)).isEqualTo("checker")
                    }
                }
        }
    }

    private fun insertDraft(actor: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO managed_notification_templates
                    (id, template, language, channel, state, subject, body, created_by, created_at)
                VALUES (?, 'TRANSACTION_COMPLETED', 'CS', 'EMAIL', 'DRAFT',
                        'Pohyb na účtu', 'Přijato {{amount}} {{currency}}', ?, now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, actor)
                statement.executeUpdate()
            }
        }
        return id
    }
}
