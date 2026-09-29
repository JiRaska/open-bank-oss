// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import jakarta.ws.rs.Priorities
import org.assertj.core.api.Assertions.assertThat
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.junit.jupiter.api.Test

/**
 * Pins the eIDAS-before-QSEAL ordering (ADR-0090). Before the conversion to `@ServerRequestFilter`
 * these were `ContainerRequestFilter`s carrying `@Priority(AUTHENTICATION)` / `@Priority(AUTHORIZATION)`;
 * a bare `@ServerRequestFilter` defaults both to `USER`, which leaves their relative order undefined.
 *
 * An end-to-end ordering test cannot catch that regression: with equal priorities the chain happens
 * to run eIDAS first today, so it passes with or without the priorities (measured). The priority
 * values themselves are the decidable contract, so this asserts them directly.
 */
class FilterPriorityTest {

    private fun priorityOf(type: Class<*>): Int = type.declaredMethods
        .single { it.isAnnotationPresent(ServerRequestFilter::class.java) }
        .getAnnotation(ServerRequestFilter::class.java)
        .priority

    @Test
    fun `eIDAS transport auth runs at AUTHENTICATION priority`() {
        assertThat(priorityOf(EidasMtlsFilter::class.java)).isEqualTo(Priorities.AUTHENTICATION)
    }

    @Test
    fun `QSEAL message signature runs at AUTHORIZATION priority`() {
        assertThat(priorityOf(QsealSignatureFilter::class.java)).isEqualTo(Priorities.AUTHORIZATION)
    }

    @Test
    fun `eIDAS runs strictly before QSEAL`() {
        assertThat(priorityOf(EidasMtlsFilter::class.java))
            .isLessThan(priorityOf(QsealSignatureFilter::class.java))
    }
}
