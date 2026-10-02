// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.decision.InputDigest
import com.openbank.libs.docs.DocsCatalog
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.identity.BlindIndex
import com.openbank.libs.security.BlindIndexTokenizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Golden vectors for every libs-domain function that renders a digest as hex. These values are
 * evidentiary or identity formats (audit hash chain, idempotency fingerprint, blind index,
 * decision-input digest, docs ETag): a change to how the bytes are rendered must not move one
 * character.
 *
 * The expected strings were computed OUTSIDE this codebase (Python `hashlib`/`hmac`), so they pin
 * the format itself, not whatever the implementation happened to print. Inputs were chosen so the
 * FIRST digest byte covers the cases a hex renderer gets wrong: `0x00` and `0x0f` (a dropped
 * leading zero), `0xff` (a sign-extended byte >= 0x80), plus the empty input.
 */
class HexOutputGoldenVectorTest {

    @Test
    fun `AuditChain sha256Hex`() {
        assertThat(AuditChain.sha256Hex("")).isEqualTo(SHA256_EMPTY)
        assertThat(AuditChain.sha256Hex("audit-72"))
            .isEqualTo("0050ed3adfb37b023bedb23c9e4019f4596b74cca1157873449117478b5b3abf")
        assertThat(AuditChain.sha256Hex("audit-223"))
            .isEqualTo("0f72c309f9e1f9223408195776dbc57dab00e43728d77257bbd8f93304d734b0")
        assertThat(AuditChain.sha256Hex("audit-186"))
            .isEqualTo("ffb3d50f6580791418a76e8f32bdfbe6e3f697a99e1dfece719f9479a71d31fd")
    }

    @Test
    fun `AuditChain link hashes a fixed event to a fixed link`() {
        val event = AuditEvent(
            eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            actorId = "operator",
            actorType = "USER",
            operation = "CREATE",
            resourceType = "LOAN",
            resourceId = "loan-1",
            timestamp = Instant.parse("2026-09-28T12:00:00Z"),
        )
        val first = AuditChain.link(event, "lending", null)
        val second = AuditChain.link(event.copy(operation = "APPROVE"), "lending", first.link)

        assertThat(first.link.hash).isEqualTo(LINK_1_HASH)
        assertThat(second.link.prevHash).isEqualTo(LINK_1_HASH)
        assertThat(second.link.hash).isEqualTo(LINK_2_HASH)
        assertThat(first.canonicalJson).isEqualTo(LINK_1_JSON)
    }

    @Test
    fun `RequestFingerprint of`() {
        assertThat(RequestFingerprint.of("", "", null))
            .isEqualTo("75a11da44c802486bc6f65640aa48a730f0f684c5c07a42ba3cd1735eb3fb070")
        assertThat(RequestFingerprint.of("post", PATH, null))
            .isEqualTo("640e43e76f610cae1e73f9d4f873f17320c63893b04e8053fb18b5396e826fa9")
        assertThat(RequestFingerprint.of("POST", PATH, "{\"n\":429}"))
            .isEqualTo("00fed33eda4ab6aa90614742d011a3195bac8a1d681e3151b3d1db98fe70a658")
        assertThat(RequestFingerprint.of("POST", PATH, "{\"n\":79}"))
            .isEqualTo("0f6fb72ad978080a42ee6bf53907612992598d822bc2183df341f5641806bf6c")
        assertThat(RequestFingerprint.of("POST", PATH, "{\"n\":231}"))
            .isEqualTo("ffa4d493407280225af1239160e167b9240e09a4914c2aa12bd46ac468961cca")
    }

    @Test
    fun `BlindIndex compute`() {
        assertThat(BlindIndex.compute(PEPPER, ""))
            .isEqualTo("9792149f61f9382798ac20085d1996f3c0771b69c28f8429eeb5cea1341df30e")
        assertThat(BlindIndex.compute(PEPPER, "76050603769"))
            .isEqualTo("00ff8ae24ad9bf460fded28d1e09edb4a874cf3158630b8283046e465a09a4fc")
        assertThat(BlindIndex.compute(PEPPER, "7605060338"))
            .isEqualTo("0f58ec27ac1b9b8cae00c5dd85cdf51090872ec1450ef6d10f8decc4e0f2a091")
        assertThat(BlindIndex.compute(PEPPER, "76050603979"))
            .isEqualTo("ffbf9864c7e0fd03476c3be3a2c6ff5df1bc691c748e85d7543b763e56cda1d5")
    }

