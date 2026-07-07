// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExceptionMappersTest {

    private val notFoundMapper = NotFoundMapper()
    private val badRequestMapper = BadRequestMapper()

    @Test
    fun `NotFoundMapper renders 404 with the exception message`() {
        val response = notFoundMapper.toResponse(NotFoundException("Payment abc not found"))

        assertThat(response.status).isEqualTo(404)
        assertThat(response.entity).isEqualTo(mapOf("error" to "Payment abc not found"))
    }

    @Test
    fun `BadRequestMapper renders 400 with the exception message`() {
        val response = badRequestMapper.toResponse(BadRequestException("Only SETTLED payments can be recalled"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity).isEqualTo(mapOf("error" to "Only SETTLED payments can be recalled"))
    }
}
