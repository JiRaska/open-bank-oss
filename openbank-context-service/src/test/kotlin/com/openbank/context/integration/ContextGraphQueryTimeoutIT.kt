// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.boundedGraphRead
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextGraphQueryTimeoutIT {
    @Inject lateinit var sessions: Mutiny.SessionFactory

    @Test
    fun `graph reads set a transaction local PostgreSQL statement timeout`() {
        val configured = ConfigProvider.getConfig().getValue("openbank.context.query-timeout-ms", Int::class.java)
        val actual = VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async {
                sessions.boundedGraphRead(configured) { session ->
                    session.createNativeQuery(
                        "select (extract(epoch from current_setting('statement_timeout')::interval) * 1000)::int",
                        Int::class.javaObjectType,
                    )
                        .singleResult
                }.awaitSuspending()
            }.asUni()
        }
        assertThat(actual).isEqualTo(configured)
    }
}
