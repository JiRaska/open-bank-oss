// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Fresh source proof inside the publishing JVM; subprocess diagnostics never reach public logs. */
internal object FinrepFreshCheckoutProof {
    fun verify(previous: JsonNode) {
        val actual = execute(
            listOf("python3", ".github/scripts/prove-finrep-historical-checkout.py", "--post-build"),
            Path.of("..").toRealPath(),
        )
        require(actual == previous) { "Checkout proof changed before publication" }
    }

    internal fun execute(
        command: List<String>,
        directory: Path,
        timeoutMillis: Long = 30_000,
        environment: Map<String, String> = System.getenv(),
    ): JsonNode {
        require(timeoutMillis in 1..30_000)
        try {
            val builder = ProcessBuilder(
                command,
            ).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear()
            builder.environment().putAll(environment)
            builder.environment().keys.removeAll { it.startsWith("PACT_BROKER_") || it.startsWith("FINREP_BROKER_") }
            val process = builder.start()
            val output = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.use { it.readNBytes(1_048_577) }
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                error("Fresh checkout proof timed out")
            }
            val bytes = output.get(5, TimeUnit.SECONDS)
            require(process.exitValue() == 0 && bytes.size <= 1_048_576) { "Fresh checkout proof failed" }
            return try {
                val mapper = ObjectMapper().enable(
                    com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
                )
                requireNotNull(mapper.readTree(bytes)).also {
                    require(it.isObject) { "Fresh checkout proof is invalid" }
                }
            } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
                throw IllegalArgumentException("Fresh checkout proof is invalid")
            }
        } catch (_: IOException) {
            error("Fresh checkout proof could not run")
        } catch (_: java.util.concurrent.ExecutionException) {
            error("Fresh checkout proof failed")
        } catch (_: java.util.concurrent.TimeoutException) {
            error("Fresh checkout proof failed")
        }
    }
}
