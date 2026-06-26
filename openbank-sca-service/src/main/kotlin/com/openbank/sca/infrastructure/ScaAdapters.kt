// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sca.infrastructure

import com.openbank.sca.application.port.out.OtpGenerator
import com.openbank.sca.application.port.out.ScaIdempotencyStore
import com.openbank.sca.application.port.out.OtpStore
import com.openbank.sca.application.port.out.NotificationSender
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
            SetArgs().ex(ttlSeconds)
        ).awaitSuspending()
    }
}

@ApplicationScoped
class LoggingNotificationSender : NotificationSender {
    private val log = org.jboss.logging.Logger.getLogger(LoggingNotificationSender::class.java)

    override suspend fun sendPushNotification(partyId: UUID, challengeId: UUID, message: String) {
        log.infof("PUSH → partyId=%s challengeId=%s message=%s", partyId, challengeId, message)
    }

    override suspend fun sendSmsOtp(partyId: UUID, otp: String) {
        log.infof("SMS OTP → partyId=%s otp=%s", partyId, otp)
    }
}
