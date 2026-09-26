// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResourceExceptionMappersTest {

    private class WidgetNotFound(id: String) : ResourceNotFoundException("Widget $id not found", "WIDGET_NOT_FOUND")

    @Test
    fun `not-found base maps to 404 with the default code`() {
        val response = ResourceNotFoundExceptionMapper().toResponse(ResourceNotFoundException("gone"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(404)
        assertThat(body.status).isEqualTo(404)
        assertThat(body.code).isEqualTo(ErrorCode.NOT_FOUND.code)
        assertThat(body.message).isEqualTo("gone")
        assertThat(body.traceId).isNotBlank()
    }

    @Test
    fun `a subclass keeps its domain code`() {
        val body = ResourceNotFoundExceptionMapper().toResponse(WidgetNotFound("w-1")).entity as ApiError

        assertThat(body.code).isEqualTo("WIDGET_NOT_FOUND")
        assertThat(body.message).isEqualTo("Widget w-1 not found")
    }

    @Test
    fun `conflict base maps to 409 with the same envelope`() {
        val response = ResourceConflictExceptionMapper().toResponse(ResourceConflictException("stale version"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(409)
        assertThat(body.status).isEqualTo(409)
        assertThat(body.code).isEqualTo(ErrorCode.CONFLICT.code)
        assertThat(body.message).isEqualTo("stale version")
    }

    @Test
    fun `envelope shape matches the NoSuchElement mapper`() {
        val lib = ResourceNotFoundExceptionMapper().toResponse(ResourceNotFoundException("x")).entity as ApiError
        val generic = NoSuchElementExceptionMapper().toResponse(NoSuchElementException("x")).entity as ApiError

        assertThat(lib.copy(traceId = "", timestamp = generic.timestamp)).isEqualTo(generic.copy(traceId = ""))
    }
}
