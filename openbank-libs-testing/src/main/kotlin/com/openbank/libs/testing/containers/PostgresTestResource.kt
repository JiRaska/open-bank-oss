// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import com.openbank.libs.domain.identifiers.Ids
/**
 * Isolated PostgreSQL per test JVM. For a service that needs a database but no Redis/Kafka —
 * everything else (broker channels) already runs the in-memory connector in tests. See
 * [PostgresBase] for the database-name `initArgs` convention.
 */
class PostgresTestResource : PostgresBase(RESOURCE_SCOPE_ID) {
    override fun start(): Map<String, String> {
        val pg = startPostgres()
        return postgresConfig(pg)
    }

    private companion object {
        val RESOURCE_SCOPE_ID = Ids.randomId().toString()
    }
}
