// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

/** Real broker and database for the connector's nack-to-DLQ contract. */
class TaxWithholdingDlqResource : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgresTestResource()
    private var broker: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        postgres.init(mapOf("db" to "openbank_tax_dlq_it"))
        val database = postgres.start()
        try {
            val kafka = RedpandaContainer(
                DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                    .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
            )
            broker = kafka
            kafka.start()
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(INPUT_TOPIC, DLQ_TOPIC).map { NewTopic(it, 1, 1) })
                    .all().get(20, TimeUnit.SECONDS)
            }
            return database + mapOf(
                "kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.incoming.withholding-remitted-in.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.incoming.withholding-remitted-in.group.id" to GROUP,
                "mp.messaging.incoming.withholding-remitted-in.auto.offset.reset" to "earliest",
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        try {
            broker?.stop()
            broker = null
        } finally {
            postgres.stop()
        }
    }

    companion object {
        const val INPUT_TOPIC = "openbank.interest.accrual.event"
        const val DLQ_TOPIC = "openbank.dlq.tax-reporting.withholding-remitted-in"
        const val GROUP = "tax-withholding-dlq-it"
    }
}
