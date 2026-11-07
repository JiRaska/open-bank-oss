// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@ConfigMapping(prefix = "openbank.fraud.streams-shadow")
interface ShadowVelocityConfig {
    @WithDefault("false")
    fun enabled(): Boolean
    fun propertiesFile(): Optional<String>

    @WithDefault("1")
    fun partitions(): Int
    fun stateDirectory(): Optional<String>
}

/** An independent, opt-in client: runtime/native failures never enter the fraud verdict path. */
@Startup
@ApplicationScoped
class ShadowVelocityRuntime(
    private val config: ShadowVelocityConfig,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    private val state = AtomicInteger(0)
    private val failed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var streams: KafkaStreams? = null
    internal var streamFactory: (Topology, Properties) -> KafkaStreams = { topology, properties ->
        KafkaStreams(topology, properties)
    }

    @PostConstruct
    fun start() {
        Gauge.builder("openbank.fraud.streams.shadow.state", state) { it.get().toDouble() }
            .description("Shadow client: 0 disabled/starting/stopped, 1 running, -1 failed")
            .register(registry)
        if (!config.enabled()) return
        // Invalid operator configuration is rejected before constructing any Kafka/native client.
        val properties = shadowVelocitySettings(config, loadProperties(config)) ?: return
        try {
            val topology = ShadowVelocityTopology(mapper, clock, ::recordOutcome).build(config.partitions())
            val client = streamFactory(topology, properties).also { streams = it }
            client.setStateListener { next, _ -> updateState(next) }
            client.setUncaughtExceptionHandler { error ->
                if (error is VirtualMachineError) throw error
                if (error is ThreadDeath) throw error
                markFailed()
                StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
            }
            client.start()
        } catch (_: RuntimeException) {
            markFailed()
            stop()
        } catch (_: LinkageError) {
            markFailed()
            stop()
        }
    }

    private fun updateState(next: KafkaStreams.State) {
        when {
            next == KafkaStreams.State.ERROR -> markFailed()
            next == KafkaStreams.State.NOT_RUNNING && !stopping.get() -> markFailed()
            else -> state.updateAndGet { current ->
                if (current < 0) {
                    current
                } else if (next == KafkaStreams.State.RUNNING && !stopping.get()) {
                    1
                } else {
                    0
                }
            }
        }
    }

    private fun markFailed() {
        state.set(-1)
        if (failed.compareAndSet(false, true)) {
            recordOutcome("runtime_failure")
            // Kafka exceptions can contain JAAS, filesystem, broker or payload details.
            Log.error("Fraud shadow Streams client failed; verdict processing remains independent")
        }
    }

    private fun recordOutcome(outcome: String) {
        val label = outcome.takeIf { it in OUTCOMES } ?: "other"
        registry.counter("openbank.fraud.streams.shadow.records", "outcome", label).increment()
    }

    @PreDestroy
    fun stop() {
        stopping.set(true)
        val client = streams
        streams = null
        try {
            if (client != null && !client.close(Duration.ofSeconds(SHUTDOWN_SECONDS))) markFailed()
        } catch (_: RuntimeException) {
            markFailed()
        } catch (_: LinkageError) {
            markFailed()
        }
        state.updateAndGet { if (it < 0) it else 0 }
    }

    private companion object {
        val OUTCOMES = setOf(
            "accepted", "ignored_type", "synthetic", "missing_metadata", "invalid_payload",
            "invalid_identity", "invalid_event_time", "late", "duplicate", "runtime_failure",
        )
    }
}

private fun loadProperties(config: ShadowVelocityConfig): Properties {
    val file = requiredPath(config.propertiesFile(), "Shadow properties file is required")
    return try {
        Files.newBufferedReader(file).use { reader -> Properties().also { it.load(reader) } }
    } catch (_: IOException) {
        throw IllegalArgumentException("Shadow properties file cannot be read")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Shadow properties file is malformed")
    }
}

