// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.it.PostgresTestResource
import io.quarkus.bootstrap.logging.InitialConfigurator
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.MDC
import org.jboss.logmanager.ExtHandler
import org.jboss.logmanager.ExtLogRecord
import org.jboss.logmanager.Level
import org.jboss.logmanager.handlers.ConsoleHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.logging.Formatter
import java.util.logging.Handler

/**
 * The console formatter the BOOTED application installed — found by walking the handler tree
 * Quarkus configures, never constructed here. A test that built its own formatter would prove
 * that a JSON encoder encodes JSON, and nothing about what this service writes to stdout.
 *
 * The tree is read from Quarkus's [InitialConfigurator.DELAYED_HANDLER], the handler
 * `LoggingSetupRecorder` fills with the configured console/file handlers, rather than from the
 * root logger: in this test JVM a JUL-to-SLF4J bridge on the test classpath re-parents the
 * root logger's handlers, while the delayed handler is what the application's log records
 * actually pass through.
 */
private fun consoleFormatter(): Formatter {
    fun consoleHandlers(handlers: Array<Handler>): List<ConsoleHandler> = handlers.flatMap { handler ->
        when (handler) {
            is ConsoleHandler -> listOf(handler)
            is ExtHandler -> consoleHandlers(handler.handlers)
            else -> emptyList()
        }
    }
    fun describe(handlers: Array<Handler>, depth: Int = 0): String = handlers.joinToString("") { handler ->
        "  ".repeat(depth) + handler.javaClass.name + " formatter=" + handler.formatter?.javaClass?.name + "\n" +
            if (handler is ExtHandler) describe(handler.handlers, depth + 1) else ""
    }
    val configured = InitialConfigurator.DELAYED_HANDLER.handlers
    val found = consoleHandlers(configured)
    assertThat(found)
        .describedAs("console handlers Quarkus configured; handler tree:\n%s", describe(configured))
        .hasSize(1)
    return found.single().formatter
}

private const val LOGGER = "com.openbank.ledger.StructuredLogEncodingProbe"

private fun record(level: java.util.logging.Level, message: String): ExtLogRecord =
    ExtLogRecord(level, message, ExtLogRecord.FormatStyle.NO_FORMAT, LOGGER).apply { loggerName = LOGGER }

/**
 * Every console line is one JSON object, whatever text the message and the MDC carry.
 *
 * Shared logging is configured in openbank-libs-runtime and supplied by the
 * `quarkus-logging-json` extension the convention plugin adds; this service is where the two
 * meet a real boot. `quarkus.log.console.json` is inert without the extension — the console
 * then keeps a text pattern, which interpolates values verbatim — so the assertions below are
 * about the OUTPUT, not about the configuration.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class StructuredLogEncodingIT {

    // Rejects a document that repeats a key: a reader that keeps the LAST of two "level" keys
    // would otherwise see a different level than the one written first.
    private val strict = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    @AfterEach
    fun clearMdc() {
        listOf("correlationId", "requestId", "traceId", "spanId").forEach(MDC::remove)
    }

    private fun formatAndParse(level: java.util.logging.Level, message: String): JsonNode {
        val line = consoleFormatter().format(record(level, message))
        // One record, one line: the only line break is the delimiter that ends it.
        assertThat(line.trimEnd('\n').lines()).describedAs("physical lines in %s", line).hasSize(1)
        return strict.readTree(line)
    }

    @Test
    fun `a message and MDC values full of JSON syntax stay inside their own fields`() {
        val awkward = "quote \" backslash \\ newline \n return \r tab \t separator   brace } end"
        val message = "payment note: $awkward\",\"level\":\"DEBUG\",\"message\":\"replaced"
        val correlationId = "corr-$awkward\",\"level\":\"TRACE"
        MDC.put("correlationId", correlationId)
        MDC.put("requestId", "req-$awkward")
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c")
        MDC.put("spanId", "b7ad6b7169203331")

        val json = formatAndParse(Level.WARN, message)

        assertThat(json["level"].asText()).isEqualTo("WARN")
        assertThat(json["message"].asText()).isEqualTo(message)
        assertThat(json["mdc"]["correlationId"].asText()).isEqualTo(correlationId)
        assertThat(json["mdc"]["requestId"].asText()).isEqualTo("req-$awkward")
    }

    @Test
    fun `the line carries the field names the log pipeline parses`() {
        MDC.put("correlationId", "corr-1")
        MDC.put("requestId", "req-1")
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c")
        MDC.put("spanId", "b7ad6b7169203331")

        val json = formatAndParse(Level.INFO, "posted journal")

        // gitops/apps/alloy.yaml: `level` becomes a stream label; mdc.traceId / mdc.spanId /
        // mdc.correlationId become structured metadata. A renamed or flattened key empties them.
        assertThat(json["level"].asText()).isEqualTo("INFO")
        assertThat(json["logger"].asText()).isEqualTo(LOGGER)
        assertThat(json["message"].asText()).isEqualTo("posted journal")
        assertThat(json["timestamp"].asText()).isNotBlank()
        assertThat(json["mdc"]["traceId"].asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c")
        assertThat(json["mdc"]["spanId"].asText()).isEqualTo("b7ad6b7169203331")
        assertThat(json["mdc"]["correlationId"].asText()).isEqualTo("corr-1")
        assertThat(json["mdc"]["requestId"].asText()).isEqualTo("req-1")
        assertThat(json.fieldNames().asSequence().toList())
            .doesNotContain("loggerName", "sequence", "loggerClassName", "hostName", "processName", "processId", "ndc")
    }

    @Test
    fun `an exception is one string field on the same line`() {
        val thrown = IllegalStateException("ledger \"closed\"\nfor the day")
        val line = consoleFormatter().format(record(Level.ERROR, "posting failed").apply { this.thrown = thrown })

        assertThat(line.trimEnd('\n').lines()).hasSize(1)
        val json = strict.readTree(line)
        assertThat(json["level"].asText()).isEqualTo("ERROR")
        assertThat(json["message"].asText()).isEqualTo("posting failed")
        assertThat(json["stackTrace"].asText())
            .contains("IllegalStateException", "ledger \"closed\"\nfor the day", "StructuredLogEncodingIT")
    }
}

/**
 * `quarkus.log.console.json=false` — the switch dev mode and the opt-out services use — must
 * still turn the encoder OFF now that the extension is present, leaving the readable text
 * pattern. Without this the `%dev` override could stop meaning anything and nobody would see it
 * outside a developer's terminal.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(StructuredLogOptOutIT.JsonOff::class)
class StructuredLogOptOutIT {

    class JsonOff : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.log.console.json" to "false")
    }

    @Test
    fun `switching JSON off leaves the plain text pattern`() {
        val line = consoleFormatter().format(record(Level.INFO, "posted journal"))

        assertThat(line).contains("INFO", "posted journal").doesNotStartWith("{")
    }
}
