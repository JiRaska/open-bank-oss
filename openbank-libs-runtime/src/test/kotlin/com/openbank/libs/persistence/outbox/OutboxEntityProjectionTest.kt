// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.persistence.outbox

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.metamodel.MappingMetamodel
import org.hibernate.metamodel.mapping.AttributeMapping
import org.hibernate.metamodel.mapping.EntityDiscriminatorMapping
import org.hibernate.metamodel.mapping.EntityIdentifierMapping
import org.hibernate.metamodel.mapping.SelectableConsumer
import org.hibernate.metamodel.mapping.SelectableMapping
import org.hibernate.metamodel.mapping.SoftDeleteMapping
import org.hibernate.persister.entity.EntityPersister
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class OutboxEntityProjectionTest {
    @Test
    fun `projection retains identifier and mapped service extensions without guessing Java fields`() {
        val mapping = outboxMapping("test_outbox", "id", "payload", "trace_context", "claim_token", "\"serviceField\"")
        assertThat(OutboxEntityProjection.resolve(mapping, PanacheOutboxEntity::class.java, "test_outbox"))
            .isEqualTo("id, payload, trace_context, claim_token, \"serviceField\"")
    }

    @Test
    fun `projection deduplicates inherited columns while retaining their order`() {
        val mapping = outboxMapping("test_outbox", "id", "payload", "id", "payload", "trace_context")
        assertThat(OutboxEntityProjection.resolve(mapping, PanacheOutboxEntity::class.java, "test_outbox"))
            .isEqualTo("id, payload, trace_context")
    }

    @Test
    fun `projection retains a physical inheritance discriminator and soft delete column`() {
        val mapping = outboxMapping("test_outbox", "id", "payload")
        val descriptor = mapping.getEntityDescriptor(PanacheOutboxEntity::class.java)
        val discriminator = mockk<EntityDiscriminatorMapping>()
        every { descriptor.discriminatorMapping } returns discriminator
        every { discriminator.hasPhysicalColumn() } returns true
        every { discriminator.forEachSelectable(any<SelectableConsumer>()) } answers {
            firstArg<SelectableConsumer>().accept(0, selectable("test_outbox", "kind"))
            1
        }
        val softDelete = mockk<SoftDeleteMapping>()
        every { descriptor.softDeleteMapping } returns softDelete
        every { softDelete.isFormula } returns false
        every { softDelete.containingTableExpression } returns "test_outbox"
        every { softDelete.selectionExpression } returns "deleted"
        assertThat(OutboxEntityProjection.resolve(mapping, PanacheOutboxEntity::class.java, "test_outbox"))
            .isEqualTo("id, payload, kind, deleted")
    }

    @Test
    fun `a mapping for another outbox table is rejected`() {
        val mapping = outboxMapping("other_outbox", "id", "payload")
        assertThatThrownBy {
            OutboxEntityProjection.resolve(mapping, PanacheOutboxEntity::class.java, "test_outbox")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `formula columns are rejected before a native query can run`() {
        val mapping = outboxMapping("test_outbox", "id", "payload")
        val descriptor = mapping.getEntityDescriptor(PanacheOutboxEntity::class.java)
        val formula = selectable("test_outbox", "upper(payload)")
        every { formula.isFormula } returns true
        attributeSelections(descriptor, listOf(formula))
        assertThatThrownBy {
            OutboxEntityProjection.resolve(mapping, PanacheOutboxEntity::class.java, "test_outbox")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `secondary table columns are rejected instead of silently omitted`() {
        val mapping = outboxMapping("test_outbox", "id", "payload")
        attributeSelections(
            mapping.getEntityDescriptor(PanacheOutboxEntity::class.java),
            listOf(selectable("secondary_outbox", "extra")),
        )
        assertThatThrownBy {
            OutboxEntityProjection.resolve(mapping, PanacheOutboxEntity::class.java, "test_outbox")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

internal fun outboxMapping(table: String, vararg columns: String): MappingMetamodel {
    require(columns.isNotEmpty())
    val mapping = mockk<MappingMetamodel>()
    val descriptor = mockk<EntityPersister>()
    val identifier = mockk<EntityIdentifierMapping>()
    every { mapping.getEntityDescriptor(PanacheOutboxEntity::class.java) } returns descriptor
    every { descriptor.identifierTableDetails.tableName } returns table
    every { descriptor.identifierMapping } returns identifier
    every { identifier.forEachSelectable(any<SelectableConsumer>()) } answers {
        firstArg<SelectableConsumer>().accept(0, selectable(table, columns.first()))
        1
    }
    every { descriptor.discriminatorMapping } returns null
    every { descriptor.softDeleteMapping } returns null
    attributeSelections(descriptor, columns.drop(1).map { selectable(table, it) })
    return mapping
}

private fun selectable(table: String, column: String): SelectableMapping = mockk {
    every { isFormula } returns false
    every { containingTableExpression } returns table
    every { selectionExpression } returns column
}

private fun attributeSelections(descriptor: EntityPersister, columns: List<SelectableMapping>) {
    val attribute = mockk<AttributeMapping>()
    every { attribute.forEachSelectable(any<SelectableConsumer>()) } answers {
        val consumer = firstArg<SelectableConsumer>()
        columns.forEachIndexed { index, column -> consumer.accept(index, column) }
        columns.size
    }
    every { descriptor.visitSubTypeAttributeMappings(any<Consumer<in AttributeMapping>>()) } answers {
        firstArg<Consumer<AttributeMapping>>().accept(attribute)
    }
}
