// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryPort
import com.openbank.kyb.application.port.out.RegistryFailure
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Reactive REST client for the monitored-subject registry of `openbank-sanctions-service`
 * (ADR-0376 D1). Same host, TLS bucket and timeouts as the screening client (`sanctions-service`
 * config key), but a different caller: the registry takes the OWNER of a registration from the
 * client the token was issued to and refuses the fleet's shared client, so this interface selects
 * the service's own named client (`m2m`, `openbank-kyb`). The screening client next to it still
 * rides the default one; a registration sent through that would be answered 403 every time.
 */
@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v3/sanctions/monitored-subjects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsMonitoringRestClient {

    @PUT
    @Path("/{subjectRef}")
    suspend fun register(@PathParam("subjectRef") subjectRef: String, body: MonitoredSubjectBody): MonitoredSubjectAck

    @DELETE
    @Path("/{subjectRef}")
    suspend fun withdraw(@PathParam("subjectRef") subjectRef: String)
}

/**
 * The registration request, limited to what the onboarding screen of the same subject sends:
 * entity type and name. Nothing else about a person leaves this service for the registry.
 */
data class MonitoredSubjectBody(val entityType: String, val name: String)

/** The part of the registry's answer this service acts on. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MonitoredSubjectAck(val ownerService: String? = null, val subjectRef: String? = null)

/**
 * Adapter for the monitored-subject registry. Every failure — a refusal, a transport fault, a
 * timeout, an answer about a different subject — surfaces as [MonitoredSubjectRegistryException]
 * with a short machine reason, and nothing here retries: the relay owns retry, with backoff, over
 * a durable row. Nothing here turns a failure into a delivery either.
 *
 * No fault-tolerance annotation on purpose. An interceptor's timeout would be thrown AROUND this
 * class, past the translation below, and reach the relay as an exception it does not expect.
 */
@ApplicationScoped
class SanctionsMonitoredSubjectRegistryAdapter : MonitoredSubjectRegistryPort {

    @Inject @RestClient
    lateinit var client: SanctionsMonitoringRestClient

    override suspend fun register(subjectRef: String, organisation: Boolean, name: String) = guarded {
        val ack = client.register(
            subjectRef,
            MonitoredSubjectBody(entityType = if (organisation) "ORGANIZATION" else "INDIVIDUAL", name = name),
        )
        // A 2xx about another subject is not a registration of this one.
        if (ack.subjectRef != subjectRef) {
            throw MonitoredSubjectRegistryException(REASON_UNEXPECTED, RegistryFailure.TRANSIENT, null)
        }
    }

    override suspend fun withdraw(subjectRef: String) = guarded { client.withdraw(subjectRef) }

    // Deliberately broad: the relay must not have to enumerate which failure types mean "not delivered".
    // The bound on the call is the REST client's own connect and read timeout, so a call that hangs
    // ends INSIDE this function and is reported like every other failure.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            throw e as? MonitoredSubjectRegistryException ?: failureOf(e)
        }
    }

    /**
     * What the relay must do is decided here, by status: 401/403 is this caller not being
     * accepted, 408/429 and everything that is not an HTTP answer may pass on its own, and any
     * other 4xx is the registry refusing this subject as it is. 5xx is transient.
     */
    private fun failureOf(e: Exception): MonitoredSubjectRegistryException {
        val status = (e as? WebApplicationException)?.response?.status
            ?: return MonitoredSubjectRegistryException(REASON_UNAVAILABLE, RegistryFailure.TRANSIENT, e)
        val kind = when (status) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> RegistryFailure.UNAUTHORISED
            HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS -> RegistryFailure.TRANSIENT
            in CLIENT_ERRORS -> RegistryFailure.REFUSED
            else -> RegistryFailure.TRANSIENT
        }
        return MonitoredSubjectRegistryException("http_$status", kind, e)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        val CLIENT_ERRORS = 400..499
        const val REASON_UNAVAILABLE = "unavailable"
        const val REASON_UNEXPECTED = "unexpected_response"
    }
}
