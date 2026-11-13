// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import com.openbank.libs.domain.error.Conflict
import java.time.Duration

private const val RETRY_AFTER_SECONDS = 30L

/**
 * A synchronous (v1) refresh found another refresh of the same list already running and refused
 * to queue behind it: 409 with Retry-After, via libs-runtime's DomainExceptionMapper (#14780).
 * Without this, N concurrent calls each held a request thread and, one after another, ran a full
 * in-request import of the same feed (PEP_GLOBAL is ~700k entries).
 */
class SanctionsRefreshInProgress(listType: String) :
    Conflict(
        clientMessage = "A refresh of sanctions list $listType is already running; retry after it completes",
        retryAfter = Duration.ofSeconds(RETRY_AFTER_SECONDS),
    )
