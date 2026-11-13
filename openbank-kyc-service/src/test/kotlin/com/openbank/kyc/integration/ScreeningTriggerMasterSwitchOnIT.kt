// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.openbank.kyc.it.CountingCoordinatorStub
import com.openbank.kyc.it.KycKafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #15961, the drain half of [ScreeningTriggerMasterSwitchOffIT]: the app restarts with the
 * master switch ON (the "flip" is a new config, as it is in a deployment) over DEFERRED rows
 * that accumulated while it was off. The real retry cron must claim them in bounded batches and
 * open exactly one case per party — never zero, never twice.
 */
@QuarkusTest
@TestProfile(ScreeningTriggerMasterSwitchOnIT.Profile::class)
class ScreeningTriggerMasterSwitchOnIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(SwitchOn::class.java),
            QuarkusTestProfile.TestResourceEntry(CountingCoordinatorStub::class.java),
        )
    }

    class SwitchOn : KycKafkaTestResource() {
        override fun overrides(): Map<String, String> = mapOf(
            "openbank.kyc.screening-triggers.enabled" to "true",
            // KycKafkaTestResource turns the scheduler off; this test IS about the real cron.
            "quarkus.scheduler.enabled" to "true",
            "openbank.kyc.screening-triggers.retry-interval" to "1s",
            "openbank.kyc.screening-triggers.retry-batch-size" to "2",
            "mp.messaging.incoming.sanctions-list-changes-in.group.id" to "kyc-master-switch-on-it",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `switch on - rows recorded while off are drained, one case per party`() {
        val parties = List(PARTIES) { UUID.randomUUID() }
        parties.forEach(::insertDeferred)

        await("every deferred row opened") { parties.all { stateOf(it) == "OPENED" } }
        // A few more ticks: an OPENED row is never re-claimed, so nothing is opened twice.
        Thread.sleep(SETTLE.toMillis())
        parties.forEach { party ->
            assertThat(CountingCoordinatorStub.opensFor(party)).`as`("opens for $party").isEqualTo(1)
            assertThat(stateOf(party)).isEqualTo("OPENED")
        }
    }

    /** Exactly the row the consumer writes with the switch off: PENDING, attempts 0, due now. */
    private fun insertDeferred(partyId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO kyc_screening_triggers (trigger_id, party_id, trigger_type, publication_id, diff, " +
                    "state, attempts, next_attempt_at) VALUES (?, ?, 'SANCTIONS_LIST_CHANGED', ?, " +
                    "'{\"changedExternalIds\":[\"X\"],\"deactivatedExternalIds\":[],\"changeCount\":1}', " +
                    "'PENDING', 0, now())",
            ).use { insert ->
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, partyId)
                insert.setObject(3, UUID.randomUUID())
                insert.executeUpdate()
            }
        }
    }

    private fun stateOf(partyId: UUID): String? = dataSource.connection.use { connection ->
        val query = connection.prepareStatement("SELECT state FROM kyc_screening_triggers WHERE party_id = ?")
        query.setObject(1, partyId)
        val rows = query.executeQuery()
        if (rows.next()) rows.getString(1) else null
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        error("Timed out waiting for $what")
    }

    private companion object {
        const val PARTIES = 5
        val SETTLE: Duration = Duration.ofSeconds(4)
    }
}
