// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.application.port.`in`.GetTransactionQuery
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.infrastructure.persistence.entity.TransactionCategoryOverrideEntity
import com.openbank.transaction.infrastructure.persistence.repository.TransactionCategoryOverrideRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class TransactionCategoryResourceTest {
    private val useCase = mockk<TransactionUseCase>()
    private val overrides = mockk<TransactionCategoryOverrideRepository>()
    private val resource = TransactionCategoryResource(useCase, overrides)
    private val transactionId = UUID.randomUUID()
    private val sourceAccount = UUID.randomUUID()
    private val targetAccount = UUID.randomUUID()

    private fun stubTransaction(name: String? = "Example Shop", description: String? = "Payment reference") {
        val transaction = mockk<Transaction>()
        every { transaction.sourceAccountId } returns sourceAccount
        every { transaction.targetAccountId } returns targetAccount
        every { transaction.counterpartyName } returns name
        every { transaction.description } returns description
        coEvery { useCase.getTransaction(GetTransactionQuery(transactionId)) } returns transaction
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `set category scopes the override to the selected transaction account`(source: Boolean): Unit = runBlocking {
        stubTransaction()
        val account = if (source) sourceAccount else targetAccount
        coEvery { overrides.upsert(account, "EXAMPLESHOP", "GROCERIES") } returns Unit

        val response = resource.setCategory(transactionId, account, SetCategoryRequest("groceries"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(CategoryOverrideResponse("EXAMPLESHOP", "GROCERIES"))
        coVerify(exactly = 1) { overrides.upsert(account, "EXAMPLESHOP", "GROCERIES") }
    }

    @Test
    fun `unknown category is rejected before reading or writing a transaction`(): Unit = runBlocking {
        val response = resource.setCategory(transactionId, sourceAccount, SetCategoryRequest("not-a-category"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity).isEqualTo(
            mapOf("title" to "Bad Request", "detail" to "unknown category 'not-a-category'"),
        )
        coVerify(exactly = 0) { useCase.getTransaction(any()) }
        coVerify(exactly = 0) { overrides.upsert(any(), any(), any()) }
    }

    @Test
    fun `set category rejects an account outside the transaction`(): Unit = runBlocking {
        stubTransaction()

        val response = resource.setCategory(transactionId, UUID.randomUUID(), SetCategoryRequest("GROCERIES"))

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { overrides.upsert(any(), any(), any()) }
    }

    @Test
    fun `set category refuses transactions without an identifiable counterparty`(): Unit = runBlocking {
        stubTransaction(name = null, description = null)

        val response = resource.setCategory(transactionId, sourceAccount, SetCategoryRequest("GROCERIES"))

        assertThat(response.status).isEqualTo(422)
        coVerify(exactly = 0) { overrides.upsert(any(), any(), any()) }
    }

    @Test
    fun `card description supplies the key when there is no counterparty name`(): Unit = runBlocking {
        stubTransaction(name = null, description = "Example Shop")
        coEvery { overrides.upsert(sourceAccount, "EXAMPLESHOP", "GROCERIES") } returns Unit

        val response = resource.setCategory(transactionId, sourceAccount, SetCategoryRequest("GROCERIES"))

        assertThat(response.status).isEqualTo(200)
        coVerify(exactly = 1) { overrides.upsert(sourceAccount, "EXAMPLESHOP", "GROCERIES") }
    }

    @Test
    fun `clear category removes only the selected account and counterparty`(): Unit = runBlocking {
        stubTransaction()
        coEvery { overrides.remove(targetAccount, "EXAMPLESHOP") } returns false

        val response = resource.clearCategory(transactionId, targetAccount)

        assertThat(response.status).isEqualTo(204)
        coVerify(exactly = 1) { overrides.remove(targetAccount, "EXAMPLESHOP") }
    }

    @Test
    fun `clear category rejects an account outside the transaction`(): Unit = runBlocking {
        stubTransaction()

        val response = resource.clearCategory(transactionId, UUID.randomUUID())

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { overrides.remove(any(), any()) }
    }

    @Test
    fun `clearing an unidentifiable counterparty is an idempotent no-op`(): Unit = runBlocking {
        stubTransaction(name = null, description = null)

        val response = resource.clearCategory(transactionId, sourceAccount)

        assertThat(response.status).isEqualTo(204)
        coVerify(exactly = 0) { overrides.remove(any(), any()) }
    }

    @Test
    fun `list categories uses the requested account and maps its overrides`(): Unit = runBlocking {
        val row = TransactionCategoryOverrideEntity().apply {
            id = TransactionCategoryOverrideEntity.Key(sourceAccount, "EXAMPLESHOP")
            category = "GROCERIES"
        }
        coEvery { overrides.listFor(sourceAccount) } returns listOf(row)

        val response = resource.listCategoryOverrides(sourceAccount)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(
            mapOf("data" to listOf(CategoryOverrideResponse("EXAMPLESHOP", "GROCERIES"))),
        )
        coVerify(exactly = 1) { overrides.listFor(sourceAccount) }
    }

    @Test
    fun `all category operations require an explicit account`() {
        assertThatThrownBy {
            runBlocking { resource.setCategory(transactionId, null, SetCategoryRequest("GROCERIES")) }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("query parameter 'accountId' is required")
        assertThatThrownBy {
            runBlocking { resource.clearCategory(transactionId, null) }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("query parameter 'accountId' is required")
        assertThatThrownBy {
            runBlocking { resource.listCategoryOverrides(null) }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("query parameter 'accountId' is required")
        coVerify(exactly = 0) { useCase.getTransaction(any()) }
        coVerify(exactly = 0) { overrides.listFor(any()) }
        coVerify(exactly = 0) { overrides.upsert(any(), any(), any()) }
        coVerify(exactly = 0) { overrides.remove(any(), any()) }
    }
}
