// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RateLimitDisconnectProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.rate-limit.max-concurrent-requests" to "2",
        "quarkus.scheduler.enabled" to "false",
    )
}

@Path("/rate-limit-disconnect-test")
@Produces(MediaType.TEXT_PLAIN)
class RateLimitDisconnectResource {
    @GET
    @Path("/hold")
    fun hold(): Uni<String> {
        val completion = CompletableFuture<String>()
        pending.add(completion)
        return Uni.createFrom().completionStage(completion)
    }

    @GET
    @Path("/ready")
    @Suppress("FunctionOnlyReturningConstant")
    fun ready(): String = "ready"

    companion object {
        val pending = CopyOnWriteArrayList<CompletableFuture<String>>()
    }
}

@QuarkusTest
@TestProfile(RateLimitDisconnectProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class RateLimitDisconnectIT {
    @Test
    @TestSecurity(user = "synthetic-rate-limit-user", roles = ["ROLE_COMPLIANCE"])
    fun `disconnects restore capacity and late completion cannot release twice`() {
        val uri = java.net.URI(RestAssured.baseURI)
        val sockets = mutableListOf<Socket>()
        try {
            repeat(3) {
                val entered = RateLimitDisconnectResource.pending.size
                repeat(2) {
                    val socket = Socket(uri.host, RestAssured.port)
                    sockets.add(socket)
                    socket.getOutputStream().write(
                        "GET /rate-limit-disconnect-test/hold HTTP/1.1\r\nHost: localhost\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                    socket.getOutputStream().flush()
                }
                await { RateLimitDisconnectResource.pending.size == entered + 2 }
                RestAssured.get("/rate-limit-disconnect-test/ready").then().statusCode(429)
                sockets.forEach { it.close() }
                sockets.clear()
                await {
                    RestAssured.get("/rate-limit-disconnect-test/ready").statusCode == 200
                }
            }
            RateLimitDisconnectResource.pending.forEach { it.complete("finished") }
            RestAssured.get("/rate-limit-disconnect-test/ready").then().statusCode(200)
            val entered = RateLimitDisconnectResource.pending.size
            repeat(2) {
                val socket = Socket(uri.host, RestAssured.port)
                sockets.add(socket)
                socket.getOutputStream().write(
                    "GET /rate-limit-disconnect-test/hold HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
            }
            await { RateLimitDisconnectResource.pending.size == entered + 2 }
            RestAssured.get("/rate-limit-disconnect-test/ready").then().statusCode(429)
        } finally {
            sockets.forEach { it.close() }
            RateLimitDisconnectResource.pending.forEach { it.complete("finished") }
            RateLimitDisconnectResource.pending.clear()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(condition()).isTrue()
    }
}
