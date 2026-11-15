// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.libs.fincrime.CounterpartyAccount

/**
 * Turns a payee account into the keyed pseudonym payee history is stored under (ADR-0377 D3).
 * fraud-service is the only place that keys a counterparty: callers send the normalised identifier
 * over the service-to-service hop, and only the keyed value is ever persisted.
 */
interface CounterpartyKeyPort {
    /** `cp2:<keyId>:<hex>`, or null when no key is configured — never an unkeyed fallback. */
    fun key(account: CounterpartyAccount): String?
}
