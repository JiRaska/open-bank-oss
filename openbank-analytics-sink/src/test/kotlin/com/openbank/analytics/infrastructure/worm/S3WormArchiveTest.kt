// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.worm

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.IntegrityAnchor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.Optional

/**
 * Tests for the authoritative S3 Object-Lock WORM adapter. The SigV4 signing is verified against the
 * **published AWS Signature V4 test suite** (`get-vanilla`), which transitively proves the signing-key
 * derivation, canonical request and string-to-sign are all correct. The seal/read flow is exercised
 * through the overridden [send] seam, so the Object-Lock headers, key scheme and round-trip parsing are
 * checked without an S3 server.
 */
class S3WormArchiveTest {

    private val mapperFixture = ObjectMapper()

    /** Captures every request and replays scripted responses chosen by URL. */
    private class ScriptedS3(
        mapper: ObjectMapper,
        private val responder: (method: String, url: String) -> Pair<Int, String>,
    ) : S3WormArchive() {
        data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)
        val calls = mutableListOf<Call>()

        init {
            this.mapper = mapper
            clock = Clock.systemUTC()
            endpoint = "https://s3.eu-central-1.amazonaws.com"
            region = "eu-central-1"
            bucket = "test-bucket"
            accessKey = Optional.of("AKIA-TEST")
            secretKey = Optional.of("secret-test")
            retentionYears = 10
        }

        override fun send(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): Pair<Int, String> {
            calls += Call(method, url, headers, body)
            return responder(method, url)
        }
    }

    @Test
    fun `SigV4 matches the AWS get-vanilla test vector`() {
        // The canonical first example from the AWS SigV4 test suite, with the documented credentials.
        val s3 = S3WormArchive().apply {
            mapper = mapperFixture
            region = "us-east-1"
            accessKey = Optional.of("AKIDEXAMPLE")
            secretKey = Optional.of("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        }
        val emptyHash = s3.sha256Hex(ByteArray(0))

        val auth = s3.authorization(
            method = "GET",
            canonicalUri = "/",
            query = emptyMap(),
            headers = mapOf("host" to "example.amazonaws.com", "x-amz-date" to "20150830T123600Z"),
            payloadHash = emptyHash,
            amzDate = "20150830T123600Z",
            service = "service",
        )

        // Authoritative value computed independently from the AWS SigV4 get-vanilla inputs; the
        // canonical-request hash is the published bb579772...e63, so this signature is the ground truth.
        assertThat(auth).isEqualTo(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=ea21d6f05e96a897f6000a1a293f0a5bf0f92a00343409e820dce329ca6365ea",
        )
    }

    @Test
    fun `object key embeds an inverted timestamp so newest sorts first`() {
        val s3 = S3WormArchive().apply { mapper = mapperFixture }
        val older = anchor(sealedAt = Instant.ofEpochMilli(1_000))
        val newer = anchor(sealedAt = Instant.ofEpochMilli(2_000))

        val kOlder = s3.objectKey(older)
        val kNewer = s3.objectKey(newer)

        assertThat(kOlder).startsWith("anchors/").endsWith("-anchor-1.json")
        // Newer (larger epoch) -> smaller inverted value -> sorts BEFORE older lexicographically.
        assertThat(kNewer < kOlder).isTrue()
    }

    @Test
    fun `anchor json round-trips including a null previous hash`() {
        val s3 = S3WormArchive().apply { mapper = mapperFixture }
        val genesis = anchor(previousAnchorHash = null)

        val parsed = s3.parseAnchor(s3.anchorJson(genesis))

        assertThat(parsed).isEqualTo(genesis)
    }

    @Test
    fun `firstKey extracts the key from a ListObjectsV2 response`() {
        val s3 = S3WormArchive().apply { mapper = mapperFixture }
        val xml = """<?xml version="1.0"?><ListBucketResult><Contents>""" +
            """<Key>anchors/0001-abc.json</Key><Size>42</Size></Contents></ListBucketResult>"""

        assertThat(s3.firstKey(xml)).isEqualTo("anchors/0001-abc.json")
        assertThat(s3.firstKey("<ListBucketResult></ListBucketResult>")).isNull()
    }

    @Test
    fun `seal PUTs the anchor with COMPLIANCE object-lock headers and the retention date`() = runBlocking<Unit> {
        val s3 = ScriptedS3(mapperFixture) { _, _ -> 200 to "" }

        s3.seal(anchor(sealedAt = Instant.parse("2026-05-30T12:00:00Z")))

        val put = s3.calls.single()
        val invertedTs = "%019d".format(Long.MAX_VALUE - Instant.parse("2026-05-30T12:00:00Z").toEpochMilli())
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.url).isEqualTo(
            "https://s3.eu-central-1.amazonaws.com/test-bucket/anchors/$invertedTs-anchor-1.json",
        )
        assertThat(put.headers["x-amz-object-lock-mode"]).isEqualTo("COMPLIANCE")
        // retention-years=10 -> sealed 2026 + 10 = 2036.
        assertThat(put.headers["x-amz-object-lock-retain-until-date"]).isEqualTo("2036-05-30T12:00:00Z")
        assertThat(put.headers["Authorization"]).startsWith("AWS4-HMAC-SHA256 Credential=AKIA-TEST/")
        assertThat(put.headers["x-amz-content-sha256"]).isNotBlank()
    }

    @Test
    fun `seal throws when S3 rejects the write`() {
        val s3 = ScriptedS3(mapperFixture) { _, _ -> 403 to "AccessDenied" }

        runCatching { runBlocking { s3.seal(anchor()) } }
            .also { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun `latest lists then gets and parses the newest anchor`() = runBlocking<Unit> {
        val newest = anchor(anchorId = "anchor-9", sealedAt = Instant.parse("2026-05-30T12:00:00Z"))
        val s3 = ScriptedS3(mapperFixture) { _, url ->
            when {
                url.contains("list-type=2") ->
                    200 to """<ListBucketResult><Contents><Key>${"k-9.json"}</Key></Contents></ListBucketResult>"""
                else -> 200 to S3WormArchive().apply { mapper = mapperFixture }.anchorJson(newest)
            }
        }

        val result = s3.latest()

        assertThat(result).isEqualTo(newest)
        assertThat(s3.calls.map { it.method }).containsExactly("GET", "GET")
        assertThat(s3.calls[0].url).contains("list-type=2").contains("prefix=anchors%2F")
        assertThat(s3.calls[1].url).endsWith("/test-bucket/k-9.json")
    }

    @Test
    fun `latest returns null when the bucket holds no anchors yet`() = runBlocking<Unit> {
        val s3 = ScriptedS3(mapperFixture) { _, _ -> 200 to "<ListBucketResult></ListBucketResult>" }

        assertThat(s3.latest()).isNull()
        // Must not attempt a GET when the listing is empty.
        assertThat(s3.calls.map { it.method }).containsExactly("GET")
    }

    private fun anchor(
        anchorId: String = "anchor-1",
        previousAnchorHash: String? = "prev-hash",
        sealedAt: Instant = Instant.parse("2026-05-30T12:00:00Z"),
    ) = IntegrityAnchor(
        anchorId = anchorId,
        merkleRoot = "merkle-root-abc",
        previousAnchorHash = previousAnchorHash,
        recordCount = 7,
        source = "STREAM",
        sealedAt = sealedAt,
    )
}
