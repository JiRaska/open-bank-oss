// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.worm

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.WormArchive
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Optional
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The **authoritative** [WormArchive] (ADR-0023, F1+F2): seals each [IntegrityAnchor] into an
 * S3 bucket with **Object Lock in COMPLIANCE mode**, so the sealed object cannot be overwritten or
 * deleted by anyone — not even the account root — until its retention date passes. That is the
 * genuine write-once-read-many guarantee the operator-mutable [ClickHouseWormArchive] mirror cannot
 * give. Only the small anchors live here (a Merkle root + chain link per batch); the bronze rows stay
 * in ClickHouse, and any tamper challenge re-derives the leaf hashes and checks they still produce the
 * sealed [IntegrityAnchor.merkleRoot].
 *
 * **Targets the S3 API standard, not a product.** Requests are signed with AWS Signature V4 and use
 * the standard `x-amz-object-lock-*` headers, so the production target is AWS S3 (which enforces
 * COMPLIANCE mode at the service level). The MinIO community edition — once the obvious local target —
 * was archived/abandoned in 2026 and is deliberately NOT used; lightweight local emulators do not
 * faithfully enforce Object Lock, so dev keeps the ClickHouse mirror (this adapter's gate stays unset)
 * and the real seal is exercised against S3 in the deployed environment.
 *
 * Implemented over the JDK [HttpClient] with **no new Maven dependency** (no AWS SDK); the SigV4
 * canonicalisation and signing are pure and unit-tested against the published AWS SigV4 test vectors,
 * and the HTTP I/O sits behind the overridable [send] seam so the seal/read flow is testable without S3.
 *
 * It is the `@Alternative @Priority(200)` binding (higher than the ClickHouse mirror, so it wins when
 * both gates are set in production) behind the `@Default` [LoggingWormArchive], gated at build time by
 * `openbank.analytics.worm.backend=s3`.
 */
@ApplicationScoped
@Alternative
@Priority(200)
@IfBuildProperty(name = "openbank.analytics.worm.backend", stringValue = "s3")
open class S3WormArchive : WormArchive {

    // eu-north-1 is the estate region (ADR-0175 §Decision 1); every bucket in the account is
    // eu-north-1, and application.yaml's committed default agrees with this one (issue #3962).
    @ConfigProperty(
        name = "openbank.analytics.worm.s3.endpoint",
        defaultValue = "https://s3.eu-north-1.amazonaws.com",
    )
    lateinit var endpoint: String

    @ConfigProperty(name = "openbank.analytics.worm.s3.region", defaultValue = "eu-north-1")
    lateinit var region: String

    @ConfigProperty(name = "openbank.analytics.worm.s3.bucket", defaultValue = "openbank-analytics-worm")
    lateinit var bucket: String

    // Optional<String>, not a plain String (CLAUDE.md pitfall): SmallRye's built-in String converter
    // treats an empty-string-resolved value as "no value" and throws SRCFG00040 at boot.
    @ConfigProperty(name = "openbank.analytics.worm.s3.access-key")
    lateinit var accessKey: Optional<String>

    @ConfigProperty(name = "openbank.analytics.worm.s3.secret-key")
    lateinit var secretKey: Optional<String>

    /** Object-Lock retention; must be >= the bronze minimum (10y) so the seal outlives what it protects. */
    @ConfigProperty(name = "openbank.analytics.worm.s3.retention-years", defaultValue = "10")
    var retentionYears: Long = 10

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    private val log = Logger.getLogger(S3WormArchive::class.java)
    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    override suspend fun seal(anchor: IntegrityAnchor) {
        val key = objectKey(anchor)
        val body = anchorJson(anchor).toByteArray(Charsets.UTF_8)
        val retainUntil = ISO.format(anchor.sealedAt.atZone(ZoneOffset.UTC).plusYears(retentionYears).toInstant())
        val headers = linkedMapOf(
            "content-type" to "application/json",
            "x-amz-object-lock-mode" to "COMPLIANCE",
            "x-amz-object-lock-retain-until-date" to retainUntil,
        )
        val (status, respBody) = signedSend("PUT", "/$bucket/$key", emptyMap(), headers, body)
        if (status !in 200..299) {
            error("S3 WORM seal failed: PUT /$bucket/$key -> HTTP $status ${respBody.take(300)}")
        }
        log.infof("sealed integrity anchor in S3 Object Lock bucket=%s key=%s retainUntil=%s", bucket, key, retainUntil)
    }

    override suspend fun latest(): IntegrityAnchor? {
        // Keys embed an inverted timestamp so the newest sorts first; list-type=2 max-keys=1 returns it.
        val (listStatus, listBody) = signedSend(
            "GET",
            "/$bucket",
            linkedMapOf("list-type" to "2", "max-keys" to "1", "prefix" to "$KEY_PREFIX/"),
            emptyMap(),
            null,
        )
        if (listStatus !in 200..299) error("S3 WORM list failed: HTTP $listStatus ${listBody.take(300)}")
        val key = firstKey(listBody) ?: return null
        val (getStatus, getBody) = signedSend("GET", "/$bucket/$key", emptyMap(), emptyMap(), null)
        if (getStatus !in 200..299) error("S3 WORM get failed: GET /$bucket/$key -> HTTP $getStatus")
        return parseAnchor(getBody)
    }

    // ----- pure, unit-testable building / parsing -------------------------------------------------

    /** Inverted-timestamp key so a plain ascending list returns the newest anchor first. Pure. */
    internal fun objectKey(anchor: IntegrityAnchor): String {
        val inverted = Long.MAX_VALUE - anchor.sealedAt.toEpochMilli()
        return "$KEY_PREFIX/${"%019d".format(inverted)}-${anchor.anchorId}.json"
    }

    /** Serialises the anchor to the sealed JSON object. Pure. */
    internal fun anchorJson(anchor: IntegrityAnchor): String {
        val row = linkedMapOf<String, Any?>(
            "anchorId" to anchor.anchorId,
            "merkleRoot" to anchor.merkleRoot,
            "previousAnchorHash" to anchor.previousAnchorHash,
            "recordCount" to anchor.recordCount,
            "source" to anchor.source,
            "sealedAt" to ISO.format(anchor.sealedAt),
        )
        return mapper.writeValueAsString(row)
    }

    /** Reads a sealed anchor JSON object back into an [IntegrityAnchor]. Pure. */
    internal fun parseAnchor(json: String): IntegrityAnchor {
        val n = mapper.readTree(json)
        return IntegrityAnchor(
            anchorId = n.path("anchorId").asText(),
            merkleRoot = n.path("merkleRoot").asText(),
            previousAnchorHash = n.path("previousAnchorHash").let {
                if (it.isNull || it.isMissingNode) null else it.asText()
            },
            recordCount = n.path("recordCount").asInt(),
            source = n.path("source").asText(),
            sealedAt = Instant.parse(n.path("sealedAt").asText()),
        )
    }

    /** Extracts the first `<Key>...</Key>` from an S3 ListObjectsV2 XML response; null if none. Pure. */
    internal fun firstKey(xml: String): String? {
        val open = xml.indexOf("<Key>")
        if (open < 0) return null
        val close = xml.indexOf("</Key>", open)
        if (close < 0) return null
        return xml.substring(open + 5, close)
    }

    // ----- AWS Signature Version 4 (pure, tested against the AWS SigV4 test vectors) ---------------

    /**
     * Builds the SigV4 `Authorization` header for a request. Pure given a fixed clock/headers — the
     * SigV4 test suite's get-vanilla vector pins this exactly (see S3WormArchiveTest).
     */
    internal fun authorization(
        method: String,
        canonicalUri: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String,
        amzDate: String,
        service: String = "s3",
    ): String {
        val dateStamp = amzDate.substring(0, 8)
        val sortedHeaders = headers.toSortedMap()
        val canonicalHeaders = sortedHeaders.entries.joinToString("") {
            "${it.key}:${it.value.trim()}\n"
        }
        val signedHeaders = sortedHeaders.keys.joinToString(";")
        // Query strings encode '/' as %2F (unlike path segments); the signature won't match S3 otherwise.
        val canonicalQuery = query.toSortedMap().entries.joinToString("&") {
            "${uriEncode(it.key, true)}=${uriEncode(it.value, true)}"
        }
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
        val signature = hex(hmac(signingKey(secretKey.orElse(""), dateStamp, region, service), stringToSign))
        return "AWS4-HMAC-SHA256 Credential=${accessKey.orElse("")}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
    }

    internal fun signingKey(secret: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmac("AWS4$secret".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    internal fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hmac(key: ByteArray, data: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** RFC 3986 encoding as AWS expects; '/' optionally preserved (path segments). Pure. */
    internal fun uriEncode(value: String, encodeSlash: Boolean): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            when {
                c in 'A'..'Z' ||
                    c in 'a'..'z' ||
                    c in '0'..'9' ||
                    c == '_' ||
                    c == '-' ||
                    c == '~' ||
                    c == '.' -> append(c)
                c == '/' && !encodeSlash -> append(c)
                else -> append("%%%02X".format(b.toInt() and 0xFF))
            }
        }
    }

    // ----- HTTP seam (overridden in tests) --------------------------------------------------------

    /** Signs and performs one S3 request. Splits signing (pure) from the [send] seam (overridable). */
    private suspend fun signedSend(
        method: String,
        canonicalUri: String,
        query: Map<String, String>,
        extraHeaders: Map<String, String>,
        body: ByteArray?,
    ): Pair<Int, String> {
        val host = URI.create(endpoint).host
        val amzDate = AMZ.format(Instant.now(clock))
        val payloadHash = sha256Hex(body ?: ByteArray(0))
        val signed = LinkedHashMap<String, String>()
        signed["host"] = host
        signed["x-amz-content-sha256"] = payloadHash
        signed["x-amz-date"] = amzDate
        signed.putAll(extraHeaders)
        val auth = authorization(method, canonicalUri, query, signed, payloadHash, amzDate)
        val sendHeaders = LinkedHashMap(signed).apply { put("Authorization", auth) }
        val queryString = if (query.isEmpty()) {
            ""
        } else {
            "?" + query.toSortedMap().entries
                .joinToString("&") { "${uriEncode(it.key, true)}=${uriEncode(it.value, true)}" }
        }
        val url = "${endpoint.trimEnd('/')}$canonicalUri$queryString"
        return withContext(Dispatchers.IO) { send(method, url, sendHeaders, body) }
    }

    /** Raw HTTP exchange. `open` so tests capture the request and script the response without S3. */
    protected open fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Pair<Int, String> {
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        val builder = HttpRequest.newBuilder(URI.create(url)).method(method, publisher)
        // 'host' is managed by the JDK client and must not be set explicitly.
        headers.filterKeys { it != "host" }.forEach { (k, v) -> builder.header(k, v) }
        val response = http.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to response.body()
    }

    private companion object {
        const val KEY_PREFIX = "anchors"
        val AMZ: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
