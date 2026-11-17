// SPDX-License-Identifier: Apache-2.0

package com.openbank.transaction.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.synthetic.SyntheticTaint
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.CompletionStage

@ApplicationScoped
class AccountBookingConsumer(private val mapper: ObjectMapper) {
    private val log = Logger.getLogger(AccountBookingConsumer::class.java)

    @Incoming("account-booking-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(message: Message<String>) {
        try {
            val metadata = message.getMetadata(IncomingKafkaRecordMetadata::class.java).orElseThrow()
            val taint = metadata.headers.lastHeader(SyntheticTaint.KAFKA_HEADER)?.value()?.toString(Charsets.UTF_8)
            project(message.payload, metadata.key?.toString(), SyntheticTaint.isTainted(taint))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message.nack(error).await()
            return
        }
        message.ack().await()
    }

    private suspend fun project(payload: String, key: String?, synthetic: Boolean) {
        val event = mapper.readTree(payload)
        if (event.path("eventType").asText() != "AccountCreated") return
        require(event.path("aggregateType").asText() == "Account")
        val accountId = UUID.fromString(event.path("aggregateId").asText())
        require(key == accountId.toString()) { "AccountCreated key differs from the account identity" }
        val context = event.get("bookingContext")?.takeUnless(JsonNode::isNull)?.let(::parseAccountBookingContext)
        val json = context?.let(mapper::writeValueAsString) ?: "null"
        val identical = EventRetry.withRetry(
            log = log,
            what = "account booking projection",
            key = accountId,
            isRetryable = { it !is CancellationException },
        ) {
            insertOrVerify(accountId, synthetic, json)
        }
        check(identical) { "AccountCreated replay cannot change an account's booking context" }
    }

    private suspend fun insertOrVerify(accountId: UUID, synthetic: Boolean, json: String): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(INSERT_SQL)
                    .setParameter("accountId", accountId)
                    .setParameter("synthetic", synthetic)
                    .setParameter("context", json)
                    .executeUpdate()
                    .flatMap { changed ->
                        if (changed == 1) {
                            Uni.createFrom().item(true)
                        } else {
                            session.createNativeQuery(REPLAY_SQL, Boolean::class.javaObjectType)
                                .setParameter("accountId", accountId)
                                .setParameter("synthetic", synthetic)
                                .setParameter("context", json)
                                .singleResult
                        }
                    }
            }
        }.awaitSuspending()

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO account_booking_projection (account_id, synthetic, booking_context)
            VALUES (:accountId, :synthetic, NULLIF(CAST(CAST(:context AS text) AS jsonb), 'null'::jsonb))
            ON CONFLICT (account_id, synthetic) DO NOTHING
        """
        const val REPLAY_SQL = """
            SELECT booking_context IS NOT DISTINCT FROM NULLIF(CAST(CAST(:context AS text) AS jsonb), 'null'::jsonb)
            FROM account_booking_projection WHERE account_id = :accountId AND synthetic = :synthetic
        """
    }
}

internal data class AccountBookingContext(
    val schemaVersion: Int,
    val contextVersionId: UUID,
    val legalEntityId: UUID,
    val bookingBookId: UUID,
    val operatingMode: Mode,
    val establishmentId: UUID?,
) {
    enum class Mode { DOMESTIC, FOS, BRANCH }
}

internal fun parseAccountBookingContext(node: JsonNode): AccountBookingContext {
    val version = node.path("schemaVersion")
    require(node.isObject && version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) {
        "Unsupported account booking context schema"
    }
    val mode = AccountBookingContext.Mode.valueOf(node.path("operatingMode").asText())
    val establishmentId = node.get("establishmentId")?.takeUnless(JsonNode::isNull)?.asText()?.let(UUID::fromString)
    require((mode == AccountBookingContext.Mode.BRANCH) == (establishmentId != null)) {
        "Branch mode and establishment identifier must agree"
    }
    return AccountBookingContext(
        schemaVersion = 1,
        contextVersionId = UUID.fromString(node.path("contextVersionId").asText()),
        legalEntityId = UUID.fromString(node.path("legalEntityId").asText()),
        bookingBookId = UUID.fromString(node.path("bookingBookId").asText()),
        operatingMode = mode,
        establishmentId = establishmentId,
    )
}

private suspend fun <T> CompletionStage<T>.await(): T = Uni.createFrom().completionStage(this).awaitSuspending()
