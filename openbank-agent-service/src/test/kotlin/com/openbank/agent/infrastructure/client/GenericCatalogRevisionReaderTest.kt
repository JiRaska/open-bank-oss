// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The catalog snapshot a review is pinned to. Everything here is a fail-closed check: a review
 * that silently reasons about a *different* revision than the one requested would produce a
 * proposal whose evidence points at the wrong document.
 */
class GenericCatalogRevisionReaderTest {

    private val mapper = ObjectMapper()
    private val client = mockk<GenericCatalogReadClient>()
    private val reader = GenericCatalogRevisionReader().also {
        it.client = client
        it.objectMapper = mapper
    }

    private fun body(
        offeringId: String = "off-1",
        id: String = "rev-1",
        state: String? = "PUBLISHED",
        contentHash: String? = "abc123",
        schemaRef: Boolean = true,
    ): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("offeringId", offeringId)
        node.put("id", id)
        state?.let { node.put("state", it) }
        contentHash?.let { node.put("contentHash", it) }
        if (schemaRef) node.putObject("schemaRef").put("id", "catalog").put("version", "2.1")
        return node
    }

    private fun read(node: ObjectNode) = run {
        every { client.getRevision("off-1", "rev-1") } returns node
        reader.get("off-1", "rev-1")
    }

    @Test
    fun `a well-formed revision maps onto the snapshot, schemaRef flattened to id at version`() {
        val snapshot = read(body())

        assertThat(snapshot.offeringId).isEqualTo("off-1")
        assertThat(snapshot.revisionId).isEqualTo("rev-1")
        assertThat(snapshot.state).isEqualTo("PUBLISHED")
        assertThat(snapshot.schemaRef).isEqualTo("catalog@2.1")
        assertThat(snapshot.contentHash).isEqualTo("abc123")
        assertThat(mapper.readTree(snapshot.document)).isEqualTo(body())
    }

    @Test
    fun `a revision that does not match the request is refused rather than reviewed`() {
        assertThatThrownBy { read(body(id = "rev-OTHER")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("different from the requested snapshot")

        assertThatThrownBy { read(body(offeringId = "off-OTHER")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("different from the requested snapshot")
    }

    @Test
    fun `a missing or blank required field is rejected by name`() {
        assertThatThrownBy { read(body(state = null)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing 'state'")

        assertThatThrownBy { read(body().also { it.put("id", "  ") }) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing 'id'")
    }

    @Test
    fun `schemaRef must be an object - a scalar of the same name is not accepted`() {
        assertThatThrownBy { read(body(schemaRef = false)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing object 'schemaRef'")

        assertThatThrownBy { read(body(schemaRef = false).also { it.put("schemaRef", "catalog@2.1") }) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing object 'schemaRef'")
    }

    @Test
    fun `a blank or absent contentHash becomes null, never an empty string`() {
        assertThat(read(body(contentHash = null)).contentHash).isNull()
        assertThat(read(body(contentHash = "   ")).contentHash).isNull()
    }
}
