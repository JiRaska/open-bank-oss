// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import org.eclipse.microprofile.config.ConfigProvider
import java.sql.DriverManager

/** Plain JDBC against the IT database, for cleanup the tests share. */
internal object TestDb {
    fun execute(sql: String) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
    }
}
