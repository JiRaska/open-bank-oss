// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.SessionFactory
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test

/** Runs in both the build and provider lanes: JDBC migrations must not enable blocking Hibernate. */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@QuarkusTestResource(PdfRendererStubResource::class, restrictToAnnotatedClass = true)
class DocumentPersistenceModeProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var testPort: String

    @Test
    fun `missing M2M identity cannot read document templates`() {
        given()
            .baseUri("http://localhost:$testPort")
            .get("/api/v1/documents/templates")
            .then()
            .statusCode(401)
    }

    @Inject
    lateinit var sessionFactory: Mutiny.SessionFactory

    @Inject
    lateinit var blockingFactories: Instance<SessionFactory>

    @Inject
    lateinit var jdbc: AgroalDataSource

    @Test
    fun `JDBC for Flyway must not bootstrap a second blocking Hibernate factory`() {
        assertThat(blockingFactories.isUnsatisfied).isTrue()
    }

    @Test
    fun `reactive provider and JDBC migrations share the seeded database`() {
        val reactiveCount = VertxContextSupport.subscribeAndAwait {
            sessionFactory.withSession { session ->
                session.createNativeQuery("select count(*) from document_templates", Long::class.java).singleResult
            }
        }
        jdbc.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from document_templates").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(reactiveCount).isPositive().isEqualTo(rows.getLong(1))
                }
                statement.executeQuery("select count(*) from flyway_schema_history where success = false").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isZero()
                }
            }
        }
    }
}
