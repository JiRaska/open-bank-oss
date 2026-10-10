// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.tax.integration

import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@QuarkusTest
@TestProfile(TaxWithholdingDlqIT.RealKafkaProfile::class)
class TaxWithholdingDlqIT {
    class RealKafkaProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(TaxWithholdingDlqResource::class.java),
        )
    }

    @Test
    @Suppress("NestedBlockDepth") // Kafka producer, consumer and admin handles must close after this scenario.
    fun `malformed target record reaches configured DLQ with original key value and type`() {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        val key = UUID.randomUUID().toString()
        val value = """{"remittanceId":"$key","totalTaxAmount":"not-a-number"}"""
        val eventType = "interest.withholding.remitted.v1"
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            KafkaConsumer<String, String>(
                mapOf(
                    "bootstrap.servers" to bootstrap,
                    "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                    "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                    "group.id" to "tax-dlq-probe-${UUID.randomUUID()}",
                    "auto.offset.reset" to "earliest",
                    "enable.auto.commit" to "false",
                ),
            ).use { dlq ->
                dlq.subscribe(listOf(TaxWithholdingDlqResource.DLQ_TOPIC))
                val input = ProducerRecord(TaxWithholdingDlqResource.INPUT_TOPIC, key, value)
                input.headers().add(RecordHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE, eventType.toByteArray()))
                val sent = producer.send(input).get(15, TimeUnit.SECONDS)
                val partition = TopicPartition(sent.topic(), sent.partition())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                var observed = false
                while (!observed && System.nanoTime() < deadline) {
                    dlq.poll(Duration.ofMillis(200)).forEach { record ->
                        if (record.key() == key) {
                            assertThat(record.value()).isEqualTo(value)
                            assertThat(record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)?.value())
                                .isEqualTo(eventType.toByteArray())
                            observed = true
                        }
                    }
                }
                assertThat(observed).describedAs("original malformed record was dead-lettered").isTrue()
                Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                    val commitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    var committedOffset: Long? = null
                    while ((committedOffset ?: 0L) <= sent.offset() && System.nanoTime() < commitDeadline) {
                        committedOffset = admin.listConsumerGroupOffsets(TaxWithholdingDlqResource.GROUP)
                            .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]?.offset()
                        if ((committedOffset ?: 0L) <= sent.offset()) Thread.sleep(100)
                    }
                    assertThat(committedOffset).isGreaterThan(sent.offset())
                }
            }
        }
    }
}
