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
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Jackson's Kotlin module null-checks a data class's CONSTRUCTOR PARAMETERS; it does not check the
 * ELEMENTS of a collection. So `{"signerPartyRefs": [null]}` deserialises happily into a
 * `List<String>` holding a null, the null reaches the use case, and the endpoint answers 500 where
 * 400 belongs.
 *
 * The document is seeded first on purpose: `openCeremony` looks the document up before it maps the
 * signers, so against a documentId that does not exist the request never reaches the dereference
 * and the test would be red for the wrong reason.
 */
@QuarkusTest
@QuarkusTestResource(OpenCeremonyNullSignerElementIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class OpenCeremonyNullSignerElementIT {

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

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a null element in signerPartyRefs is rejected with 400, not 500`() {
        val documentId: UUID = Ids.newId()
        onVertxContext {
            documents.save(
                Document(
                    id = documentId,
                    templateCode = "UCET_SMLOUVA_CS",
                    templateVersion = "1.0.0",
                    sha256 = "a".repeat(64),
                    storageKey = "documents/$documentId",
                    contentType = "application/pdf",
                    sizeBytes = 10,
                    status = DocumentStatus.GENERATED,
                    metadata = emptyMap(),
                    partyRef = "party-1",
                    caseRef = "acc-1",
                    productRef = "prod-1",
                    retainUntil = null,
                    createdAt = Instant.now(),
                    idempotencyKey = "null-signer-element-$documentId",
                ),
            )
        }

        Given {
            contentType(ContentType.JSON)
            body("""{"documentId":"$documentId","signerPartyRefs":[null]}""")
        } When {
            post("/api/v1/signature-ceremonies")
        } Then {
            statusCode(400)
        }
    }
}
