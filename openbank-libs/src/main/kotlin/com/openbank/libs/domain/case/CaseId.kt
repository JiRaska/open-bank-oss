// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

import java.util.UUID

/** UUID-backed identifier for a case aggregate. */
data class CaseId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CaseId = CaseId(UUID.randomUUID())

        fun from(value: String): CaseId = CaseId(UUID.fromString(value))
    }
}
