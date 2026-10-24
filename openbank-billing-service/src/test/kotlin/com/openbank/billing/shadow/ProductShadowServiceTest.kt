// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.shadow

import com.openbank.billing.application.port.out.CatalogRevisionIrPort
import com.openbank.billing.application.port.out.CatalogRevisionMismatchException
import com.openbank.billing.application.port.out.CatalogRevisionUnavailableException
import com.openbank.billing.application.port.out.ProductShadowQueuePort
import com.openbank.billing.application.port.out.QueuedShadowInput
import com.openbank.billing.application.port.out.RevisionIrCopyPort
import com.openbank.billing.application.port.out.StoredRevisionIr
import com.openbank.billing.application.usecase.FeeShadowComparator
import com.openbank.billing.application.usecase.ProductShadowService
import com.openbank.billing.domain.ProductShadowInput
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.IrSigningException
import com.openbank.libs.productrevision.shadow.ProductShadowRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ProductShadowServiceTest {

    private val registry = SimpleMeterRegistry()
    private val signer = HmacIrSigner(ShadowFixtures.SECRET.toByteArray(), "product-ir-publish")
    private val hash = ProductIrParser.parse(ShadowFixtures.IR).revisionHash

    private class Queue(vararg inputs: ProductShadowInput) : ProductShadowQueuePort {
        val pending = inputs.map { QueuedShadowInput(UUID.randomUUID(), it, 0) }.toMutableList()
        val completed = mutableMapOf<UUID, String>()
        val compared = mutableSetOf<UUID>()
        val retried = mutableMapOf<UUID, String>()
        override suspend fun claim(limit: Int) = pending.take(limit).also { pending.removeAll(it) }
        override suspend fun complete(id: UUID, outcome: String, compared: Boolean) {
            completed[id] = outcome
            if (compared) this.compared += id
        }
        override suspend fun retryLater(id: UUID, outcome: String) {
            retried[id] = outcome
        }
    }

    private class Copies : RevisionIrCopyPort {
        val rows = mutableMapOf<String, StoredRevisionIr>()
        override suspend fun find(revisionId: String) = rows[revisionId]
        override suspend fun store(copy: StoredRevisionIr) = rows.getOrPut(copy.revisionId) { copy }
    }

    private fun signed(revisionId: String) =
        StoredRevisionIr(revisionId, true, hash, ShadowFixtures.IR, signer.sign(hash))

    private fun service(
        queue: Queue,
        fetch: (String) -> StoredRevisionIr,
        verifier: IrSignatureVerifierPort? = signer,
        copies: Copies = Copies(),
    ) = ProductShadowService(
        queue,
        copies,
        object : CatalogRevisionIrPort {
            override suspend fun fetch(revisionId: String) = fetch(revisionId)
        },
        verifier,
        FeeShadowComparator(ProductInterpreter(FinanceProductArithmetic)),
        ProductShadowRecorder(registry, "billing"),
        registry,
        maxAttempts = 2,
    )

    private fun count(name: String, vararg tags: String) = registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    @Test
    fun `a verified revision is compared per rule, agreement and divergence counted apart`(): Unit = runBlocking {
        val agree = ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged()))
        val diverge = ShadowFixtures.input("5000.00", listOf(ShadowFixtures.charged()), agree.productRevisionId)
        val queue = Queue(agree, diverge)
        service(queue, { signed(it) }).runOnce(10)
        assertThat(queue.completed.values).containsExactlyInAnyOrder("AGREED", "DIVERGED")
        val tags = arrayOf("consumer", "billing", "product", "CA-TEST", "rule_id", "fee.maintenance")
        assertThat(count(ProductShadowRecorder.COMPARED, *tags)).isEqualTo(2.0)
        assertThat(count(ProductShadowRecorder.DIVERGED, *tags)).isEqualTo(1.0)
    }

    @Test
    fun `a required unknown balance is explicitly not comparable and never counted as agreement`(): Unit = runBlocking {
        val queue = Queue(ShadowFixtures.input(null, listOf(ShadowFixtures.charged())))
        service(queue, { signed(it) }).runOnce(10)
        assertThat(queue.completed.values).containsExactly("INPUT_UNKNOWN")
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "input_unknown")).isEqualTo(1.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counters()).isEmpty()
        assertThat(registry.find(ProductShadowRecorder.DIVERGED).counters()).isEmpty()
    }

    @Test
    fun `a revision without IR, or with verification off, compares nothing and says why`(): Unit = runBlocking {
        val noIr = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(noIr, { StoredRevisionIr(it, false, null, null, null) }).runOnce(10)
        assertThat(noIr.completed.values).containsExactly("NO_IR")
        val off = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(off, { signed(it) }, verifier = null).runOnce(10)
        assertThat(off.completed.values).containsExactly("VERIFICATION_DISABLED")
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "no_ir")).isEqualTo(1.0)
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "verification_disabled")).isEqualTo(1.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counters()).isEmpty()
    }

    @Test
    fun `a forged or missing signature is a verification failure, never a comparison`(): Unit = runBlocking {
        val forger = HmacIrSigner("another-secret-entirely-not-shared".toByteArray(), "product-ir-publish")
        val forged = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(forged, { StoredRevisionIr(it, true, hash, ShadowFixtures.IR, forger.sign(hash)) }).runOnce(10)
        val unsigned = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(unsigned, { StoredRevisionIr(it, true, null, ShadowFixtures.IR, null) }).runOnce(10)
        assertThat(forged.completed.values + unsigned.completed.values)
            .containsExactly("VERIFICATION_FAILED", "VERIFICATION_FAILED")
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counters()).isEmpty()
    }

    @Test
    fun `an unreachable catalog or verifier is retried, then given up on after the attempt cap`(): Unit = runBlocking {
        val down = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(down, {
            throw CatalogRevisionUnavailableException("catalog unreachable", RuntimeException("boom"))
        }).runOnce(10)
        assertThat(down.retried.values).containsExactly("REVISION_UNAVAILABLE")
        val exhausted = Queue()
        exhausted.pending +=
            QueuedShadowInput(UUID.randomUUID(), ShadowFixtures.input("1.00", emptyList()), attempts = 1)
        service(exhausted, {
            throw CatalogRevisionUnavailableException("still down", RuntimeException("boom"))
        }).runOnce(10)
        assertThat(exhausted.completed.values).containsExactly("REVISION_UNAVAILABLE_EXHAUSTED")
        val verifierDown = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(verifierDown, { signed(it) }, verifier = { _, _ -> throw IrSigningException("HTTP 503") }).runOnce(10)
        assertThat(verifierDown.retried.values).containsExactly("VERIFIER_UNAVAILABLE")
    }

    @Test
    fun `the catalog is read once per revision and the local copy serves every later input`(): Unit = runBlocking {
        var fetches = 0
        val revisionId = UUID.randomUUID().toString()
        val queue = Queue(
            ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged()), revisionId),
            ShadowFixtures.input("600.00", listOf(ShadowFixtures.charged()), revisionId),
        )
        service(queue, {
            fetches++
            signed(it)
        }).runOnce(10)
        assertThat(fetches).isEqualTo(1)
        assertThat(queue.completed.values).containsOnly("AGREED")
    }

    @Test
    fun `old null snapshot facts finish once as observable unknown without retry`(): Unit = runBlocking {
        val queue = Queue(ShadowFixtures.input("500", listOf(ShadowFixtures.charged())).copy(evaluatedAt = null))
        val worker = service(queue, { signed(it) })
        assertThat(worker.runOnce(10)).isEqualTo(1)
        assertThat(queue.completed.values).containsExactly("INPUT_UNKNOWN")
        assertThat(queue.retried).isEmpty()
        assertThat(worker.runOnce(10)).isZero()
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "input_unknown")).isEqualTo(1.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counters()).isEmpty()
    }

    @Test
    fun `an unexpected failure completes that item as IR_EVALUATION_FAILED and the batch carries on`(): Unit =
        runBlocking {
            val poison = ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged()))
            val healthy = ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged()))
            val queue = Queue(poison, healthy)
            queue.pending += QueuedShadowInput(UUID.randomUUID(), null, attempts = 0)
            service(queue, { id ->
                if (id == poison.productRevisionId) throw NullPointerException("interpreter defect") else signed(id)
            }).runOnce(10)
            assertThat(queue.completed.values)
                .containsExactlyInAnyOrder("IR_EVALUATION_FAILED", "IR_EVALUATION_FAILED", "AGREED")
            assertThat(queue.retried).isEmpty()
            assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "ir_evaluation_failed")).isEqualTo(2.0)
            // Only the compared item may have its frozen facts erased.
            assertThat(queue.compared).hasSize(1)
        }

    @Test
    fun `a not-comparable outcome never asks for the frozen facts to be erased`(): Unit = runBlocking {
        val noIr = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(noIr, { StoredRevisionIr(it, false, null, null, null) }).runOnce(10)
        val off = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(off, { signed(it) }, verifier = null).runOnce(10)
        assertThat(noIr.completed).hasSize(1)
        assertThat(off.completed).hasSize(1)
        assertThat(noIr.compared + off.compared).isEmpty()
    }

    @Test
    fun `a catalog answer for another revision is a verification failure and is never stored`(): Unit = runBlocking {
        val copies = Copies()
        val queue = Queue(ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())))
        service(queue, {
            throw CatalogRevisionMismatchException("catalog answered revision other for $it")
        }, copies = copies)
            .runOnce(10)
        assertThat(queue.completed.values).containsExactly("VERIFICATION_FAILED")
        assertThat(queue.retried).isEmpty()
        assertThat(copies.rows).isEmpty()
    }

    @Test
    fun `an input with no fee on either side is NO_FEES with its own counter, never AGREED`(): Unit = runBlocking {
        val empty = """{"irVersion":1,"productCode":"CA-EMPTY","currency":"EUR","fees":[],"hooks":{}}"""
        val emptyHash = ProductIrParser.parse(empty).revisionHash
        val queue = Queue(ShadowFixtures.input("500.00", emptyList()))
        service(queue, { StoredRevisionIr(it, true, emptyHash, empty, signer.sign(emptyHash)) }).runOnce(10)
        assertThat(queue.completed.values).containsExactly(ProductShadowService.NO_FEES)
        assertThat(count(ProductShadowService.NOTHING_TO_COMPARE)).isEqualTo(1.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counters()).isEmpty()
    }
}
