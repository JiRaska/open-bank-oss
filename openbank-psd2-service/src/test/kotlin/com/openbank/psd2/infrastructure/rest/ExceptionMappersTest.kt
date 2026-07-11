// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.psd2.application.usecase.ConsentNotFoundException
import com.openbank.psd2.application.usecase.ConsentUnauthorizedException
import com.openbank.psd2.application.usecase.InvalidPaymentProductException
import com.openbank.psd2.application.usecase.Psd2RequestFormatException
import com.openbank.psd2.application.usecase.TppNotAuthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every mapper renders the Berlin/NextGenPSD2 `tppMessages` error envelope with the correct HTTP
 * status and error code; falls back to a generic message when the exception carries none.
 */
class ExceptionMappersTest {

    @Suppress("UNCHECKED_CAST")
    private fun tppCode(entity: Any?): String {
        val body = entity as Map<String, Any?>
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        return messages[0]["code"] as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun tppText(entity: Any?): String {
        val body = entity as Map<String, Any?>
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        return messages[0]["text"] as String
    }

    @Test
    fun `ConsentNotFoundMapper returns 404 with CONSENT_UNKNOWN`() {
        val response = ConsentNotFoundMapper().toResponse(ConsentNotFoundException("consent-1"))

        assertThat(response.status).isEqualTo(404)
        assertThat(tppCode(response.entity)).isEqualTo("CONSENT_UNKNOWN")
        assertThat(tppText(response.entity)).contains("consent-1")
    }

    @Test
    fun `ConsentUnauthorizedMapper returns 401 with CONSENT_INVALID`() {
        val response = ConsentUnauthorizedMapper().toResponse(ConsentUnauthorizedException("nope"))

        assertThat(response.status).isEqualTo(401)
        assertThat(tppCode(response.entity)).isEqualTo("CONSENT_INVALID")
        assertThat(tppText(response.entity)).isEqualTo("nope")
    }

    @Test
    fun `TppNotAuthorizedMapper returns 401 with CERTIFICATE_INVALID`() {
        val response = TppNotAuthorizedMapper().toResponse(TppNotAuthorizedException("tpp-9"))

        assertThat(response.status).isEqualTo(401)
        assertThat(tppCode(response.entity)).isEqualTo("CERTIFICATE_INVALID")
        assertThat(tppText(response.entity)).contains("tpp-9")
    }

    @Test
    fun `InvalidPaymentProductMapper returns 400 with PRODUCT_INVALID`() {
        val response = InvalidPaymentProductMapper().toResponse(InvalidPaymentProductException("bad product"))

        assertThat(response.status).isEqualTo(400)
        assertThat(tppCode(response.entity)).isEqualTo("PRODUCT_INVALID")
        assertThat(tppText(response.entity)).isEqualTo("bad product")
    }

    @Test
    fun `Psd2RequestFormatMapper returns 400 with FORMAT_ERROR`() {
        val response = Psd2RequestFormatMapper().toResponse(Psd2RequestFormatException("bad format"))

        assertThat(response.status).isEqualTo(400)
        assertThat(tppCode(response.entity)).isEqualTo("FORMAT_ERROR")
        assertThat(tppText(response.entity)).isEqualTo("bad format")
    }

    @Test
    fun `mappers fall back to a default message when the exception carries none`() {
        assertThat(tppText(ConsentNotFoundMapper().toResponse(ConsentNotFoundException("c-1")).entity))
            .contains("Consent not found")
        assertThat(tppText(TppNotAuthorizedMapper().toResponse(TppNotAuthorizedException("t-1")).entity))
            .contains("TPP not authorized")
    }
}
