// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.Optional
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Singleton upstream proxy client (ADR-0065).
 *
 * One [HttpClient] instance shared for the application lifetime — shared connection
 * pool, no per-request thread/fd leak (review B1 fix). Explicit timeouts are set at
 * the request level (B3 fix): connect timeout on the builder, per-request timeout
 * on [HttpRequest.Builder.timeout].
 *
 * M2M token (B4 fix): the customer JWT (openbank-customers realm) is validated at
 * the edge but NOT forwarded to upstream services that validate against the operator
 * openbank realm. This client fetches a service-account token via client_credentials
 * flow (operator realm) and caches it until 60s before expiry. The customer party ID
 * is passed via [PARTY_HEADER] so upstream services can scope data independently.
 */
// TooManyFunctions: one thin method per HTTP verb (get/post/put/patch/delete + an
// anonymous/extra-headers variant) — a generic dispatcher parameterized by verb string would
// collapse the count but trade a self-documenting call site (`upstream.put(...)`) for a
// stringly-typed one, for no real complexity reduction (each verb still needs its own
// header/body wiring). See existing precedent (BerlinXs2aMappers, KycRepository).
@Suppress("TooManyFunctions")
@ApplicationScoped
class UpstreamClient {
    // FIELD injection (not constructor params). With Kotlin constructor parameters that
    // carry default values, Arc/@ConfigProperty injection is shadowed by the Kotlin
    // default — so `clientSecret` silently stayed "" and every client_credentials token
    // fetch got 401 invalid_client → 502 at the edge. (clientId/tokenBase only "worked"
    // because their Kotlin defaults happened to equal the real values.) Field injection
    // runs after construction and overwrites the Kotlin initializer, so config is applied
    // reliably — matching CustomerEdgeResource/OnboardingResource.
    @ConfigProperty(name = "openbank.upstream.connect-timeout-ms", defaultValue = "5000")
    var connectTimeoutMs: Long = 5000

    @ConfigProperty(name = "openbank.upstream.request-timeout-ms", defaultValue = "10000")
    var requestTimeoutMs: Long = 10000

    @ConfigProperty(
        name = "openbank.upstream.token-url",
        defaultValue = "http://keycloak.iam.svc:8080/realms/openbank",
    )
    var tokenEndpointBase: String = "http://keycloak.iam.svc:8080/realms/openbank"

    @ConfigProperty(name = "openbank.upstream.client-id", defaultValue = "openbank-edge")
    var clientId: String = "openbank-edge"

    // Secret is supplied at runtime via env OPENBANK_UPSTREAM_CLIENT_SECRET, mapped by
    // Quarkus to this dotted property.
    @ConfigProperty(name = "openbank.upstream.client-secret", defaultValue = "")
    var clientSecret: String = ""

    /**
     * Optional PEM CA certificate for the private HTTPS campaign validator. The edge currently
     * uses HTTP for its other in-cluster calls, so an empty value keeps development fixtures on
     * the platform default while GitOps mounts the private CA only in the deployed edge.
     */
    @ConfigProperty(name = "openbank.upstream.tls-trust-certificate-file")
    lateinit var tlsTrustCertificateFile: Optional<String>

    // SSRF hardening: every public method below takes a caller-supplied `url` and hands it to
    // URI.create() with no host check of its own — it relies entirely on callers building `url`
    // from a fixed @ConfigProperty base + a validated path segment (true today, per review, but
    // not enforced). This allowlist is the defense-in-depth backstop: reject any target whose
    // host doesn't match a known-safe suffix before a request ever leaves the process.
    // ".svc" covers every in-cluster upstream base URL (Kubernetes Service DNS, e.g.
    // account-service.accounts.svc); 127.0.0.1/localhost cover UpstreamClientTest's throwaway
    // loopback HTTP server. Adjust via config, not by loosening the check in code.
    @ConfigProperty(
        name = "openbank.upstream.allowed-host-suffixes",
        defaultValue = ".svc,127.0.0.1,localhost",
    )
    var allowedHostSuffixes: String = ".svc,127.0.0.1,localhost"

