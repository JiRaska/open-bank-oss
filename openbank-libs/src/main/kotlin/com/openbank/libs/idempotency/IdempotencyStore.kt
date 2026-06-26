// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.idempotency

import java.time.OffsetDateTime

data class IdempotencyRecord(
    val key: String,
    val statusCode: Int,
    val responseBody: String,
    val createdAt: OffsetDateTime,
)

interface IdempotencyStore {
    suspend fun get(key: String): IdempotencyRecord?
    suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long = 86400)
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Idempotent(val ttlSeconds: Long = 86400, val headerName: String = "Idempotency-Key")
