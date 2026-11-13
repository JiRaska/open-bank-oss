// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import javax.sql.DataSource

/**
 * The legacy-schema regression tests pin Flyway to an old version to reproduce a historical JSONB
 * shape, yet read it through the CURRENT entity. Columns the entity gained after their pin must
 * therefore exist, or every read fails on a missing column before reaching what the test is about.
 * V21 adds the same columns with `IF NOT EXISTS`, so migrating forward afterwards still works.
 */
internal fun addColumnsAddedAfterLegacyPin(dataSource: DataSource) {
    dataSource.connection.use { c ->
        c.createStatement().use {
            it.execute(
                "ALTER TABLE sanctions_checks " +
                    "ADD COLUMN IF NOT EXISTS profile VARCHAR(16), " +
                    "ADD COLUMN IF NOT EXISTS country VARCHAR(2), " +
                    "ADD COLUMN IF NOT EXISTS bank_identifier VARCHAR(11), " +
                    "ADD COLUMN IF NOT EXISTS pep_status VARCHAR(16)",
            )
        }
    }
}
