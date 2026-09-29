// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * OpenBao Kubernetes-auth login, lifted from card-issuance's `OpenBaoTransitDekUnwrapper.login`
 * (ADR-0262). Plain class, not a CDI bean (ADR-0320 rule 1). Logs nothing; the service account JWT
 * and the returned token never appear in an exception message.
 *
 * Do NOT hand `login::login` to a protector directly — that logs in on every Transit call. Wrap it:
 * `OpenBaoTransitFieldProtector(transport, key, CachingOpenBaoTokenSource(login::login), pepper)`,
 * which caches the token for its `auth.lease_duration` and re-logs-in once on a 403.
 *
 * @param authMount the Kubernetes auth mount path (default `kubernetes`).
 */
class OpenBaoKubernetesLogin(
    private val transport: OpenBaoTransport,
    private val role: String,
    private val saTokenPath: Path = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token"),
    private val authMount: String = "kubernetes",
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    init {
        require(MOUNT.matches(authMount)) { "invalid auth mount" }
    }

    fun login(): OpenBaoToken {
        val response = send()
        if (response.statusCode() != HTTP_OK) {
            throw FieldProtectionException("OpenBao kubernetes-auth login failed: HTTP ${response.statusCode()}")
        }
        val auth = try {
            objectMapper.readTree(response.body())?.get("auth")
        } catch (e: IOException) {
            throw FieldProtectionException("OpenBao kubernetes-auth login returned malformed JSON", e)
        }
        val token = auth?.get("client_token")?.takeIf { it.isTextual }?.asText()
            ?: fail("OpenBao kubernetes-auth login returned no client_token")
        val lease = auth.get("lease_duration")?.takeIf { it.canConvertToLong() }?.asLong() ?: 0L
        return OpenBaoToken(token, Duration.ofSeconds(lease))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun send(): HttpResponse<String> = try {
        val jwt = Files.readString(saTokenPath).trim()
        val body = objectMapper.writeValueAsString(mapOf("role" to role, "jwt" to jwt))
        val request = HttpRequest.newBuilder()
            .uri(transport.uri("v1/auth/$authMount/login"))
            .timeout(transport.requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        transport.httpClient.send(request, OpenBaoTransport.boundedBody())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FieldProtectionException("OpenBao kubernetes-auth login interrupted", e)
    } catch (e: Exception) {
        throw FieldProtectionException("OpenBao kubernetes-auth login failed: ${e.javaClass.simpleName}", e)
    }

    private fun fail(message: String): Nothing = throw FieldProtectionException(message)

    private companion object {
        const val HTTP_OK = 200
        val MOUNT = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}(/[A-Za-z0-9][A-Za-z0-9_-]{0,63}){0,3}$")
    }
}
