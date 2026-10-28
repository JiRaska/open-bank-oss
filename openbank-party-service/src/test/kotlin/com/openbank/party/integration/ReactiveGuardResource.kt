// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.core.Context
import io.vertx.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

@ApplicationScoped
@Alternative
class ReactiveGuardPdp : PolicyDecisionPoint {
    @Inject
    lateinit var identity: SecurityIdentity

    var started = CompletableFuture<AuthzQuery>()
    var decision = CompletableDeferred<Boolean>()
    var originalContext: Context? = null
    val invocations = AtomicInteger()

    fun reset() {
        started = CompletableFuture()
        decision = CompletableDeferred()
        originalContext = null
        invocations.set(0)
    }

    override suspend fun allow(query: AuthzQuery): AuthzDecision {
        originalContext = Vertx.currentContext()
        started.complete(query)
        val allow = decision.await()
        // Reading the request-scoped proxy after suspension must still resolve this request.
        check(identity.principal.name == query.principal.id)
        check(Vertx.currentContext() === originalContext)
        return AuthzDecision(allow = allow, reason = "test-decision")
    }
}

/** Test-only routes exercise generated ArC interceptors rather than a mocked InvocationContext. */
@Path("/test/reactive-guard")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class ReactiveGuardResource {
    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var headers: HttpHeaders

    @Inject
    lateinit var pdp: Instance<ReactiveGuardPdp>

    @GET
    @Path("/uni")
    @Authorize(action = "party.test", attributes = ["idempotency-key"])
    fun uni(): Uni<Map<String, Any>> = Uni.createFrom().item(snapshot())

    @GET
    @Path("/stage")
    @Authorize(action = "party.test", attributes = ["idempotency-key"])
    fun stage(): CompletionStage<Map<String, Any>> = CompletableFuture.completedFuture(snapshot())

    @GET
    @Path("/heartbeat")
    fun heartbeat(): Uni<Map<String, Boolean>> = Uni.createFrom().item(mapOf("alive" to true))

    private fun snapshot(): Map<String, Any> {
        val decisionPoint = pdp.get()
        decisionPoint.invocations.incrementAndGet()
        return mapOf(
            "caller" to identity.principal.name,
            "marker" to headers.getHeaderString("Idempotency-Key"),
            "sameContext" to (Vertx.currentContext() === decisionPoint.originalContext),
            "eventLoop" to Context.isOnEventLoopThread(),
        )
    }
}
