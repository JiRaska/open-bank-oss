// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.devops.contract

import javax.sql.DataSource

/** Real persistence behind the consumer's read contract; no HTTP response is stubbed. */
internal fun seedDevOpsPactFindings(dataSource: DataSource, active: Boolean) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM findings")
            if (active) {
                statement.executeUpdate(
                    """
                    INSERT INTO findings
                        (id, detector, severity, detected_at, title, raw_metric_value,
                         threshold, affected_resource, remediation_kind, status)
                    VALUES
                        ('11111111-1111-4111-8111-111111111111', 'D1_CI_PIPELINE_HEALTH', 'WARNING',
                         '2026-01-01T00:00:00Z', 'Synthetic CI health finding',
                         5, 2, 'synthetic-pipeline', 'NONE', 'OPEN')
                    """.trimIndent(),
                )
            }
        }
    }
}
