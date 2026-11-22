// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import jakarta.persistence.metamodel.Metamodel
import org.hibernate.metamodel.MappingMetamodel
import org.hibernate.metamodel.mapping.SelectableMapping

/**
 * The native query must return exactly the mapped row, not every column of a growing table.
 * Use the ORM's resolved mapping, including mapped superclasses, overrides and service fields;
 * a second hand-maintained column list would drift from the entity it hydrates.
 */
internal object OutboxEntityProjection {
    fun resolve(metamodel: Metamodel, entityClass: Class<*>, table: String): String {
        check(metamodel is MappingMetamodel) { "Outbox row projection requires the Hibernate mapping metamodel" }
        val descriptor = metamodel.getEntityDescriptor(entityClass)
        val mappedTable = descriptor.identifierTableDetails.tableName
        require(mappedTable.substringAfterLast('.').removeSurrounding("\"") == table) {
            "Outbox entity must map the configured outbox table"
        }
        val columns = linkedSetOf<String>()
        fun include(selectable: SelectableMapping) {
            require(!selectable.isFormula && selectable.containingTableExpression == mappedTable) {
                "Native outbox row reads require physical columns from the outbox table"
            }
            check(selectable.selectionExpression.isNotBlank()) { "Outbox mapped column must not be blank" }
            columns.add(selectable.selectionExpression)
        }
        descriptor.identifierMapping.forEachSelectable { _, selectable -> include(selectable) }
        descriptor.visitSubTypeAttributeMappings { attribute ->
            attribute.forEachSelectable { _, selectable -> include(selectable) }
        }
        descriptor.discriminatorMapping?.let { discriminator ->
            require(discriminator.hasPhysicalColumn()) { "Native outbox row reads require a physical discriminator" }
            discriminator.forEachSelectable { _, selectable -> include(selectable) }
        }
        descriptor.softDeleteMapping?.let { include(it) }
        check(columns.isNotEmpty()) { "Outbox entity must map a nonempty row" }
        return columns.joinToString(", ")
    }
}

/** One current metamodel binding per repository; no session or global factory registry is retained. */
internal class OutboxEntityProjectionCache {
    @Volatile
    private var cached: Pair<Metamodel, String>? = null

    fun resolve(metamodel: Metamodel, entityClass: Class<*>, table: String): String {
        val current = cached
        if (current?.first === metamodel) return current.second
        return OutboxEntityProjection.resolve(metamodel, entityClass, table).also {
            cached = metamodel to it
        }
    }
}
