// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import io.micrometer.core.instrument.Metrics
import org.jboss.logging.Logger

/**
 * Error-path counters (ADR-0326), recorded on Micrometer's global registry — the registry Quarkus
 * exports — so the `@Provider` mappers need no injected collaborator and keep their no-arg shape.
 *
 * libs-runtime compiles against Micrometer without shipping it, so a consumer may not have it. The
 * first reference to [Metrics] then raises a `LinkageError`; it is caught once and the recorder
 * stays off for the life of the process. A metric must never be the reason an error response fails.
 */
internal object ApiErrorMetrics {
    const val GENERIC_MAPPER_FIRED = "openbank.api.generic_exception_mapper.fired"
    const val ERRORS = "openbank.api.errors"

    @Volatile
    private var available = true

    /**
     * One of the three generic JDK-exception mappers answered a request.
     *
     * This is the measurement ADR-0326 phase c waits for: how often, per service, a raw
     * `IllegalArgumentException` / `IllegalStateException` / `NoSuchElementException` is what a
     * caller is answered with. [mapped] is the mapper's own type, [thrown] the concrete class that
     * reached it — `CancellationException` is an `IllegalStateException`, and telling those apart
     * is the point. Both are class names, so the label set is bounded by the code, not by input.
     */
    fun genericMapperFired(mapped: String, thrown: Throwable, status: Int) = count(
        GENERIC_MAPPER_FIRED,
        "mapped",
        mapped,
        "thrown",
        thrown.javaClass.simpleName.ifEmpty { thrown.javaClass.name },
        "status",
        status.toString(),
    )

    /** A typed domain error was rendered. [code] must already be well-formed — it is a label. */
    fun domainError(code: String, category: String, status: Int) =
        count(ERRORS, "code", code, "category", category, "status", status.toString())

    private fun count(name: String, vararg tags: String) {
        if (!available) return
        try {
            Metrics.counter(name, *tags).increment()
        } catch (absent: LinkageError) {
            available = false
            Logger.getLogger(ApiErrorMetrics::class.java)
                .info("Micrometer is not on the classpath; API error counters are disabled", absent)
        }
    }
}
