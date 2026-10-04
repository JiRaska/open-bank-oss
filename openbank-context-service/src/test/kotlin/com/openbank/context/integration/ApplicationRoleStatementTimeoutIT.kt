// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

private const val MIGRATION = "db/migration/V14__application_role_statement_timeout.sql"

/**
 * The 750 ms statement backstop lives on the application role (V14), not on the cluster: a
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

    /**
     * The deployed Flyway role is the CNPG database owner: NOSUPERUSER, NOCREATEROLE. The test
     * container connects as a superuser, which would pass any ALTER ROLE, so this switches to a
     * role with the deployed attributes and runs the migration's own text. If V14 needed more
     * privilege than "alter your own defaults", the pod would crashloop at Flyway; here it fails.
     */
    @Test
    fun `the migration succeeds for a non-superuser owner and cannot touch another role`() {
        val probe = "ctx_v14_probe"
        val migration = checkNotNull(javaClass.classLoader.getResource(MIGRATION)) { MIGRATION }.readText()
        connection().use { c ->
            val s = c.createStatement()
            s.execute("DROP ROLE IF EXISTS $probe")
            s.execute("CREATE ROLE $probe NOSUPERUSER NOCREATEROLE")
            try {
                s.execute("SET ROLE $probe")
                s.execute(migration)
                assertThat(settingsOf(s, probe)).contains("statement_timeout=750ms")
                // Negative: the same privilege does not reach any other role.
                val appRole = config("quarkus.datasource.username")
                assertThatThrownBy {
                    s.execute("ALTER ROLE \"$appRole\" IN DATABASE \"${c.catalog}\" SET work_mem = '1MB'")
                }.hasMessageContaining("permission denied")
            } finally {
                s.execute("RESET ROLE")
                s.execute("ALTER ROLE $probe IN DATABASE \"${c.catalog}\" RESET ALL")
                s.execute("DROP ROLE $probe")
                s.close()
            }
        }
    }

    private fun settingsOf(s: Statement, role: String): String? = s.executeQuery(
        "SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting x " +
            "JOIN pg_database d ON d.oid = x.setdatabase " +
            "WHERE x.setrole = '$role'::regrole AND d.datname = current_database()",
    ).use { rs -> if (rs.next()) rs.getString(1) else null }

    private fun config(key: String) = ConfigProvider.getConfig().getValue(key, String::class.java)

    private fun connection(): Connection = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )
}
