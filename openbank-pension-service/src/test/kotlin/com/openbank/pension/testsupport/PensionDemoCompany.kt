// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.testsupport

import java.util.UUID

/** Synthetic identities only. Each demo invocation owns a fresh JVM, Postgres and Temporal server. */
enum class PensionDemoCompany(val slug: String, val providerId: UUID) {
    ALPHA("alpha", UUID.fromString("00000000-0000-4000-8000-000000000001")),
    BETA("beta", UUID.fromString("00000000-0000-4000-8000-000000000002")),
    ;

    val other: PensionDemoCompany get() = if (this == ALPHA) BETA else ALPHA

    companion object {
        private const val PROPERTY = "pension.demo.company"

        fun current(): PensionDemoCompany = when (val slug = System.getProperty(PROPERTY, "alpha")) {
            "alpha" -> ALPHA
            "beta" -> BETA
            else -> error("Unknown synthetic demo company: $slug")
        }

        fun databaseName(): String = if (System.getProperty(PROPERTY) == null) {
            "openbank_pension_it"
        } else {
            "openbank_pension_demo_${current().slug}"
        }
    }
}
