// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class DynamicLinkingInputDeserializerTest {
    private val mapper = jacksonObjectMapper()
    private val party = UUID.randomUUID()
    private val linking =
        """{"amount":"250.00","currency":"CZK","creditorIban":"CZ123","creditorName":"Payee","reference":"rent"}"""

    private fun request(linkingValue: String): String =
        """{"partyId":"$party","purpose":"PAYMENT_INITIATION","preferredMethod":null,""" +
            """"dynamicLinkingData":$linkingValue,"redirectUrl":null}"""

    @Test
    fun `legacy JSON string and object decode into exactly the same dynamic link`() {
        val objectRequest = mapper.readValue(request(linking), InitiateScaRequest::class.java)
        val stringRequest = mapper.readValue(
            request(mapper.writeValueAsString(linking)),
            InitiateScaRequest::class.java,
        )

        assertThat(stringRequest.dynamicLinkingData).isEqualTo(objectRequest.dynamicLinkingData)
        assertThat(stringRequest.dynamicLinkingData?.amount).isEqualTo("250.00")
        assertThat(stringRequest.dynamicLinkingData?.creditorIban).isEqualTo("CZ123")
    }

    @Test
    fun `legacy string must contain an object not a scalar or malformed JSON`() {
        for (value in listOf("not-json", "null", "[]", "42")) {
            assertThatThrownBy {
                mapper.readValue(request(mapper.writeValueAsString(value)), InitiateScaRequest::class.java)
            }.isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException::class.java)
        }
    }

    @Test
    fun `contract advertises both wire forms without changing the v1 URL`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val property = contract.substringAfter("        dynamicLinkingData:").substringBefore("        redirectUrl:")
        assertThat(property).contains("oneOf:", "type: string", "#/components/schemas/DynamicLinkingData")
    }
}
