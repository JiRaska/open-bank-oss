// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sca.application.port.out.NotificationSender
import com.openbank.sca.application.port.out.OtpGenerator
import com.openbank.sca.application.port.out.OtpStore
import com.openbank.sca.application.port.out.ScaIdempotencyStore
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

    // CodeQL java/log-injection: message is caller-supplied and flows straight into the log
    // line below. Strip CR/LF so an attacker can't forge additional log lines (CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    // Emit a real PUSH notification request (#4) so the party gets a "payment to approve" alert on
    // their device. SCA_APPROVAL maps to the SECURITY category in notification-service, so it is
    // always delivered regardless of the customer's push preferences.
    override suspend fun sendPushNotification(partyId: UUID, challengeId: UUID, message: String) {
        log.infof("PUSH → partyId=%s challengeId=%s message=%s", partyId, challengeId, message.sanitizeForLog())
        val request = mapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to "SCA_APPROVAL",
            "recipient" to partyId.toString(),
            "variables" to mapOf("detail" to message),
        )
        notificationEmitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(request)))
    }
}
