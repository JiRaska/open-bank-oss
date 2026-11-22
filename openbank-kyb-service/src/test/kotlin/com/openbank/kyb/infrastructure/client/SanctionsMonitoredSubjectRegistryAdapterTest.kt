// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.RegistryFailure
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException

/**
 * What the relay does with a failed call is decided by one function, from the status. The cases
 * that matter are the ones that must NOT park a subject: a route miss or a conflict from a registry
 * that was rolled back is retried, and a refused caller is not a refused subject.
 */
class SanctionsMonitoredSubjectRegistryAdapterTest {

    // An anonymous object on purpose: a named class implementing the client interface would be
    // discovered as a bean by every @QuarkusTest of the module and fail their boot.
    private fun answering(failure: Exception?) = object : SanctionsMonitoringRestClient {
        override suspend fun register(subjectRef: String, body: MonitoredSubjectBody): MonitoredSubjectAck {
            failure?.let { throw it }
            return MonitoredSubjectAck("openbank-kyb", subjectRef)
        }

        override suspend fun withdraw(subjectRef: String) {
            failure?.let { throw it }
        }
    }

    private fun adapter(failure: Exception?) =
        SanctionsMonitoredSubjectRegistryAdapter().also { it.client = answering(failure) }

    private fun failureOf(call: suspend () -> Unit): MonitoredSubjectRegistryException =
        runCatching { runBlocking { call() } }.exceptionOrNull() as MonitoredSubjectRegistryException

    @ParameterizedTest(name = "HTTP {0} is {1}")
    @CsvSource(
        "400, REFUSED",
        "422, REFUSED",
        "401, UNAUTHORISED",
        "403, UNAUTHORISED",
        "404, TRANSIENT",
        "405, TRANSIENT",
        "409, TRANSIENT",
        "429, TRANSIENT",
        "500, TRANSIENT",
        "503, TRANSIENT",
    )
    fun `a status decides what the relay does, for a registration and a withdrawal alike`(
        status: Int,
        kind: RegistryFailure,
    ) {
        val adapter = adapter(WebApplicationException(status))

        val registering = failureOf { adapter.register("ref", organisation = false, name = "Synthetic Person") }
        val withdrawing = failureOf { adapter.withdraw("ref") }

        assertThat(registering.kind).isEqualTo(kind)
        assertThat(registering.reason).isEqualTo("http_$status")
        assertThat(withdrawing.kind).isEqualTo(kind)
    }

    @Test
    fun `a transport fault is retried and an accepted call is no failure`() {
        assertThat(failureOf { adapter(IOException("connection reset")).withdraw("ref") }.kind)
            .isEqualTo(RegistryFailure.TRANSIENT)
        assertThatCode {
            runBlocking { adapter(null).register("ref", organisation = true, name = "Synthetic Entity") }
        }.doesNotThrowAnyException()
    }
}
