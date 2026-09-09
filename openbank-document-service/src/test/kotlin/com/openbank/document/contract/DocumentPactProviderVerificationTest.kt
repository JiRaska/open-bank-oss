// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.document.application.port.out.DisclosureSnapshotRepository
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.DisclosureSnapshot
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.storage.ObjectStorePort
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Provider-side Pact verification for the contracts consumers publish against
 * `openbank-document-service`. `@PactFolder` replays EVERY pact in `../pacts` naming this provider,
 * so the set below is what is committed today rather than a list this class maintains — all three
 * merged services holding a live synchronous REST client against document-service, each making the
 * same two calls (ADR-0248 #3): `GET /api/v1/documents/templates` then
 * `POST /api/v1/documents/templates/preview`.
 *
 *  - `openbank-sepa-payment` — `SepaPaymentDocumentServicePactConsumerTest` (#4299)
 *  - `openbank-domestic-payment` — `DomesticPaymentDocumentServicePactConsumerTest`
 *  - `openbank-statement-service` — `StatementDocumentServicePactConsumerTest`
 *
 * All three declare the same two provider states, which is why adding them cost no new
 * `@State` handler: the states are properties of the provider (seeded templates, a stateless
 * renderer), not of any one consumer. Deliberately ONE class — a second broker-sourced `@Provider`
 * class for the same provider collides, since each fetches every pact the broker holds.
 *
 * **Why this class is the load-bearing half.** A consumer pact ALONE cannot catch a wrong request
 * path: the Pact mock server answers whatever path the client asks for, so pointing a client at a
 * route that does not exist leaves the consumer test green. Only replaying the committed pact
 * against the real, running provider goes red — the #2269 lesson. sepa-payment's pre-existing cover
 * for these two calls was `DocumentServiceWireMockResource`, a consumer-authored stub that
 * hard-codes the same paths the client sends, and a stub written from the client cannot disagree
 * with it.
 *
 * `@PactFolder("../pacts")` and UNGATED, on purpose. A `@PactBroker` class would not do: the PR
 * lane blanks `PACT_BROKER_URL` (the broker has no public ingress, ADR-0056), so an
 * `@EnabledIfSystemProperty(pactbroker.url)` gate skips and the contract would be replayed only
 * after the merge — the state 16 of 27 pacts were in when #2327 was measured.
 * `check-pact-provider-replay.py` (gate `pact-provider-replay-coverage`) enforces exactly this
 * shape.
 *
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact folder a skip rather
 * than a failure, matching every other `@PactFolder` provider class in the fleet.
 *
 * IMPORTANT: if any of the three consumer tests changes the contract, regenerate that consumer's
 * pact (`./gradlew :<consumer-module>:test --rerun --tests "*.contract.*PactConsumerTest"`) and
 * commit that consumer's updated `<consumer>-openbank-document-service.json` under `pacts` in the
 * same PR (spelled without a leading slash-star on purpose: Kotlin block comments NEST, so a glob
 * written as a path prefix inside a KDoc opens a comment that never closes), or this test replays
 * a stale contract. `pact-drift-check.yml` enforces it over a scope derived from the `@Pact`
 * annotations, so a new consumer module is in scope automatically.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-document-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DocumentPactProviderVerificationTest {

    private companion object {
        val DISCLOSURE_DOCUMENT_ID: UUID = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb")
        const val DISCLOSURE_OWNER_ID = "88888888-9999-4aaa-8bbb-cccccccccccc"
        val DISCLOSURE_SNAPSHOT_ID: UUID = UUID.fromString("99999999-aaaa-4bbb-8ccc-dddddddddddd")
        val DISCLOSURE_REQUEST_ID: UUID = UUID.fromString("99999999-aaaa-4bbb-8ccc-eeeeeeeeeeee")
        const val DISCLOSURE_PDF = "%PDF-1.7 sealed disclosure"
        const val DISCLOSURE_SHA256 = "397f16a0e617d2898c400f7c9db43b117395f2518b348d563b603d8aa35f6399"
        const val DISCLOSURE_STORAGE_KEY = "pact/disclosure-snapshot.pdf"
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var documents: DocumentRepositoryPort

    @Inject
    lateinit var snapshots: DisclosureSnapshotRepository

    @Inject
    lateinit var objectStore: ObjectStorePort

    @Inject
    lateinit var vertx: Vertx

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * No seeding needed, and that is a property of the provider rather than an omission:
     * `DocumentTemplateSeeder` runs on EVERY boot (`@Observes StartupEvent`) and inserts the six
     * canonical templates — including the `POTVRZENI_O_PLATBE_CS`/`_EN` payment confirmations this
     * contract is about — into whatever database the service starts against, so the fresh
     * Testcontainer DB this test boots on already satisfies the state. Seeding again through the
     * repository would only assert that the fixture works.
     */
    @State("the canonical document templates are seeded and published")
    fun stateTemplatesSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * `previewTemplate` is stateless and non-persisting by design (ADR-0248 #3): it merges a
     * caller-supplied `bodyHtml` with a caller-supplied data map through Handlebars and returns
     * the result. No `Document` row, no outbox event, nothing to set up.
     */
    @State("the template preview renderer is available")
    fun statePreviewRendererAvailable() {
        // Intentionally empty — see the KDoc above.
    }

    @State("a document owned by a known party exists")
    fun stateOwnedDocumentExists() = runOnVertxContext {
        documents.save(disclosureDocument())
    }

    @State("an immutable disclosure snapshot with the expected digest exists")
    fun stateDisclosureSnapshotExists() = seedDisclosureSnapshot()

    /** Existing snapshot plus a different pinned digest must replay as 404 to prevent enumeration. */
    @State("an immutable disclosure snapshot exists but the supplied digest is wrong")
    fun stateDisclosureSnapshotWithWrongDigestExists() = seedDisclosureSnapshot()

    private fun seedDisclosureSnapshot() = runOnVertxContext {
        val bytes = DISCLOSURE_PDF.toByteArray()
        objectStore.put(DISCLOSURE_STORAGE_KEY, bytes, "application/pdf")
        snapshots.createOrFind(
            DisclosureSnapshot(
                id = DISCLOSURE_SNAPSHOT_ID,
                requestId = DISCLOSURE_REQUEST_ID,
                sourceDocumentId = DISCLOSURE_DOCUMENT_ID,
                partyRef = DISCLOSURE_OWNER_ID,
                sourceSha256 = DISCLOSURE_SHA256,
                sha256 = DISCLOSURE_SHA256,
                storageKey = DISCLOSURE_STORAGE_KEY,
                contentType = "application/pdf",
                sizeBytes = bytes.size.toLong(),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }

    private fun disclosureDocument() = Document(
        id = DISCLOSURE_DOCUMENT_ID,
        templateCode = "DISCLOSURE_PACT",
        templateVersion = "1",
        sha256 = "0".repeat(64),
        storageKey = "pact/disclosure.pdf",
        contentType = "application/pdf",
        sizeBytes = 1,
        status = DocumentStatus.SIGNED,
        metadata = emptyMap(),
        partyRef = DISCLOSURE_OWNER_ID,
        caseRef = null,
        productRef = null,
        retainUntil = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun runOnVertxContext(block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val context = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(context, true)
        val dispatcher = Executor { command -> context.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(10, TimeUnit.SECONDS)
    }
}
