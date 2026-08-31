// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.testing.evidence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Append-only, secret-free runtime evidence emitted by shared Testcontainers resources.
 * CI supplies [OPENBANK_TEST_EVIDENCE_DIR]; local tests remain unaffected when it is absent.
 * Host names, mapped ports, credentials and container ids are deliberately never recorded.
 */
object TestInfrastructureEvidence {
    private const val EVIDENCE_DIR = "OPENBANK_TEST_EVIDENCE_DIR"

    @Synchronized
    fun record(
        resource: String,
        image: String,
        lifecycle: String,
        resourceScopeId: String? = null,
        observedAt: Instant = Instant.now(),
    ) {
        val directory = System.getenv(EVIDENCE_DIR)?.takeIf { it.isNotBlank() } ?: return
        require(lifecycle == "started" || lifecycle == "stopped") { "unsupported lifecycle" }
        require(resourceScopeId == null || UUID.fromString(resourceScopeId).toString() == resourceScopeId) {
            "resource scope id must be a canonical UUID"
        }
        val path = Path.of(directory).resolve("testcontainers.jsonl")
        Files.createDirectories(path.parent)
        val scopeField = resourceScopeId?.let { ",\"resourceScopeId\":\"${escape(it)}\"" }.orEmpty()
        val line =
            """{"schemaVersion":1,"resource":"${escape(
                resource,
            )}","image":"${escape(image)}","lifecycle":"$lifecycle","observedAt":"$observedAt"$scopeField}""" +
                "\n"
        Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
