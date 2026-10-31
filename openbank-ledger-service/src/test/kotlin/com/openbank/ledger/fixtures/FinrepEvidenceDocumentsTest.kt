// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files

class FinrepEvidenceDocumentsTest {
    @Test
    fun `malformed XML diagnostics do not expose source data or paths`() {
        val file = Files.createTempFile("synthetic-private-evidence-", ".xml")
        val errors = ByteArrayOutputStream()
        val original = System.err
        try {
            Files.writeString(file, "<suite private='synthetic-secret'><private.invalid></suite>")
            System.setErr(PrintStream(errors))
            val failure = assertThrows<IllegalArgumentException> { FinrepEvidenceDocuments.xml(file) }
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
            assertThat(trace + errors.toString()).doesNotContain("synthetic-secret", "private.invalid", file.toString())
            assertThat(failure.cause).isNull()
            assertThat(errors.toString()).isEmpty()
        } finally {
            System.setErr(original)
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `invalid JSON is sanitized without the parser source snippet`() {
        val file = Files.createTempFile("synthetic-private-json-", ".json")
        try {
            Files.writeString(file, "private.invalid synthetic-secret")
            val failure = assertThrows<IllegalArgumentException> { FinrepEvidenceDocuments.json(file) }
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
            assertThat(trace).doesNotContain("private.invalid", "synthetic-secret", file.toString())
            assertThat(failure.cause).isNull()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
