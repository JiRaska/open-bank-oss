// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.authz.Authorize
import com.openbank.productcatalog.infrastructure.rest.CatalogEventCursorResource
import com.openbank.productcatalog.infrastructure.rest.FeesResource
import com.openbank.productcatalog.infrastructure.rest.GenericCatalogResource
import com.openbank.productcatalog.infrastructure.rest.ProductCatalogResource
import io.quarkus.security.Authenticated
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Security contract for the newly-authenticated product catalog (issue #401). product-catalog was
 * public-by-design and carried ZERO security annotations; this locks in the cutover so a future
 * edit cannot silently re-open it:
 *   - every read (`@GET`) requires a valid token (`@Authenticated`) and carries an `@Authorize`
 *     `catalog.<read-verb>` action for the query.catalog.readonly OPA bridge;
 *   - every write (`@POST`/`@PUT`) is `@RolesAllowed(ROLE_OPERATOR|ROLE_ADMIN)`;
 *   - nothing is `@PermitAll`.
 * Pure reflection — no Quarkus boot. The functional path (operator token succeeds) is covered by
 * ProductCatalogResourceTest under `@TestSecurity`.
 */
class ProductCatalogSecurityTest {

    private val resources = listOf(
        ProductCatalogResource::class.java,
        FeesResource::class.java,
        GenericCatalogResource::class.java,
        CatalogEventCursorResource::class.java,
    )

    private fun endpoints(clazz: Class<*>, vararg verbs: Class<out Annotation>): List<Method> =
        clazz.declaredMethods.filter { m -> verbs.any { m.getAnnotation(it) != null } }

    @Test
    fun `no product-catalog endpoint is @PermitAll`() {
        resources.forEach { clazz ->
            endpoints(clazz, GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)
                .forEach { m ->
                    assertThat(m.getAnnotation(PermitAll::class.java))
                        .describedAs("%s.%s must not be @PermitAll", clazz.simpleName, m.name)
                        .isNull()
                }
        }
    }

    @Test
    fun `every read requires auth and carries a catalog read-verb Authorize action`() {
        val reads = resources.flatMap { endpoints(it, GET::class.java) }
        assertThat(reads).describedAs("expected @GET reads").isNotEmpty
        reads.forEach { m ->
            assertThat(
                m.getAnnotation(Authenticated::class.java) != null || m.getAnnotation(RolesAllowed::class.java) != null,
            ).describedAs("read %s must require authentication or a role", m.name).isTrue()
            val authorize = m.getAnnotation(Authorize::class.java)
            assertThat(authorize).describedAs("read %s must carry @Authorize", m.name).isNotNull
            assertThat(authorize.action)
                .describedAs("read %s @Authorize action must be a catalog read verb", m.name)
                .isIn("catalog.read", "catalog.list", "catalog.search")
        }
    }

    @Test
    fun `every write is role-gated to operator or admin`() {
        val writes = resources.flatMap { endpoints(it, POST::class.java, PUT::class.java) }
        assertThat(writes).describedAs("expected write endpoints").isNotEmpty
        writes.forEach { m ->
            val roles = m.getAnnotation(RolesAllowed::class.java)
            assertThat(roles).describedAs("write %s must be @RolesAllowed", m.name).isNotNull
            assertThat(roles.value.toList())
                .describedAs("write %s must retain operator/admin compatibility", m.name)
                .contains("ROLE_OPERATOR", "ROLE_ADMIN")
        }
    }
}
