// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every inbound ISO 20022 entry point must refuse a document carrying a DTD — the external-entity
 * read, the external-DTD fetch and the internal entity-expansion bomb alike — and must do so on
 * the DOCTYPE itself, before any entity is resolved. The assertion is on the DOCTYPE refusal (not
 * merely "something threw"): the JDK's own entity-expansion limit would also stop the bomb, so
 * only this message proves the parser is the hardened one from SecureXml.
 */
class XxeRejectionTest {
    /** Runs [xml] through one reader and returns the failure text, or null if it was accepted. */
    fun interface Entry {
        fun failureOf(xml: String): String?
    }

    @ParameterizedTest(name = "{0} refuses {2}")
    @MethodSource("cases")
    fun `every inbound reader refuses a DTD before resolving anything`(
        reader: String,
        entry: Entry,
        payloadName: String,
        payload: (Path) -> String,
        @TempDir dir: Path,
    ) {
        val secret = dir.resolve("secret.txt")
        Files.writeString(secret, MARKER)

        val failure = entry.failureOf(payload(secret))

        assertThat(failure).describedAs("$reader accepted $payloadName").isNotNull()
        assertThat(failure).contains("DOCTYPE")
        assertThat(failure).doesNotContain(MARKER)
    }

    companion object {
        private const val MARKER = "xxe-marker-must-never-be-read"

        private fun thrown(block: () -> Any?): String? = try {
            block()
            null
        } catch (e: Exception) {
            generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        }

        private fun body(root: String) = "<$root>&e;</$root>"

        private val externalEntity: (Path) -> String = { f ->
            "<?xml version=\"1.0\"?><!DOCTYPE Document [<!ENTITY e SYSTEM \"${f.toUri()}\">]>" + body("Document")
        }
        private val externalDtd: (Path) -> String = { f ->
            "<?xml version=\"1.0\"?><!DOCTYPE Document SYSTEM \"${f.toUri()}\"><Document/>"
        }
        private val expansionBomb: (Path) -> String = { _ ->
            val levels = (1..9).joinToString("") { i -> "<!ENTITY l$i \"${"&l${i - 1};".repeat(10)}\">" }
            "<?xml version=\"1.0\"?><!DOCTYPE Document [<!ENTITY l0 \"x\">$levels<!ENTITY e \"&l9;\">]>" +
                body("Document")
        }

        @JvmStatic
        fun cases(): List<Arguments> {
            val validator = Iso20022Validator.forSchema("pacs.008.001.08.xsd")
            val entries = listOf(
                "Pacs008Reader" to Entry { xml -> thrown { Pacs008Reader().read(xml) } },
                "Pacs004Reader" to Entry { xml -> thrown { Pacs004Reader().read(xml) } },
                "Pacs002Reader" to Entry { xml -> thrown { Pacs002Reader().read(xml) } },
                "Iso20022Validator" to Entry { xml ->
                    when (val r = validator.validate(xml)) {
                        is Iso20022ValidationResult.Valid -> null
                        is Iso20022ValidationResult.Invalid -> r.errors.joinToString(" | ")
                    }
                },
            )
            val payloads = listOf(
                "an external entity" to externalEntity,
                "an external DTD" to externalDtd,
                "an entity-expansion bomb" to expansionBomb,
            )
            return entries.flatMap { (name, entry) ->
                payloads.map { (pName, p) -> Arguments.of(name, entry, pName, p) }
            }
        }
    }
}
