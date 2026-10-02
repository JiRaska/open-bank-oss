// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestFilter
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Semaphore

/** Only the owning service's current case identity and status cross this boundary. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AmlCaseSourceSnapshot(val id: UUID? = null, val status: String? = null)

@RegisterRestClient(configKey = "aml-service")
@RegisterProvider(AmlUserTokenPropagationFilter::class)
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/aml/cases")
@Produces(MediaType.APPLICATION_JSON)
interface AmlCaseSourceClient {
    @GET
    @Path("/{id}")
    fun getCase(@PathParam("id") id: UUID): Uni<AmlCaseSourceSnapshot>
}

/** AML evaluates the investigator's own token, including its current source-side policy. */
@ApplicationScoped
class AmlUserTokenPropagationFilter(private val identity: SecurityIdentity) : ResteasyReactiveClientRequestFilter {
    override fun filter(requestContext: ResteasyReactiveClientRequestContext) {
        val token = (identity.principal as? JsonWebToken)?.rawToken ?: return
        requestContext.headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}

/** Bounded, uncached source check; historical observations never decide current case eligibility. */
@ApplicationScoped
class AmlCaseSourceStatus(@param:RestClient private val client: AmlCaseSourceClient) {
    private val inFlight = Semaphore(MAX_INFLIGHT)

    suspend fun isOpen(id: UUID): Boolean {
        if (!inFlight.tryAcquire()) throw AmlCaseSourceUnavailable()
        try {
            return readSource(id)
        } finally {
            inFlight.release()
        }
    }

    // REST, timeout and decoding errors share a fail-closed boundary.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun readSource(id: UUID): Boolean {
        val snapshot = try {
            client.getCase(id).ifNoItem().after(Duration.ofMillis(TIMEOUT_MS)).fail().awaitSuspending()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: WebApplicationException) {
            if (exception.response.status == NOT_FOUND) return false
            throw AmlCaseSourceUnavailable(exception)
        } catch (exception: Exception) {
            throw AmlCaseSourceUnavailable(exception)
        }
        if (snapshot == null || snapshot.id != id || snapshot.status !in KNOWN_STATUSES) {
            throw AmlCaseSourceUnavailable()
        }
        return snapshot.status in OPEN_STATUSES
    }

    private companion object {
        const val TIMEOUT_MS = 1500L
        const val NOT_FOUND = 404
        const val MAX_INFLIGHT = 16
        val OPEN_STATUSES = setOf("OPEN", "UNDER_REVIEW", "ESCALATED")
        val KNOWN_STATUSES = OPEN_STATUSES + setOf("CLEARED", "BLOCKED")
    }
}

class AmlCaseSourceUnavailable(cause: Throwable? = null) : RuntimeException("AML source status is unavailable", cause)