/** Pure validation and protected defaults; the external file supplies only connection/security fields. */
internal fun shadowVelocitySettings(config: ShadowVelocityConfig, external: Properties): Properties? {
    if (!config.enabled()) return null
    val propertiesFile = requiredPath(config.propertiesFile(), "Shadow properties file is required")
    val stateDirectory = requiredPath(config.stateDirectory(), "Shadow state directory is required")
    val artifacts = Path.of("").toAbsolutePath().normalize()
    require(!stateDirectory.startsWith(artifacts) && !propertiesFile.startsWith(artifacts)) {
        "Shadow state and properties must be outside application artifacts"
    }
    require(config.partitions() in 1..MAX_SHADOW_PARTITIONS) { "Shadow partitions must be between 1 and 32" }
    require(external.keys.all { it is String && it in CONNECTION_KEYS }) { "Unsupported shadow connection property" }
    val bootstrap = external.getProperty("bootstrap.servers")?.trim().orEmpty()
    require(bootstrap.isNotBlank()) { "Shadow bootstrap servers are required" }
    val servers = bootstrap.split(',').map(::bootstrapServer)
    when (external.getProperty("security.protocol")) {
        "SSL" -> Unit
        "SASL_SSL" -> validateSasl(external)
        "PLAINTEXT" -> require(servers.all { it.host in setOf("127.0.0.1", "localhost", "[::1]") }) {
            "Plaintext shadow brokers must use explicit loopback addresses"
        }
        else -> throw IllegalArgumentException("Shadow security protocol must be SSL, SASL_SSL or loopback PLAINTEXT")
    }
    require(
        external.getProperty("security.protocol") == "SASL_SSL" ||
            external.keys.none { it.toString().startsWith("sasl.") },
    ) {
        "SASL properties require SASL_SSL"
    }
    return Properties().also {
        it.putAll(external)
        it[StreamsConfig.APPLICATION_ID_CONFIG] = "openbank-fraud-shadow-velocity-v1"
        it[StreamsConfig.PROCESSING_GUARANTEE_CONFIG] = StreamsConfig.EXACTLY_ONCE_V2
        it[StreamsConfig.NUM_STREAM_THREADS_CONFIG] = "1"
        it[StreamsConfig.STATE_DIR_CONFIG] = stateDirectory.toString()
        it["auto.offset.reset"] = "earliest"
        it["ssl.endpoint.identification.algorithm"] = "https"
    }
}

private fun requiredPath(value: Optional<String>, message: String): Path {
    require(value.isPresent && value.get().isNotBlank()) { message }
    val path = try {
        Path.of(value.get()).normalize()
    } catch (_: InvalidPathException) {
        throw IllegalArgumentException("Shadow path is malformed")
    }
    require(path.isAbsolute && path.parent != null) { "Shadow paths must be explicit absolute paths" }
    return path
}

private fun bootstrapServer(value: String): URI {
    val address = try {
        URI.create("kafka://${value.trim()}")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Shadow bootstrap address is malformed")
    }
    require(
        address.host != null &&
            address.port in 1..MAX_TCP_PORT &&
            address.userInfo == null &&
            address.path.isNullOrEmpty() &&
            address.query == null &&
            address.fragment == null,
    ) {
        "Shadow bootstrap servers require host and port only"
    }
    return address
}

private fun validateSasl(properties: Properties) {
    val module = when (properties.getProperty("sasl.mechanism")) {
        "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule"
        "SCRAM-SHA-256", "SCRAM-SHA-512" -> "org.apache.kafka.common.security.scram.ScramLoginModule"
        else -> throw IllegalArgumentException("Shadow SASL mechanism must be PLAIN or SCRAM")
    }
    // One built-in module and two quoted credentials; external callback/login plugins cannot execute.
    val quoted = "\"(?:[^\"\\\\]|\\\\.)*\""
    val allowed = Regex("${Regex.escape(module)}\\s+required\\s+username=$quoted\\s+password=$quoted\\s*;")
    require(allowed.matches(properties.getProperty("sasl.jaas.config").orEmpty().trim())) {
        "Shadow JAAS requires one built-in module with quoted username and password"
    }
}

private const val SHUTDOWN_SECONDS = 30L
private const val MAX_SHADOW_PARTITIONS = 32
private const val MAX_TCP_PORT = 65535

private val CONNECTION_KEYS = setOf(
    "bootstrap.servers", "security.protocol", "sasl.mechanism", "sasl.jaas.config",
    "ssl.truststore.location", "ssl.truststore.password", "ssl.truststore.type", "ssl.truststore.certificates",
    "ssl.keystore.location", "ssl.keystore.password", "ssl.keystore.type", "ssl.keystore.key",
    "ssl.keystore.certificate.chain", "ssl.key.password",
)
