// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The executable contract every [ApprovalStore] must satisfy (#3349).
 *
 * Segregation of duties on a four-eyes action is asserted in prose in a dozen threat models and
 * enforced in exactly one line of code per implementation. This class is what makes that assertion
 * checkable, and it exists as a CONTRACT rather than a test of one class because there is already
 * more than one implementation: `RedisApprovalStore` in production and [InMemoryApprovalStore] as
 * the interceptor tests' double. A rule stated twice drifts unless both statements answer to the
 * same test.
 *
 * Subclass it for any new implementation. If a `PanacheApprovalStore` ever lands — the entity
 * already exists in this module — it belongs here on day one; otherwise the threat models keep
 * saying "the guard" as though there were only one.
 */
abstract class ApprovalStoreContractTest {

    protected abstract fun newStore(): ApprovalStore

    @Test
    fun `a maker deciding their own pending approval is refused`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")

        // SelfApprovalNotAllowedException SPECIFICALLY. InvalidApprovalStateException is a sibling
        // subclass of IllegalStateException, so asserting the supertype would stay green with the
        // self-approval guard deleted and a non-PENDING fixture.
        assertThatThrownBy {
            runBlocking { store.decide(pending.id, decidedBy = "operator-1", approve = true) }
        }
            .isInstanceOf(SelfApprovalNotAllowedException::class.java)
            .hasMessageContaining("operator-1")
    }

    @Test
    fun `a refused self-approval leaves the approval PENDING and undecided`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")

        runCatching { store.decide(pending.id, decidedBy = "operator-1", approve = true) }

        // The guard must refuse BEFORE writing. A refactor that threw but still persisted an
        // APPROVED record would leave the maker's own X-Approval-Id retry able to consume it — a
        // refusal that grants the very thing it refused.
        val after = store.find(pending.id)
        assertThat(after?.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(after?.decidedBy).isNull()
    }

    @Test
    fun `a different checker decides the same approval successfully`(): Unit = runBlocking {
        // Control. Without it, a store whose find() always returned null would also be "red on
        // self-approval" — for the wrong reason, and it would stay red after the guard's removal.
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")

        val decided = store.decide(pending.id, decidedBy = "operator-2", approve = true)

        assertThat(decided?.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(decided?.decidedBy).isEqualTo("operator-2")
    }

    @Test
    fun `the self-approval guard is checked before the status guard`(): Unit = runBlocking {
        // Order is not arbitrary: if status were checked first, a maker re-deciding their own
        // settled approval would be told "wrong state" instead of "you may not decide your own
        // request" — and a reader of that error would conclude the SoD control had been consulted
        // when it had not.
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")
        store.decide(pending.id, decidedBy = "operator-2", approve = true)

        assertThatThrownBy {
            runBlocking { store.decide(pending.id, decidedBy = "operator-1", approve = true) }
        }
            .isInstanceOf(SelfApprovalNotAllowedException::class.java)
            .isNotInstanceOf(InvalidApprovalStateException::class.java)
    }

    @Test
    fun `an already-decided approval cannot be re-decided by a third party`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")
        store.decide(pending.id, decidedBy = "operator-2", approve = true)

        assertThatThrownBy {
            runBlocking { store.decide(pending.id, decidedBy = "operator-3", approve = true) }
        }.isInstanceOf(InvalidApprovalStateException::class.java)
    }
}

/** The production implementation's binding lives in `impl/RedisApprovalStoreTest`. */
class InMemoryApprovalStoreTest : ApprovalStoreContractTest() {
    override fun newStore(): ApprovalStore = InMemoryApprovalStore()
}