    /**
     * Rejects [url] unless it matches [allowedHostSuffixes], via a regex match against the raw
     * string — CodeQL's java/ssrf query specifically recognizes a regex check as a sanitizing
     * barrier (unlike a host check performed after URI.create()). URI.create() only runs once
     * validation has already passed. A leading "." in an entry (e.g. ".svc") matches any host
     * ending in that domain suffix; anything else (e.g. "127.0.0.1", "localhost") must match the
     * whole host exactly.
     *
     * `inline`: every caller (get/patch/put/delete/post) invokes this as a separate function,
     * and CodeQL's SSRF barrier recognition does not follow the sanitizer across that call
     * boundary — it re-flags a fresh alert at each new caller (#239-241, #288, #289). Inlining
     * puts the regex check in the same function body as the URI.create() sink at every call
     * site, which is what the barrier recognition needs to see.
     */
    private inline fun validatedUri(url: String): URI {
        val entries = allowedHostSuffixes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val alternatives = entries.joinToString("|") { entry ->
            if (entry.startsWith(".")) {
                "[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*" + Regex.escape(entry)
            } else {
                Regex.escape(entry)
            }
        }
        val pattern = Regex("^https?://(?:$alternatives)(?::\\d+)?(?:/.*)?$")
        require(pattern.matches(url)) {
            "refusing upstream call to disallowed host in url " +
                "(configure openbank.upstream.allowed-host-suffixes to permit it)"
        }
        return URI.create(url)
    }

    companion object {
        const val PARTY_HEADER = "X-Customer-Party-Id"
        const val IDEMPOTENCY_REPLAY_HEADER = "X-Idempotency-Replayed"
        private const val TOKEN_REFRESH_BUFFER_SECONDS = 60L
        private val JSON = com.fasterxml.jackson.databind.ObjectMapper()
    }

