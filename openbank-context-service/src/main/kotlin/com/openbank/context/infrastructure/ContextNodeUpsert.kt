// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/** A deterministic row id is the arbiter; the natural identity must still match after every write. */
internal fun upsertContextNode(session: Mutiny.Session, sql: String, values: Map<String, Any>): Uni<Int> {
    val rowId = (values["rowId"] ?: values["id"]) as UUID
    val key = values["key"] as String
    val bankScope = values["bankScope"] as String
    val generation = values["generation"] as Long
    val mutation = session.createNativeMutationQuery(sql)
    values.forEach { (name, value) -> mutation.setParameter(name, value) }
    return mutation.executeUpdate().flatMap { changed ->
        session.createNativeQuery(
            """SELECT EXISTS (SELECT 1 FROM context_nodes
               WHERE node_row_id = :rowId AND node_key = :key
                 AND bank_scope = :bankScope AND projection_generation = :generation)
            """.trimIndent(),
            Boolean::class.javaObjectType,
        ).setParameter("rowId", rowId)
            .setParameter("key", key)
            .setParameter("bankScope", bankScope)
            .setParameter("generation", generation)
            .singleResult.flatMap { matches ->
                if (matches == true) {
                    Uni.createFrom().item(changed)
                } else {
                    Uni.createFrom().failure(IllegalStateException("context node row identity mismatch"))
                }
            }
    }
}
