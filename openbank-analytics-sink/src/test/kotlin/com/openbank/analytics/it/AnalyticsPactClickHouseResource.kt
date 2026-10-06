// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.it

import com.openbank.analytics.infrastructure.support.KGenericContainer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.util.UUID

/** Real V1/V5/V10 warehouse for the credit-profile provider contract. Missing Docker fails closed. */
class AnalyticsPactClickHouseResource : QuarkusTestResourceLifecycleManager {
    private var clickHouse: KGenericContainer? = null

    override fun start(): Map<String, String> {
        check(DockerClientFactory.instance().isDockerAvailable) {
            "Docker is required for ClickHouse-backed credit-profile Pact verification"
        }
        val password = UUID.randomUUID().toString()
        val container = KGenericContainer("clickhouse/clickhouse-server:26.8-alpine")
            .withEnv("CLICKHOUSE_DB", DATABASE)
            .withEnv("CLICKHOUSE_USER", USER)
            .withEnv("CLICKHOUSE_PASSWORD", password)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("clickhouse/V1__analytics_bronze_silver.sql"),
                "/docker-entrypoint-initdb.d/01-analytics.sql",
            )
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("clickhouse/V5__party_accounts.sql"),
                "/docker-entrypoint-initdb.d/05-party-accounts.sql",
            )
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("clickhouse/V10__party_credit_profile.sql"),
                "/docker-entrypoint-initdb.d/10-credit-profile.sql",
            )
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
        container.start()
        clickHouse = container
        return mapOf(
            "openbank.analytics.clickhouse.url" to "http://${container.host}:${container.getMappedPort(8123)}",
            "openbank.analytics.clickhouse.database" to DATABASE,
            "openbank.analytics.clickhouse.username" to USER,
            "openbank.analytics.clickhouse.password" to password,
        )
    }

    override fun stop() {
        clickHouse?.stop()
    }

    private companion object {
        const val DATABASE = "openbank_analytics"
        const val USER = "analytics"
    }
}
