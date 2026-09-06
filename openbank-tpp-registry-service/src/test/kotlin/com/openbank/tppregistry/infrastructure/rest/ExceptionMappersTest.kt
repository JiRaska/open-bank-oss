// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.rest

import com.openbank.tppregistry.application.usecase.EbaSyncUnavailableException
import com.openbank.tppregistry.application.usecase.TppAlreadyExistsException
import com.openbank.tppregistry.application.usecase.TppNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExceptionMappersTest {

    @Suppress("UNCHECKED_CAST")
    private fun body(entity: Any?) = entity as Map<String, Any?>

    @Test
    fun `not-found maps to 404 and echoes the exception message`() {
        val response = TppNotFoundMapper().toResponse(TppNotFoundException("TPP CZ-1 not found"))

        assertThat(response.status).isEqualTo(404)
        assertThat(body(response.entity))
            .containsEntry("error", "NOT_FOUND")
            .containsEntry("message", "TPP CZ-1 not found")
    }

    @Test
    fun `already-exists maps to 409 and echoes the exception message`() {
        val response = TppAlreadyExistsMapper().toResponse(TppAlreadyExistsException("TPP CZ-1 already registered"))

        assertThat(response.status).isEqualTo(409)
        assertThat(body(response.entity))
            .containsEntry("error", "CONFLICT")
            .containsEntry("message", "TPP CZ-1 already registered")
    }

    @Test
    fun `eba-sync-unavailable maps to 503 with a fixed message, not the exception's own`() {
        val response = EbaSyncUnavailableMapper().toResponse(EbaSyncUnavailableException())

        assertThat(response.status).isEqualTo(503)
        assertThat(body(response.entity))
            .containsEntry("error", "SERVICE_UNAVAILABLE")
            .containsEntry("message", "EBA sync is temporarily unavailable")
    }
}
