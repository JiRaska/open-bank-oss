// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 750 ms statement backstop lives on the application role (V12), not on the cluster: a
 * cluster-wide value also bound the postgres superuser and cancelled pg_backup_stop() on the
 * primary, failing every base backup (#12009). A fresh application session must still get it.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ApplicationRoleStatementTimeoutIT {
    @Test
    fun `a new application session carries the 750 ms statement backstop`() {
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SHOW statement_timeout").use { rs ->
                    rs.next()
                    assertThat(rs.getString(1)).isEqualTo("750ms")
                }
            }
        }
    }

    @Test
    fun `the backstop is scoped to the application role in this database only`() {
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT r.rolname, d.datname FROM pg_db_role_setting x " +
                        "JOIN pg_roles r ON r.oid = x.setrole JOIN pg_database d ON d.oid = x.setdatabase " +
                        "WHERE 'statement_timeout=750ms' = ANY(x.setconfig)",
                ).use { rs ->
                    val rows = buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    assertThat(rows).containsExactly(config("quarkus.datasource.username") to c.catalog)
                }
            }
        }
    }

    private fun config(key: String) = ConfigProvider.getConfig().getValue(key, String::class.java)

    private fun connection(): Connection = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )
}
