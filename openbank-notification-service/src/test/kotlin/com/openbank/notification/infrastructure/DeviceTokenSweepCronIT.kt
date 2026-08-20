package com.openbank.notification.infrastructure

import com.openbank.notification.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** Proves the real Quarkus scheduler invokes the stale-token sweep. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DeviceTokenSweepCronIT.FastCronProfile::class)
class DeviceTokenSweepCronIT {
    class FastCronProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "quarkus.scheduler.cron.device-token-stale-sweep" to "*/1 * * * * ?",
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var meterRegistry: MeterRegistry

    @Test
    fun `real cron retires stale token and records heartbeat`() {
        val id = java.util.UUID.randomUUID()
        dataSource.connection.use { c -> insertStale(c, id) }

        val deadline = System.nanoTime() + 15_000_000_000L
        var status = "ACTIVE"
        while (System.nanoTime() < deadline && status == "ACTIVE") {
            Thread.sleep(200)
            status = statusOf(id)
        }
        assertThat(status).isEqualTo("INACTIVE")
        assertThat(
            meterRegistry.find("openbank_workflow_success_recorded")
                .tag("workflow", "device-token-stale-sweep").gauge()?.value(),
        ).isEqualTo(1.0)
    }

    private fun statusOf(id: java.util.UUID): String {
        val connection = dataSource.connection
        val statement = connection.prepareStatement("select status from device_tokens where device_id = ?")
        statement.setObject(1, id)
        val result = statement.executeQuery()
        return try {
            if (result.next()) result.getString(1) else "MISSING"
        } finally {
            result.close()
            statement.close()
            connection.close()
        }
    }

    private fun insertStale(c: Connection, id: java.util.UUID) {
        c.prepareStatement(
            """
            insert into device_tokens
              (device_id, party_id, app_instance, platform, token, status,
               last_used_at, registered_at, refreshed_at, created_at, updated_at)
            values (?, ?, 'cron-it', 'IOS', 'cron-it-token-' || ?, 'ACTIVE',
                    ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { s ->
            val old = Instant.now().minusSeconds(91 * 24 * 3600)
            val oldTimestamp = Timestamp.from(old)
            s.setObject(1, id)
            s.setObject(2, java.util.UUID.randomUUID())
            s.setObject(3, id)
            s.setTimestamp(4, oldTimestamp)
            s.setTimestamp(5, oldTimestamp)
            s.setTimestamp(6, oldTimestamp)
            s.setTimestamp(7, oldTimestamp)
            s.setTimestamp(8, oldTimestamp)
            s.executeUpdate()
        }
    }
}
