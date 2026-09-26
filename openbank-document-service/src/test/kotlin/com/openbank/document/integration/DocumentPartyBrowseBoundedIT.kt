// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.integration

import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.infrastructure.persistence.repository.DocumentRepositoryImpl
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The browse contract is BOUNDED (#8082).
 *
 * `GET /api/v1/documents?partyRef=` returned every document a party had, in one response, with no
 * page parameter to ask for less. A party's document count is not bounded by anything the service
 * controls — it grows with every rendered statement and agreement — so the response size was a
 * function of how long the customer had been a customer.
 *
 * Why this is an IT and not a unit test: the bound is in the SQL (`.page(...)` on a Panache query),
 * so a mocked repository returns whatever the mock was told to return and the test agrees with
 * itself. Only a real database can show that the query asked for fewer rows than exist. The seeded
 * count deliberately exceeds the default page size, because a test seeded with less than one page
 * passes identically against the unbounded code.
 *
 * Each assertion below fails against the pre-fix endpoint: it answered 200 with all 55 documents
 * and none of the three headers.
 */
@QuarkusTest
@QuarkusTestResource(DocumentPartyBrowseBoundedIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DocumentPartyBrowseBoundedIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("document-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var documents: DocumentRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    /** Unique per run so a re-run against a reused database does not accumulate rows. */
    private val partyRef = "party-browse-${UUID.randomUUID()}"

    @BeforeEach
    fun seed() {
        onVertxContext {
            repeat(SEEDED) { i ->
                val id = Ids.newId()
                documents.save(
                    Document(
                        id = id,
                        templateCode = "UCET_SMLOUVA_CS",
                        templateVersion = "1.0.0",
                        sha256 = "a".repeat(64),
                        storageKey = "documents/$id",
                        contentType = "application/pdf",
                        sizeBytes = 10,
                        status = DocumentStatus.GENERATED,
                        metadata = emptyMap(),
                        partyRef = partyRef,
                        caseRef = "case-$i",
                        productRef = "prod-1",
                        retainUntil = null,
                        // Distinct instants so "newest first" is a total order the assertions can rely on.
                        createdAt = Instant.now().minusSeconds((SEEDED - i).toLong()),
                        idempotencyKey = null,
                    ),
                )
            }
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a party with more documents than one page gets ONE page, and is told the true total`() {
        Given {
            queryParam("partyRef", partyRef)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(200)
            // The bound itself: 55 rows exist, the default page is 50.
            body("size()", org.hamcrest.Matchers.equalTo(DEFAULT_PAGE))
            // Without this the caller cannot tell a full page from the whole set — the two
            // render identically and mean opposite things to whoever is judging completeness.
            header("X-Total-Count", SEEDED.toString())
            header("X-Page", "0")
            header("X-Page-Size", DEFAULT_PAGE.toString())
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `pages partition the set - the last page holds the remainder, not a full page`() {
        Given {
            queryParam("partyRef", partyRef)
            queryParam("size", 10)
            queryParam("page", 0)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.equalTo(10))
            header("X-Page-Size", "10")
        }

        // 55 rows at 10 per page: pages 0..4 are full, page 5 holds the remaining 5. A page index
        // past the end must be empty rather than an error or a wrapped-around first page.
        Given {
            queryParam("partyRef", partyRef)
            queryParam("size", 10)
            queryParam("page", 5)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.equalTo(SEEDED - 50))
            header("X-Total-Count", SEEDED.toString())
        }

        Given {
            queryParam("partyRef", partyRef)
            queryParam("size", 10)
            queryParam("page", 99)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.equalTo(0))
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `an oversized page size is CLAMPED, not honoured - the caller cannot ask for unbounded work`() {
        Given {
            queryParam("partyRef", partyRef)
            queryParam("size", 100_000)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(200)
            // Clamped to the 200 ceiling. The body is all 55 rows only because 55 < 200 — the
            // header is what proves the requested 100000 was refused.
            header("X-Page-Size", "200")
            body("size()", org.hamcrest.Matchers.equalTo(SEEDED))
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a negative page is a 400, not a silently coerced first page`() {
        Given {
            queryParam("partyRef", partyRef)
            queryParam("page", -1)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a missing partyRef is still a 400, not a 500 - the nullable param guard survives paging`() {
        // The parameter is declared String? on purpose: a non-nullable @QueryParam is injected null
        // by JAX-RS and fails as a 500 before the body runs. Adding two Int params next to it must
        // not have disturbed that.
        Given { this } When {
            get("/api/v1/documents")
        } Then {
            statusCode(400)
        }
    }

    private companion object {
        /** More than one default page, so the bound is observable. */
        const val SEEDED = 55
        const val DEFAULT_PAGE = 50
    }
}
