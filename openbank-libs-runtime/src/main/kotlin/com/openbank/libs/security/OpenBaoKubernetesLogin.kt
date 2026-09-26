// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * OpenBao Kubernetes-auth login, lifted from card-issuance's `OpenBaoTransitDekUnwrapper.login`
 * (ADR-0262) so an [OpenBaoTransitFieldProtector] can use `OpenBaoKubernetesLogin(...)::login` as
 * its token supplier. Plain class, not a CDI bean (ADR-0320 rule 1). Logs nothing; the service
 * account JWT and the returned token never appear in an exception message.
 */
class OpenBaoKubernetesLogin(
    private val baoAddr: URI,
    private val role: String,
    private val saTokenPath: Path = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token"),
    private val requestTimeout: Duration = Duration.ofSeconds(
        OpenBaoTransitFieldProtector.DEFAULT_REQUEST_TIMEOUT_SECONDS,
    ),
    private val httpClient: HttpClient = OpenBaoTransitFieldProtector.defaultHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    fun login(): String {
        val response = send()
        if (response.statusCode() != HTTP_OK) {
            throw FieldProtectionException("OpenBao kubernetes-auth login failed: HTTP ${response.statusCode()}")
        }
        val token = try {
            objectMapper.readTree(response.body())?.get("auth")?.get("client_token")?.takeIf { it.isTextual }?.asText()
        } catch (e: IOException) {
            throw FieldProtectionException("OpenBao kubernetes-auth login returned malformed JSON", e)
        }
        return token ?: fail("OpenBao kubernetes-auth login returned no client_token")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun send(): HttpResponse<String> = try {
        val jwt = Files.readString(saTokenPath).trim()
        val body = objectMapper.writeValueAsString(mapOf("role" to role, "jwt" to jwt))
        val request = HttpRequest.newBuilder()
            .uri(baoAddr.resolve("/v1/auth/kubernetes/login"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FieldProtectionException("OpenBao kubernetes-auth login interrupted", e)
    } catch (e: Exception) {
        throw FieldProtectionException("OpenBao kubernetes-auth login failed: ${e.javaClass.simpleName}", e)
    }

    private fun fail(message: String): Nothing = throw FieldProtectionException(message)

    private companion object {
        const val HTTP_OK = 200
    }
}
