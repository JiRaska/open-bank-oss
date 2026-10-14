// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI

class HistoricalFinrepBrokerBasicAuthTest {
    @Test
    fun `invalid Basic configurations fail without exposing identities or credentials`() {
        val privateIdentity = "synthetic-private-user"
        val privatePassword = "synthetic-private-password"
        val failures = listOf(
            assertThrows<IllegalArgumentException> {
                HistoricalFinrepBrokerTransport(
                    URI("http://127.0.0.1:12345"),
                    username = privateIdentity,
                    password = privatePassword,
                )
            },
            assertThrows<IllegalArgumentException> {
                HistoricalFinrepBrokerTransport(URI("https://broker.invalid"), username = privateIdentity)
            },
            assertThrows<IllegalArgumentException> {
                HistoricalFinrepBrokerTransport(
                    URI("https://broker.invalid"),
                    "synthetic-token",
                    privateIdentity,
                    privatePassword,
                )
            },
            assertThrows<IllegalArgumentException> {
                HistoricalFinrepBrokerTransport(
                    URI("https://broker.invalid"),
                    username = "$privateIdentity\n",
                    password = privatePassword,
                )
            },
            assertThrows<IllegalArgumentException> {
                HistoricalFinrepBrokerTransport(
                    URI("https://broker.invalid"),
                    username = "",
                    password = privatePassword,
                )
            },
        )
        failures.forEach { failure ->
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
            assertThat(trace).doesNotContain(privateIdentity, privatePassword, "synthetic-token", "broker.invalid")
            assertThat(failure.cause).isNull()
            assertThat(failure.suppressed).isEmpty()
        }
    }
}
