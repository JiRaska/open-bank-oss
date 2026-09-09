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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Broker-side provider verification for document-service — the published-result counterpart to
 * [DocumentPactProviderVerificationTest], and the same gap #1009 closed for ledger-service.
 *
 * **The defect this fixes, measured 2026-08-31.** `@PactFolder` reads pacts off disk. It never
 * contacts the broker, so it publishes no verification result and creates no provider version.
 * document-service had only that half, so nothing it did ever reached the broker:
 *
 * ```
 * newest broker version for openbank-document-service : ee974ea3c2c7, 2026-08-07
 * document-service source commits since that date      : 15
 * version actually running in sandbox (3b62a4a5…)      : HTTP 404 — absent from the broker
 * broker's currently-deployed record                   : ee974ea3, which carries ZERO pacts
 * ```
 *
 * A version row with zero pacts makes `can-i-deploy` *unanswerable* rather than negative, so all
 * three consumers of this provider — domestic-payment, sepa-payment and statement-service — resolve
 * `UNVERIFIABLE`. Two of those are money-path, which is what failed the auto-deploy reconcile job
 * and left services stranded on stale images (issue #7621).
 *
 * **Why a second `@Provider` class is safe here.** CLAUDE.md warns that two broker-sourced
 * `@Provider` classes for one provider collide, because each fetches every pact the broker holds.
 * That is not this: the sibling is `@PactFolder`-sourced, which is the sanctioned pair rather than
 * the footgun. Both use [HttpTestTarget] exclusively — document-service has no message-consumer
 * contracts — so there is no HTTP-vs-MESSAGE dispatch fighting over `@BeforeEach`.
 *
 * **Gating.** `@EnabledIfSystemProperty(pactbroker.url)` keeps this skipped locally and on the PR
 * lane, where `_service-ci.yml` blanks `PACT_BROKER_URL` because the broker has no public ingress
 * (ADR-0056). It runs on main-push, where the broker properties are injected. The `@PactFolder`
 * sibling stays ungated and unaffected, so PR-time protection against a wrong request path — the
 * load-bearing half, issue #2338 — is unchanged.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-document-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DocumentPactBrokerProviderVerificationTest {

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
     * Empty for the same reason as the sibling class: `DocumentTemplateSeeder` runs on every boot
     * (`@Observes StartupEvent`) and inserts the six canonical templates, so the fresh Testcontainer
     * database already satisfies this state. Seeding again would assert the fixture, not the provider.
     */
    @State("the canonical document templates are seeded and published")
    fun stateTemplatesSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * `previewTemplate` is stateless and non-persisting by design (ADR-0248 #3): it merges a
     * caller-supplied `bodyHtml` with a caller-supplied data map through Handlebars and returns the
     * result. No `Document` row, no outbox event, nothing to set up.
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