    @Test
    fun `BlindIndexTokenizer tokenize with a domain`() {
        val tokenizer = BlindIndexTokenizer(PEPPER)
        assertThat(tokenizer.tokenize("", TOKEN_DOMAIN))
            .isEqualTo("9baca5057b989572fc2986a24bf6724898d9e70241724dbbad067c6343aabc34")
        assertThat(tokenizer.tokenize("v71", TOKEN_DOMAIN))
            .isEqualTo("00e8b69ab45cc3748e9915422580bdbe3dc3531e4e8562d227393134ab49832a")
        assertThat(tokenizer.tokenize("v68", TOKEN_DOMAIN))
            .isEqualTo("0fbdb456ccbec10349b8e00fd69aab7c4ff86c1a14b0465579507147cbfdda2c")
        assertThat(tokenizer.tokenize("v452", TOKEN_DOMAIN))
            .isEqualTo("ffe0162e5ab00f1c61e59478e3d9d70025588b147eaeac4675e3c8f07270faa0")
        // The undomained overload is BlindIndex.compute.
        assertThat(tokenizer.tokenize("7605060338"))
            .isEqualTo("0f58ec27ac1b9b8cae00c5dd85cdf51090872ec1450ef6d10f8decc4e0f2a091")
    }

    @Test
    fun `InputDigest sha256`() {
        assertThat(InputDigest.sha256(emptyMap()).hex).isEqualTo(SHA256_EMPTY)
        assertThat(InputDigest.sha256(mapOf("score" to "318", "amount" to "1200.50")).hex)
            .isEqualTo("005ecafaa7eafa4b8de60f014787d9d301de39fd0727f598d7bffdd05624c9e3")
        assertThat(InputDigest.sha256(mapOf("score" to "1", "amount" to "1200.50")).hex)
            .isEqualTo("0f43c318940f83b72b90a6899bffe74c7cdef6135ea52ff56f45a703b909b6f7")
        assertThat(InputDigest.sha256(mapOf("score" to "607", "amount" to "1200.50")).hex)
            .isEqualTo("ffbafbf52644c5e238d67ab74d9ebecd5521b35c1fc5e18ed919a26627d3f55c")
    }

    @Test
    fun `DocsCatalog etag and meta sha256`() {
        val catalog = DocsCatalog(
            mapOf(
                "a" to mapOf("" to "# Doc 41\n"),
                "b" to mapOf("en" to "# Doc 71\n", "cs" to "# Doc 209\n"),
                "c" to mapOf("" to ""),
            ),
        )
        assertThat(catalog.read("a")!!.etag)
            .isEqualTo("00f40a188e439de10356b38bf8ccde1465aec10f3677236ab3a4cfec8fd857e8")
        assertThat(catalog.read("b", "cs")!!.etag)
            .isEqualTo("0f9c30e473619055eba7344b28dbefb1646390680936390e5c286fbc7f82c4ef")
        assertThat(catalog.read("b", "en")!!.etag)
            .isEqualTo("ffbc0981b2ae4fb1bec08420c0c9d3d1c9cfb5f32142f2ae7b72495acc8a95b5")
        assertThat(catalog.read("c")!!.etag).isEqualTo(SHA256_EMPTY)
        assertThat(DocsCatalog(emptyMap()).meta().sha256).isEqualTo(SHA256_EMPTY)
        assertThat(
            DocsCatalog(
                mapOf("a" to mapOf("" to "# Doc 41\n"), "b" to mapOf("en" to "# Doc 71\n", "cs" to "# Doc 209\n")),
            ).meta().sha256,
        ).isEqualTo("6d70e9b696ba3e80c7de09614a7c3e305c11d1fe2c67074f9f4a54ed6dddbf1e")
    }

    private companion object {
        const val SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val PATH = "/api/v1/payments"
        const val TOKEN_DOMAIN = "party.national-id"
        val PEPPER = "pepper-v1".toByteArray()

        const val LINK_1_HASH = "4316aef655c286eb6cdd41702d2ed4e99d5f82f05ebc436c8224e2f130cd941c"
        const val LINK_2_HASH = "4afbd32f6f1506064971b0af1ee8618638c2acf5c10e9f24dcd5ba3cb429d698"
        const val LINK_1_JSON = "{\"actChain\":[],\"actorId\":\"operator\",\"actorType\":\"USER\"," +
            "\"aggregateId\":\"loan-1\",\"aggregateType\":\"LOAN\",\"channel\":null,\"correlationId\":null," +
            "\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"CREATE\"," +
            "\"hash\":\"$LINK_1_HASH\",\"ipAddress\":null,\"occurredAt\":\"2026-09-28T12:00:00Z\"," +
            "\"operation\":\"CREATE\",\"payload\":{}," +
            "\"prevHash\":\"0000000000000000000000000000000000000000000000000000000000000000\"," +
            "\"producer\":\"lending\",\"resourceId\":\"loan-1\",\"resourceType\":\"LOAN\",\"result\":\"SUCCESS\"," +
            "\"seq\":1,\"sessionId\":null,\"sourceService\":\"lending\",\"traceId\":null,\"userAgent\":null}"
    }
}
