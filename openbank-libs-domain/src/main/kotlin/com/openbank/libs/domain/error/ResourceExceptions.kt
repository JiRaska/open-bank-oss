// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.error

/**
 * Framework-free base for "the addressed resource does not exist". libs-runtime maps every
 * subclass to **404** with the standard `ApiError` envelope (`ResourceNotFoundExceptionMapper`),
 * so a service no longer needs its own `ExceptionMapper` per not-found type.
 *
 * [code] is the `ApiError.code` rendered to the caller; subclasses keep a domain-specific code
 * (e.g. `ACCOUNT_NOT_FOUND`) where their published contract already uses one.
 *
 * Deliberately NOT a subclass of `NoSuchElementException`/`IllegalStateException`: the generic
 * lib mappers for those would otherwise compete with the dedicated one (issue #526).
 */
open class ResourceNotFoundException(message: String, val code: String = "NOT_FOUND", cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Framework-free base for "the request conflicts with the resource's current state" — a duplicate
 * key, a stale version, an idempotency-key reuse with a different payload. Mapped to **409** by
 * libs-runtime's `ResourceConflictExceptionMapper`.
 */
open class ResourceConflictException(message: String, val code: String = "CONFLICT", cause: Throwable? = null) :
    RuntimeException(message, cause)
