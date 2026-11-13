// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.billing.integration

import com.openbank.billing.infrastructure.persistence.repository.BillingCloseEvidenceRepository
import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Runbook 0033's re-bill record against real Postgres (V10): the four-eyes CHECK refuses a maker who
 * is also the checker however the name is cased or padded, and [BillingCloseEvidenceRepository.isAuthorised]
 * admits exactly the recorded (new key -> original) pairing while the original fee is unbooked.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingFeeRebillIT {

    @Inject
    lateinit var evidence: BillingCloseEvidenceRepository

    private fun sql(statement: String, vararg args: Any?) {
        val cfg = ConfigProvider.getConfig()
        DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.prepareStatement(statement).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeUpdate()
            }
        }
    }

    private fun authorised(rebillKey: String, originalKey: String): Boolean = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { evidence.isAuthorised(rebillKey, originalKey) }
    }

    private fun seedFailedFee(): String {
        val assessment = UUID.randomUUID()
        val suffix = UUID.randomUUID().toString().take(8)
        val key = "fee-2099-01-it-$suffix-f1-CZK"
        sql(
            "INSERT INTO billing_cycle_assessment (id, cycle_id, account_id, currency) VALUES (?, '2099-01', ?, 'CZK')",
            assessment,
            "it-$suffix",
        )
        sql(
            "INSERT INTO assessed_fee (assessment_id, cycle_id, account_id, fee_id, fee_name, currency, " +
                "charged_amount, waive_reason, idempotency_key, posting_status) " +
                "VALUES (?, '2099-01', ?, 'f1', 'Maintenance', 'CZK', 10, 'NONE', ?, 'FAILED')",
            assessment,
            "it-$suffix",
            key,
        )
        return key
    }

    @Test
    fun `the four-eyes check refuses the same person in another case or with padding`() {
        val key = seedFailedFee()
        assertThatThrownBy {
            sql(
                "INSERT INTO billing_fee_rebill (rebill_key, original_key, reason, change_ticket, maker, checker) " +
                    "VALUES (?, ?, 'ACCOUNTING_DAY_CLOSED', 'CHG-1', 'Alice', ' alice ')",
                "$key:rebill:2099-01-02",
                key,
            )
        }.hasMessageContaining("billing_fee_rebill_four_eyes")
    }

    @Test
    fun `only the recorded pairing on an unbooked fee is authorised`() {
        val key = seedFailedFee()
        val rebillKey = "$key:rebill:2099-01-02"
        assertThat(authorised(rebillKey, key)).describedAs("no record yet").isFalse()

        sql(
            "INSERT INTO billing_fee_rebill (rebill_key, original_key, reason, change_ticket, maker, checker) " +
                "VALUES (?, ?, 'ACCOUNTING_DAY_CLOSED', 'CHG-1', 'alice', 'bob')",
            rebillKey,
            key,
        )
        assertThat(authorised(rebillKey, key)).isTrue()
        assertThat(authorised("$key:rebill:forged", key)).describedAs("another new key").isFalse()

        sql(
            "UPDATE assessed_fee SET posting_status = 'POSTED', journal_id = ? WHERE idempotency_key = ?",
            UUID.randomUUID(),
            key,
        )
        assertThat(authorised(rebillKey, key)).describedAs("the original is already booked").isFalse()
    }
}
