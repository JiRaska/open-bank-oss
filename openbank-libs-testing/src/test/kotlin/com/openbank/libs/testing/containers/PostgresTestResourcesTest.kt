// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Real Docker verification for the canonical Testcontainers resources (issue #467) — not just
 * that the classes compile, but that they actually provision a reachable PostgreSQL and (for
 * the Redis variant) return connection config a real client can use. Runs in the normal `test`
 * task, same as every service's own `*ApiIT`; skips gracefully (via [PostgresBase]'s own Docker
 * guard) if Docker isn't available.
 */
class PostgresTestResourcesTest {

    @Test
    fun `PostgresTestResource provisions a reachable database with the given name`() {
        val resource = PostgresTestResource()
        resource.init(mapOf("db" to "openbank_kit_selftest_it"))
        try {
            val config = resource.start()
            assertThat(config["quarkus.datasource.jdbc.url"]).contains("openbank_kit_selftest_it")
            assertThat(config["quarkus.datasource.username"]).isEqualTo("openbank")

            DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url"),
                config["quarkus.datasource.username"],
                config["quarkus.datasource.password"],
            ).use { conn ->
                assertThat(conn.isValid(5)).isTrue()
            }
        } finally {
            resource.stop()
        }
    }

    @Test
    fun `omitting the db initArg falls back to the default database name`() {
        val resource = PostgresTestResource()
        try {
            val config = resource.start()
            assertThat(config["quarkus.datasource.jdbc.url"]).contains("openbank_it")
        } finally {
            resource.stop()
        }
    }

    @Test
    fun `repeated start on one resource reuses the running database`() {
        val resource = PostgresTestResource()
        resource.init(mapOf("db" to "openbank_kit_idempotent_start_it"))
        try {
            val first = resource.start()
            val repeated = resource.start()

            assertThat(repeated["quarkus.datasource.jdbc.url"])
                .isEqualTo(first["quarkus.datasource.jdbc.url"])
        } finally {
            resource.stop()
        }
    }

    @Test
    fun `PostgresRedisTestResource provisions both a database and a reachable Redis`() {
        val resource = PostgresRedisTestResource()
        resource.init(mapOf("db" to "openbank_kit_redis_selftest_it"))
        try {
            val config = resource.start()
            assertThat(config).containsKey("quarkus.redis.hosts")
            assertThat(config.getValue("quarkus.redis.hosts")).startsWith("redis://")
        } finally {
            resource.stop()
        }
    }
}
