// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.Properties

class ShadowVelocityRuntimeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `disabled needs no files and never constructs a native client`() {
        val config = Config(enabled = false)
        assertThat(shadowVelocitySettings(config, Properties())).isNull()
        withRegistry { registry ->
            val runtime = ShadowVelocityRuntime(config, ObjectMapper(), Clock.systemUTC(), registry)
            runtime.streamFactory = { _, _ -> error("Disabled pilot constructed a client") }
            runtime.start()
            runtime.stop()
            assertThat(registry.get("openbank.fraud.streams.shadow.state").gauge().value()).isZero()
        }
    }

    @Test
    fun `enabled requires external settings state directory and brokers`() {
        assertRejected(Config(), connection())
        assertRejected(config().copy(stateDirectory = Optional.empty()), connection())
        assertRejected(config().copy(partitions = 0), connection())
        assertRejected(config(), Properties())
        assertRejected(config(), connection().also { it.remove("security.protocol") })
        assertRejected(
            config().copy(stateDirectory = Optional.of(Path.of("").toAbsolutePath().toString())),
            connection(),
        )
    }

    @Test
    fun `protected defaults cannot be overridden through connection file`() {
        val properties = shadowVelocitySettings(config(), connection())!!
        assertThat(properties.getProperty("application.id")).isEqualTo("openbank-fraud-shadow-velocity-v1")
        assertThat(properties.getProperty("processing.guarantee")).isEqualTo("exactly_once_v2")
        assertThat(properties.getProperty("num.stream.threads")).isEqualTo("1")
        assertThat(properties.getProperty("auto.offset.reset")).isEqualTo("earliest")
        assertThat(properties.getProperty("ssl.endpoint.identification.algorithm")).isEqualTo("https")
        for (key in listOf(
            "application.id", "processing.guarantee", "num.stream.threads", "state.dir", "auto.offset.reset",
            "default.key.serde", "sasl.login.callback.handler.class", "ssl.engine.factory.class",
            "consumer.interceptor.classes", "ssl.endpoint.identification.algorithm",
        )) {
            assertRejected(config(), connection().also { it[key] = "override" })
        }
    }

    @Test
    fun `plaintext is restricted to explicit loopback bootstrap addresses`() {
        assertThat(
            shadowVelocitySettings(config(), connection("localhost:9092,127.0.0.1:9093", "PLAINTEXT")),
        ).isNotNull()
        assertRejected(config(), connection("127.0.0.1:9092,broker.example:9093", "PLAINTEXT"))
        assertRejected(config(), connection("localhost.evil.example:9092", "PLAINTEXT"))
        assertRejected(config(), connection("user:password@localhost:9092", "SSL"))
        assertRejected(config(), connection("localhost:9092/path", "SSL"))
        assertRejected(config(), connection("localhost:9092", "SASL_PLAINTEXT"))
        assertThat(shadowVelocitySettings(config(), connection("broker.example:9092", "SSL"))).isNotNull()
    }

    @Test
    fun `SASL accepts only built in login modules and does not reveal credentials`() {
        val properties = connection("broker.example:9092", "SASL_SSL").also {
            it["sasl.mechanism"] = "SCRAM-SHA-512"
            it["sasl.jaas.config"] =
                "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                "username=\"fixture\" password=\"fixture\";"
        }
        assertThat(shadowVelocitySettings(config(), properties)).isNotNull()
        properties["sasl.jaas.config"] = "example.UntrustedModule required password=\"must-not-appear\";"
        assertThatThrownBy { shadowVelocitySettings(config(), properties) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageNotContaining("must-not-appear")
    }

    @Test
    fun `native initialization failure degrades shadow state without escaping`() {
        withRegistry { registry ->
            val runtime = ShadowVelocityRuntime(writeConfig(), ObjectMapper(), Clock.systemUTC(), registry)
            runtime.streamFactory = { _, _ -> throw UnsatisfiedLinkError("private native path") }
            runtime.start()
            assertThat(registry.get("openbank.fraud.streams.shadow.state").gauge().value()).isEqualTo(-1.0)
            assertThat(
                registry.get("openbank.fraud.streams.shadow.records").tag("outcome", "runtime_failure")
                    .counter().count(),
            ).isEqualTo(1.0)
            runtime.stop()
        }
    }

    @Test
    fun `running state failed handler and bounded close are independent of API health`() {
        val client = mockk<KafkaStreams>(relaxed = true)
        val listener = slot<KafkaStreams.StateListener>()
        val handler = slot<StreamsUncaughtExceptionHandler>()
        every { client.setStateListener(capture(listener)) } returns Unit
        every { client.setUncaughtExceptionHandler(capture(handler)) } returns Unit
        every { client.close(Duration.ofSeconds(30)) } returns true
        withRegistry { registry ->
            val runtime = ShadowVelocityRuntime(writeConfig(), ObjectMapper(), Clock.systemUTC(), registry)
            runtime.streamFactory = { _, _ -> client }
            runtime.start()
            listener.captured.onChange(KafkaStreams.State.RUNNING, KafkaStreams.State.REBALANCING)
            assertThat(registry.get("openbank.fraud.streams.shadow.state").gauge().value()).isEqualTo(1.0)
            assertThat(handler.captured.handle(IllegalStateException("private detail")))
                .isEqualTo(StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT)
            runtime.stop()
            assertThat(registry.get("openbank.fraud.streams.shadow.state").gauge().value()).isEqualTo(-1.0)
            verify(exactly = 1) { client.start() }
            verify(exactly = 1) { client.close(Duration.ofSeconds(30)) }
        }
    }

    private fun config() = Config(
        propertiesFile = Optional.of(directory.resolve("connection.properties").toString()),
        stateDirectory = Optional.of(directory.resolve("state").toString()),
    )

    private fun writeConfig(): Config = config().also {
        Files.newBufferedWriter(Path.of(it.propertiesFile.get())).use { writer -> connection().store(writer, null) }
    }

    private fun connection(servers: String = "localhost:9092", protocol: String = "SSL") = Properties().also {
        it["bootstrap.servers"] = servers
        it["security.protocol"] = protocol
    }

    private fun assertRejected(config: ShadowVelocityConfig, properties: Properties) {
        assertThatThrownBy {
            shadowVelocitySettings(config, properties)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun withRegistry(test: (SimpleMeterRegistry) -> Unit) {
        val registry = SimpleMeterRegistry()
        try {
            test(registry)
        } finally {
            registry.close()
        }
    }

    private data class Config(
        val enabled: Boolean = true,
        val propertiesFile: Optional<String> = Optional.empty(),
        val partitions: Int = 1,
        val stateDirectory: Optional<String> = Optional.empty(),
    ) : ShadowVelocityConfig {
        override fun enabled() = enabled
        override fun propertiesFile() = propertiesFile
        override fun partitions() = partitions
        override fun stateDirectory() = stateDirectory
    }
}
