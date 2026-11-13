// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryPort
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
import org.eclipse.microprofile.faulttolerance.Timeout
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
 */
@ApplicationScoped
class SanctionsMonitoredSubjectRegistryAdapter : MonitoredSubjectRegistryPort {

    @Inject @RestClient
    lateinit var client: SanctionsMonitoringRestClient

    @Timeout(TIMEOUT_MS)
    override suspend fun register(subjectRef: String, organisation: Boolean, name: String) = guarded {
        val ack = client.register(
            subjectRef,
            MonitoredSubjectBody(entityType = if (organisation) "ORGANIZATION" else "INDIVIDUAL", name = name),
        )
        // A 2xx about another subject is not a registration of this one.
        if (ack.subjectRef != subjectRef) throw MonitoredSubjectRegistryException(REASON_UNEXPECTED, null)
    }

    @Timeout(TIMEOUT_MS)
    override suspend fun withdraw(subjectRef: String) = guarded { client.withdraw(subjectRef) }

    // Deliberately broad: the relay must not have to enumerate which failure types mean "not delivered".
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: MonitoredSubjectRegistryException) {
            throw e
        } catch (e: WebApplicationException) {
            throw MonitoredSubjectRegistryException("http_${e.response?.status ?: 0}", e)
        } catch (e: Exception) {
            throw MonitoredSubjectRegistryException(REASON_UNAVAILABLE, e)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val REASON_UNAVAILABLE = "unavailable"
        const val REASON_UNEXPECTED = "unexpected_response"
    }
}
