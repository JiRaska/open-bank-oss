// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.infrastructure.client

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class OutboundClientRuntimeClasspathTest {
    @Test
    fun `outbound client filter has its OpenTelemetry baggage runtime`() {
        assertThatCode { Class.forName("io.opentelemetry.api.baggage.Baggage") }
            .doesNotThrowAnyException()
    }
}
