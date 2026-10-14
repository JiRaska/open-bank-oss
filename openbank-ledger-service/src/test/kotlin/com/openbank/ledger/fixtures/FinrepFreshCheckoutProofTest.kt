// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class FinrepFreshCheckoutProofTest {
    private val python = "python3"

    @Test
    fun `broker credentials are removed but proof context remains`() {
        val result = FinrepFreshCheckoutProof.execute(
            listOf(python, "-c", "import json,os; print(json.dumps(dict(os.environ)))"),
            Path.of("."),
            environment = mapOf(
                "PACT_BROKER_PASSWORD" to "synthetic-secret",
                "PACT_BROKER_URL" to "https://private.invalid",
                "FINREP_BROKER_TOKEN" to "synthetic-token",
                "FINREP_CHECKOUT_PROOF" to "synthetic-proof-context",
            ),
        )
        assertThat(result.has("PACT_BROKER_PASSWORD")).isFalse()
        assertThat(result.has("PACT_BROKER_URL")).isFalse()
        assertThat(result.has("FINREP_BROKER_TOKEN")).isFalse()
        assertThat(result.path("FINREP_CHECKOUT_PROOF").asText()).isEqualTo("synthetic-proof-context")
    }

    @Test
    fun `malformed and nonzero helper output stays private`() {
        for (script in listOf(
            "import sys; print('private.invalid synthetic-secret'); sys.exit(1)",
            "print('private.invalid synthetic-secret')",
            "print('{\"x\":1,\"x\":2}')",
        )) {
            val failure = assertThrows<RuntimeException> {
                FinrepFreshCheckoutProof.execute(listOf(python, "-c", script), Path.of("."))
            }
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
            assertThat(trace).doesNotContain("private.invalid", "synthetic-secret")
            assertThat(failure.cause).isNull()
        }
    }

    @Test
    fun `helper cannot wait indefinitely`() {
        val failure = assertThrows<IllegalStateException> {
            FinrepFreshCheckoutProof.execute(listOf(python, "-c", "import time; time.sleep(30)"), Path.of("."), 100)
        }
        assertThat(failure.message).isEqualTo("Fresh checkout proof timed out")
    }

    @Test
    fun `oversized helper output is rejected`() {
        assertThrows<IllegalArgumentException> {
            FinrepFreshCheckoutProof.execute(listOf(python, "-c", "print('x' * 1048577)"), Path.of("."))
        }
    }
}