    // Force HTTP/1.1. The JDK HttpClient defaults to HTTP/2, which over cleartext
    // (h2c) attempts an Upgrade handshake; a POST *with a body* during that upgrade
    // fails against the in-cluster Quarkus/Keycloak servers, so every client_credentials
    // token fetch and every upstream POST threw — surfacing to the app as 502
    // "upstream unavailable". HTTP/1.1 matches what the services actually speak.
    // Lazy so the (field-injected) connect timeout is available when it is first built.
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .apply {
                tlsTrustCertificateFile
                    .filter { it.isNotBlank() }
                    .ifPresent { sslContext(trustContext(Path.of(it))) }
            }
            .build()
    }

    private fun trustContext(certificatePath: Path): SSLContext {
        val certificates = Files.newInputStream(certificatePath).use { input ->
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(input)
        }
        require(certificates.isNotEmpty()) { "TLS trust certificate file is empty: $certificatePath" }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, certificate ->
            trustStore.setCertificateEntry("upstream-ca-$index", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
    }

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpiresAt: Long = 0L

    @Synchronized
    private fun serviceToken(): String {
        val now = System.currentTimeMillis() / 1000L
        val cached = cachedToken
        if (cached != null && tokenExpiresAt - now > TOKEN_REFRESH_BUFFER_SECONDS) return cached
        return fetchToken().also { (token, expiry) ->
            cachedToken = token
            tokenExpiresAt = now + expiry
        }.first
    }

    private fun fetchToken(): Pair<String, Long> {
        val tokenUrl = "$tokenEndpointBase/protocol/openid-connect/token"
        val body = "grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Token endpoint returned ${response.statusCode()}: ${response.body()}"
        }
        val responseBody = response.body()
        val tree = JSON.readTree(responseBody)
        val token = tree.get("access_token")?.asText()
            ?: error("Token endpoint response missing access_token")
        val expiresIn = tree.get("expires_in")?.asLong() ?: 300L
        return token to expiresIn
    }

    /** Preserve the one response header that is durable evidence rather than proxy metadata. */
    private fun jsonResponse(response: HttpResponse<String>): Response {
        val builder = Response.status(response.statusCode())
            .entity(response.body())
            .type(MediaType.APPLICATION_JSON)
        response.headers().firstValue(IDEMPOTENCY_REPLAY_HEADER).ifPresent { replayed ->
            builder.header(IDEMPOTENCY_REPLAY_HEADER, replayed)
        }
        return builder.build()
    }

    fun get(url: String, partyId: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET().build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * GET that preserves the upstream's Content-Type instead of forcing JSON — for non-JSON bodies
     * such as rendered statement documents (camt.053 `application/xml`, MT940/PDF `text/plain`). The
     * caller supplies the [accept] media type; the response body is streamed back with the upstream's
     * own Content-Type (falling back to octet-stream). Errors degrade to a JSON 502 like [get].
     *
     * The body is read as a [ByteArray] (not a String): text bodies (camt.053 XML, MT940) are
     * unaffected, but a real binary `application/pdf` would be corrupted by `ofString()`'s charset
     * decode/encode round-trip. JAX-RS writes a `ByteArray` entity verbatim, so the bytes pass
     * through unchanged regardless of the media type.
     */
    fun getRaw(url: String, partyId: String, accept: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Accept", accept)
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET().build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val contentType = r.headers().firstValue("content-type").orElse(MediaType.APPLICATION_OCTET_STREAM)
        Response.status(r.statusCode()).entity(r.body()).type(contentType).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * POST to an upstream service with M2M operator token but WITHOUT a party-id header.
     * Used for onboarding routes where the party does not yet exist (e.g. party creation).
     *
     * Sends an Idempotency-Key: party-service's POST /api/v1/parties declares it as a
     * required @HeaderParam, so a missing key is rejected with an empty-body 400. The edge
     * generates one per call (each onboarding attempt is a distinct party); a future
     * enhancement can let the mobile client supply a stable key for safe retries.
     */
    fun postAnonymous(url: String, body: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /** PATCH (no body) with the M2M token + party header — e.g. mark a notification read. */
    fun patch(url: String, partyId: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()).build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /** PATCH with a JSON body + M2M token + party header — e.g. update marketing consent. */
    fun patch(url: String, partyId: String, body: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /** PUT with a JSON body + M2M token + party header — e.g. set a savings goal (ADR-0153). */
    fun put(url: String, partyId: String, body: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /** PUT with the M2M operator token + party header + extra headers (e.g. X-Operator-Id). */
    fun put(
        url: String,
        partyId: String,
        body: String,
        idempotencyKey: String?,
        extraHeaders: Map<String, String>,
    ): Response = try {
        val builder = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val r = http.send(
            builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream PUT to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""").type(MediaType.APPLICATION_JSON).build()
    }

    /** DELETE with the M2M operator token + party header (e.g. cancel a standing order). */
    fun delete(url: String, partyId: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .DELETE().build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    /** DELETE carrying a JSON body (e.g. consent revoke, which requires a { reason }). */
    fun delete(url: String, partyId: String, body: String): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        Response.status(r.statusCode()).entity(r.body()).type(MediaType.APPLICATION_JSON).build()
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    // Idempotency-aware POST: forwards the caller's Idempotency-Key (required by some upstreams,
    // e.g. domestic-payment) so an app retry replays rather than duplicates. A blank/absent key
    // falls back to a generated one so the upstream contract is always satisfied.
    fun post(url: String, partyId: String, body: String, idempotencyKey: String? = null): Response = try {
        val request = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val r = http.send(request, HttpResponse.BodyHandlers.ofString())
        jsonResponse(r)
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }

    // POST with the M2M token + party header plus caller-supplied extra headers (e.g. the
    // X-Operator-Id that card-issuance requires for an audit trail). Body may be empty. The
    // extra headers are applied last so a real value is never silently dropped.
    fun post(
        url: String,
        partyId: String,
        body: String,
        idempotencyKey: String?,
        extraHeaders: Map<String, String>,
    ): Response = try {
        val builder = HttpRequest.newBuilder()
            .uri(validatedUri(url))
            .header("Authorization", "Bearer ${serviceToken()}")
            .header(PARTY_HEADER, partyId)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        jsonResponse(r)
    } catch (e: Exception) {
        Log.error("upstream call to $url failed: ${e::class.qualifiedName}: ${e.message}", e)
        Response.status(502).entity("""{"error":"upstream unavailable"}""")
            .type(MediaType.APPLICATION_JSON).build()
    }
}
