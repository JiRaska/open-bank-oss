// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.shadow

import com.openbank.billing.application.port.out.CatalogRevisionUnavailableException
import com.openbank.billing.infrastructure.adapter.RestCatalogRevisionIrPort
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.libs.product.ir.ProductIrParser
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RestCatalogRevisionIrPortTest {

    private val port = RestCatalogRevisionIrPort(mockk(relaxed = true))

    @Test
    fun `a signed revision keeps its IR bytes exact, numeric decimals included`() {
        val numeric = ShadowFixtures.IR.replace("\"4.90\"", "4.90").replace("\"1000.00\"", "1000.00")
        val hash = ProductIrParser.parse(ShadowFixtures.IR).revisionHash
        val body = """{"id":"r1","content":{"behaviour":$numeric},"irSignature":{"revisionHash":"$hash",""" +
            """"keyId":"product-ir-publish","algorithm":"hmac-sha256","signature":"c2ln","signedAt":"2026-10-23T00:00:00Z"}}"""
        val stored = port.parse("r1", body)
        assertThat(stored.hasIr).isTrue()
        assertThat(stored.revisionHash).isEqualTo(hash)
        assertThat(stored.signature!!.algorithm).isEqualTo("hmac-sha256")
        // The decimals survive as written, so the loader re-derives the hash that was signed.
        assertThat(ProductIrParser.parse(stored.irJson!!).revisionHash).isEqualTo(hash)
    }

    @Test
    fun `a revision without behaviour has no IR, and an IR without a signature is kept unsigned`() {
        assertThat(port.parse("r2", """{"id":"r2","content":{"attributes":{}}}""").hasIr).isFalse()
        assertThat(port.parse("r3", """{"id":"r3","content":{"behaviour":null}}""").hasIr).isFalse()
        val unsigned = port.parse("r4", """{"id":"r4","content":{"behaviour":${ShadowFixtures.IR}}}""")
        assertThat(unsigned.hasIr).isTrue()
        assertThat(unsigned.signature).isNull()
        assertThat(unsigned.revisionHash).isNull()
    }

    @Test
    fun `a catalog that cannot answer is CatalogRevisionUnavailableException, whatever the transport did`(): Unit =
        runBlocking {
            val failures = listOf(
                jakarta.ws.rs.ProcessingException("connection refused"),
                jakarta.ws.rs.WebApplicationException(503),
            )
            for (failure in failures) {
                val client = mockk<ProductCatalogRestClient>()
                every { client.getRevisionJson("r1") } returns Uni.createFrom().failure(failure)
                assertThatThrownBy { runBlocking { RestCatalogRevisionIrPort(client).fetch("r1") } }
                    .isInstanceOf(CatalogRevisionUnavailableException::class.java)
            }
            val garbled = mockk<ProductCatalogRestClient>()
            every { garbled.getRevisionJson("r1") } returns Uni.createFrom().item("{not json")
            assertThatThrownBy { runBlocking { RestCatalogRevisionIrPort(garbled).fetch("r1") } }
                .isInstanceOf(CatalogRevisionUnavailableException::class.java)
        }
}
