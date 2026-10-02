// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

/**
 * ADR-0326 phase a: the three generic JDK-exception mappers count every firing, so the blast radius
 * of demoting them is measured per service before anything is demoted. Their status and body are
 * pinned here too — this slice adds a counter and must change nothing else.
 */
class GenericMapperFiringMetricTest {

    private val registry = SimpleMeterRegistry()

    @BeforeEach
    fun addRegistry() {
        Metrics.addRegistry(registry)
    }

    @AfterEach
    fun removeRegistry() {
        Metrics.removeRegistry(registry)
    }

    private fun fired(mapped: String, thrown: String, status: String): Double =
        registry.find(ApiErrorMetrics.GENERIC_MAPPER_FIRED)
            .tag("mapped", mapped).tag("thrown", thrown).tag("status", status).counter()?.count() ?: 0.0

    // Micrometer's global registry is a composite: a meter created in an earlier test is mirrored
    // into every registry added later, at zero. So "not counted" is a SUM of zero, never an empty
    // meter list — which is also why each test gets a fresh registry rather than a fresh meter.
    private fun totalFirings(): Double =
        registry.find(ApiErrorMetrics.GENERIC_MAPPER_FIRED).counters().sumOf { it.count() }

    @Test
    fun `nothing is counted before a mapper fires`() {
        assertThat(totalFirings()).isZero()
    }

    @Test
    fun `IllegalArgumentException still answers 400 VALIDATION_ERROR and is counted`() {
        val response = IllegalArgumentExceptionMapper().toResponse(IllegalArgumentException("bad input"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(400)
        assertThat(body.code).isEqualTo("VALIDATION_ERROR")
        assertThat(body.message).isEqualTo("bad input")
        assertThat(fired("IllegalArgumentException", "IllegalArgumentException", "400")).isEqualTo(1.0)
    }

    @Test
    fun `IllegalStateException still answers 422 BUSINESS_RULE_VIOLATION and is counted`() {
        val response = IllegalStateExceptionMapper().toResponse(IllegalStateException("not allowed"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(422)
        assertThat(body.code).isEqualTo("BUSINESS_RULE_VIOLATION")
        assertThat(body.message).isEqualTo("not allowed")
        assertThat(fired("IllegalStateException", "IllegalStateException", "422")).isEqualTo(1.0)
    }

    @Test
    fun `NoSuchElementException still answers 404 NOT_FOUND and is counted`() {
        val response = NoSuchElementExceptionMapper().toResponse(NoSuchElementException("nothing"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(404)
        assertThat(body.code).isEqualTo("NOT_FOUND")
        assertThat(body.message).isEqualTo("nothing")
        assertThat(fired("NoSuchElementException", "NoSuchElementException", "404")).isEqualTo(1.0)
    }

    @Test
    fun `the thrown label tells a cancelled coroutine from a business rule`() {
        IllegalStateExceptionMapper().toResponse(CancellationException("job was cancelled"))
        IllegalStateExceptionMapper().toResponse(IllegalStateException("rule"))
        IllegalStateExceptionMapper().toResponse(IllegalStateException("rule"))

        assertThat(fired("IllegalStateException", "CancellationException", "422")).isEqualTo(1.0)
        assertThat(fired("IllegalStateException", "IllegalStateException", "422")).isEqualTo(2.0)
    }

    @Test
    fun `the typed mapper does not count as a generic firing`() {
        DomainExceptionMapper().toResponse(com.openbank.libs.domain.error.NotFound("x"))

        assertThat(totalFirings()).isZero()
        // …while the same call IS visible on the typed counter, so the zero above is not a dead probe.
        assertThat(registry.find(ApiErrorMetrics.ERRORS).counters().sumOf { it.count() }).isEqualTo(1.0)
    }
}
