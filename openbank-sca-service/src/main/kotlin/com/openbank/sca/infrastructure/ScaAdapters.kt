// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.sanitizeForLog
import com.openbank.sca.application.port.out.NotificationSender
import com.openbank.sca.application.port.out.OtpGenerator
import com.openbank.sca.application.port.out.OtpStore
import com.openbank.sca.application.port.out.ScaIdempotencyStore
import com.openbank.sca.domain.model.ReservedNamespace
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.security.SecureRandom
import java.util.UUID

@ApplicationScoped
class SecureOtpGenerator : OtpGenerator {
    private val random = SecureRandom()
    override fun generate(): String = String.format("%06d", random.nextInt(1_000_000))
}

@ApplicationScoped
class RedisOtpStore(private val redis: ReactiveRedisDataSource) : OtpStore {
    private val strings = redis.value(String::class.java)

    override suspend fun store(challengeId: UUID, otp: String, ttlSeconds: Long) {
        strings.setex("sca:otp:$challengeId", ttlSeconds, otp).awaitSuspending()
    }

    override suspend fun verify(challengeId: UUID, otp: String): Boolean {
        val stored = strings.get("sca:otp:$challengeId").awaitSuspending()
        return stored == otp
    }

    override suspend fun invalidate(challengeId: UUID) {
        redis.key().del("sca:otp:$challengeId").awaitSuspending()
    }
}

@ApplicationScoped
class RedisScaIdempotencyStore(private val redis: ReactiveRedisDataSource) : ScaIdempotencyStore {
    private val strings = redis.value(String::class.java)

    override suspend fun get(key: String): String? = strings.get("sca:idempotency:$key").awaitSuspending()

    override suspend fun save(key: String, challengeId: UUID, ttlSeconds: Long) {
        strings.set(
            "sca:idempotency:$key",
            challengeId.toString(),
            SetArgs().ex(ttlSeconds),
        ).awaitSuspending()
    }
}

@ApplicationScoped
class LoggingNotificationSender(
    private val objectMapper: ObjectMapper,
    @Channel("notification-requests-out") private val notificationEmitter: Emitter<Record<String, String>>,
) : NotificationSender {
    private val log = org.jboss.logging.Logger.getLogger(LoggingNotificationSender::class.java)

    // Moved to the shared com.openbank.libs.security.sanitizeForLog (#10907), imported above.

    private val canonicalMcpReference = Regex("[0-9a-f]{64}")

    // Both approval templates are SECURITY notifications and respect the same delivery guarantees.
    override suspend fun sendPushNotification(
        partyId: UUID,
        challengeId: UUID,
        message: String,
        mcpApprovalRequestId: String?,
    ) {
        log.infof("PUSH → partyId=%s challengeId=%s message=%s", partyId, challengeId, message.sanitizeForLog())
        val request = buildMap<String, Any> {
            put("partyId", partyId.toString())
            put("channel", "PUSH")
            put("recipient", partyId.toString())
            if (mcpApprovalRequestId == null) {
                put("template", "SCA_APPROVAL")
                put("variables", mapOf("detail" to message))
            } else {
                put("template", "MCP_CONNECT_APPROVAL")
                put("variables", emptyMap<String, String>())
                val reference = mcpApprovalRequestId.removePrefix(ReservedNamespace.MCP_CONNECT.prefix)
                if (reference != mcpApprovalRequestId && canonicalMcpReference.matches(reference)) {
                    put("deepLink", "openbank://mcp/connect/$reference")
                }
            }
        }
        notificationEmitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(request)))
    }
}
