// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.it

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * The fraud ITs connect the way the service does under CNPG: as a NON-superuser role that OWNS its
 * database (`owner: openbank` in gitops fraud-service/postgres.yaml), never as the container's
 * bootstrap superuser. A superuser bypasses every GRANT/REVOKE, so a suite running as one passes a
 * migration that removes a privilege the code needs and fails only in the cluster (V10,
 * `FraudThresholdSetPrivilegeIT`).
 *
 * Every resource here is registered globally (no `restrictToAnnotatedClass`), so each one's
 * datasource keys can win for any test class: they must all use this, or one superuser resource
 * silently undoes the rest.
 */
internal object AppOwnerRole {
    const val ROLE = "openbank"
    const val PASSWORD = "openbank_secret"
    const val DATABASE = "openbank_fraud_it"

    /** A Postgres container whose only login is the bootstrap superuser; call [provision] after start. */
    fun container(image: String): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse(image))
        .withUsername("postgres")
        .withPassword("bootstrap_secret")
        .withDatabaseName("postgres")

    /** Creates [ROLE] (NOSUPERUSER) and [DATABASE] owned by it, then returns the app's datasource keys. */
    fun provision(pg: PostgreSQLContainer<*>): Map<String, String> {
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().use {
                it.execute("CREATE ROLE $ROLE LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB PASSWORD '$PASSWORD'")
                it.execute("CREATE DATABASE $DATABASE OWNER $ROLE")
            }
        }
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/$DATABASE",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/$DATABASE",
            "quarkus.datasource.username" to ROLE,
            "quarkus.datasource.password" to PASSWORD,
        )
    }
}
