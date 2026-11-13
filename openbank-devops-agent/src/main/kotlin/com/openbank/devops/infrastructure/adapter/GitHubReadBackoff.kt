// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.adapter

import com.openbank.libs.security.EgressResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object GitHubReadBackoff {
    fun until(response: EgressResponse, now: Instant): Instant? {
        val remaining = response.header("x-ratelimit-remaining")?.toLongOrNull()
        val limit = response.header("x-ratelimit-limit")?.toLongOrNull()?.takeIf { it > 0 }
        val reserve = maxOf(MIN_RESERVE, (limit ?: 0) / RESERVE_DIVISOR)
        if (response.status !in THROTTLED && (remaining == null || remaining > reserve)) return null
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull()?.let {
            runCatching { Instant.ofEpochSecond(it) }.getOrNull()
        }
        val retry = response.header("retry-after")?.let { value ->
            value.toLongOrNull()?.takeIf { it >= 0 }?.let {
                runCatching { now.plusSeconds(it) }.getOrNull()
            }
                ?: runCatching {
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                }.getOrNull()
        }
        return listOfNotNull(now.plus(MIN_PAUSE), reset, retry).max()
    }

    private const val FORBIDDEN = 403
    private const val TOO_MANY_REQUESTS = 429
    private const val PAUSE_MINUTES = 15L
    private val THROTTLED = setOf(FORBIDDEN, TOO_MANY_REQUESTS)
    private val MIN_PAUSE: Duration = Duration.ofMinutes(PAUSE_MINUTES)
    private const val MIN_RESERVE = 100L
    private const val RESERVE_DIVISOR = 5
}
