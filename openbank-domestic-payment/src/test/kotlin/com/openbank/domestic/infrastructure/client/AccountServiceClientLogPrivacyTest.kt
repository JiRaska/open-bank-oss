// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.quarkus.oidc.client.OidcClient
import io.quarkus.oidc.client.Tokens
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

class AccountServiceClientLogPrivacyTest {

    @Test
    fun `HTTP lookup failures omit the raw IBAN and exception payload`() {
        val failure = WebApplicationException(
            "$PRIVATE_EXCEPTION_MARKER $ATTACKER_IBAN",
            Response.status(503).build(),
        )
        val client = clientFailingWith(failure)

        val records = captureAccountClientLogs {
            runBlocking {
                assertThat(client.findPartyByIban(ATTACKER_IBAN)).isNull()
                assertThat(client.findAccountIdByIban(ATTACKER_IBAN)).isNull()
            }
        }

        assertThat(records).hasSize(2)
        assertThat(records.joinToString(" ")).contains("503")
        assertThat(records).allSatisfy { record ->
            assertThat(record)
                .doesNotContain(ATTACKER_IBAN, FORGED_LOG_MARKER, PRIVATE_EXCEPTION_MARKER)
                .doesNotContain("\r", "\n")
        }
    }

    @Test
    fun `unexpected lookup failures omit identifiers and throwable text`() {
        val failure = IllegalStateException("$PRIVATE_EXCEPTION_MARKER $ATTACKER_IBAN")
        val client = clientFailingWith(failure)
        val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val records = captureAccountClientLogs {
            runBlocking {
                assertThat(client.findPartyByIban(ATTACKER_IBAN)).isNull()
                assertThat(client.findPartyByAccountId(accountId)).isNull()
                assertThat(client.findAccountIdByIban(ATTACKER_IBAN)).isNull()
            }
        }

        assertThat(records).hasSize(3)
        assertThat(records.joinToString(" ")).contains("IllegalStateException")
        assertThat(records).allSatisfy { record ->
            assertThat(record)
                .doesNotContain(ATTACKER_IBAN, FORGED_LOG_MARKER, PRIVATE_EXCEPTION_MARKER, accountId.toString())
                .doesNotContain("\r", "\n")
        }
    }

    private fun clientFailingWith(failure: Exception): AccountServiceClient {
        val oidcClient = mockk<OidcClient>()
        every { oidcClient.tokens } returns Uni.createFrom().failure<Tokens>(failure)
        val oidcClientInstance = mockk<Instance<OidcClient>>()
        every { oidcClientInstance.get() } returns oidcClient
        return AccountServiceClient(oidcClientInstance, "http://localhost:1")
    }

    private fun captureAccountClientLogs(block: () -> Unit): List<String> {
        val records = mutableListOf<String>()
        val logger = JulLogger.getLogger(AccountServiceClient::class.java.name)
        val previousLevel = logger.level
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                val parameters = record.parameters.orEmpty()
                val formatted = runCatching {
                    String.format(Locale.ROOT, record.message, *parameters)
                }.getOrDefault(record.message)
                val parameterText = parameters.joinToString(" ") { it.toString() }
                val thrown = record.thrown?.stackTraceToString().orEmpty()
                records += listOfNotNull(
                    formatted,
                    parameterText.takeIf(String::isNotBlank),
                    thrown.takeIf(String::isNotBlank),
                ).joinToString(" ")
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
        }
        return records
    }

    private companion object {
        const val ATTACKER_IBAN = "123456789012345678\r\nFORGED_LOG_ENTRY"
        const val FORGED_LOG_MARKER = "FORGED_LOG_ENTRY"
        const val PRIVATE_EXCEPTION_MARKER = "PRIVATE_UPSTREAM_REQUEST_DETAILS"
    }
}
