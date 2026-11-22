// SPDX-License-Identifier: Apache-2.0

package com.openbank.transaction.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(value = PostgresRedpandaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(AccountBookingProjectionIT.ConsumerProfile::class)
class AccountBookingProjectionIT {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrap: String

    @Test
    fun `consumer preserves branch attribution and isolates synthetic openings`() {
        val account = UUID.randomUUID()
        val company = UUID.randomUUID()
        val branch = UUID.randomUUID()
        val revision = UUID.randomUUID()
        val event = mapper.createObjectNode()
            .put("eventType", "AccountCreated")
            .put("aggregateId", account.toString())
            .put("aggregateType", "Account")
        event.putObject("bookingContext")
            .put("schemaVersion", 1)
            .put("contextVersionId", revision.toString())
            .put("legalEntityId", company.toString())
            .put("bookingBookId", UUID.randomUUID().toString())
            .put("operatingMode", "BRANCH")
            .put("establishmentId", branch.toString())

        val config = mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)
        KafkaProducer(config, StringSerializer(), StringSerializer()).use { producer ->
            val original = ProducerRecord(TOPIC, account.toString(), event.toString())
            producer.send(original).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            producer.send(original).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            event.putNull("bookingContext")
            val synthetic = ProducerRecord(TOPIC, account.toString(), event.toString())
            synthetic.headers().add(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.headerValue().toByteArray())
            producer.send(synthetic).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).untilAsserted {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT synthetic, CAST(booking_context AS text)
                    FROM account_booking_projection WHERE account_id = ? ORDER BY synthetic
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, account)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getBoolean(1)).isFalse()
                        val context = parseAccountBookingContext(mapper.readTree(rows.getString(2)))
                        assertThat(context.contextVersionId).isEqualTo(revision)
                        assertThat(context.legalEntityId).isEqualTo(company)
                        assertThat(context.establishmentId).isEqualTo(branch)
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getBoolean(1)).isTrue()
                        assertThat(rows.getString(2)).isNull()
                        assertThat(rows.next()).isFalse()
                    }
                }
            }
        }
    }

    class ConsumerProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("mp.messaging.incoming.account-booking-in.enabled" to "true")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val TOPIC = "openbank.accounts.account.created"
    }
}
