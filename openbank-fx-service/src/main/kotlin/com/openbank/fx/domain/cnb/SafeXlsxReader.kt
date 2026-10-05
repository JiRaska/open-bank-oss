// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import com.openbank.libs.xml.SecureXml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * A deliberately minimal, JDK-only reader for the CELL VALUES of an `.xlsx` workbook — enough to
 * read the ČNB minimum-reserve history, nothing more (no formulas, styles or dates: a date is the
 * raw serial and the caller converts it). JDK-only so the money-path service takes no new
 * third-party dependency (no Apache POI and its transitive set) for one small official file.
 *
 * Hardened for untrusted input, failing with [IllegalArgumentException] on any violation:
 *  - zip-bomb limits on entry count, per-entry and total UNCOMPRESSED size, enforced while reading
 *    (the declared sizes in a zip header are not trusted);
 *  - StAX with DTDs and external entities disabled (no XXE, no entity expansion);
 *  - entries are looked up by exact name only — nothing is ever written to disk, so zip-slip paths
 *    have nothing to escape into.
 */
object SafeXlsxReader {

    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    private const val BUFFER = 8192

    /** One sheet: its tab name and its non-empty cells by A1 reference (`B9` -> value). */
    data class Sheet(val name: String, val cells: Map<String, Cell>)

    /** A cell value: [text] for strings, [number] (raw, as written) for numeric cells. */
    data class Cell(val text: String?, val number: String?)

    fun read(bytes: ByteArray): List<Sheet> {
        val entries = unzip(bytes)
        val workbook = requireNotNull(entries["xl/workbook.xml"]) { "not an xlsx workbook: no xl/workbook.xml" }
        val rels = requireNotNull(entries["xl/_rels/workbook.xml.rels"]) { "xlsx without workbook relationships" }
        val shared = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val targets = relationships(rels)
        return sheets(workbook).map { (name, rid) ->
            val target = requireNotNull(targets[rid]) { "sheet '$name' has no relationship '$rid'" }
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val xml = requireNotNull(entries[path]) { "sheet '$name' part '$path' is missing" }
            Sheet(name, cells(xml, shared))
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        val budget = longArrayOf(0L) // total inflated bytes so far, across entries
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                require(out.size < MAX_ENTRIES) { "xlsx has more than $MAX_ENTRIES entries" }
                val data = inflate(zip, entry.name, budget)
                require(out.put(entry.name, data) == null) { "duplicate xlsx entry '${entry.name}'" }
            }
        }
        require(out.isNotEmpty()) { "payload is not a zip archive" }
        return out
    }

    /** Reads one entry, enforcing the per-entry and total limits on the bytes actually inflated. */
    private fun inflate(zip: ZipInputStream, name: String, budget: LongArray): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER)
        var size = 0L
        var n = zip.read(chunk)
        while (n >= 0) {
            size += n
            budget[0] += n
            require(size <= MAX_ENTRY_BYTES) { "xlsx entry '$name' exceeds $MAX_ENTRY_BYTES bytes" }
            require(budget[0] <= MAX_TOTAL_BYTES) { "xlsx exceeds $MAX_TOTAL_BYTES uncompressed bytes" }
            buf.write(chunk, 0, n)
            n = zip.read(chunk)
        }
        return buf.toByteArray()
    }

    private val factory: XMLInputFactory = SecureXml.xmlInputFactory()

    private fun <T> parse(xml: ByteArray, block: (XMLStreamReader) -> T): T {
        val r = factory.createXMLStreamReader(ByteArrayInputStream(xml))
        try {
            return block(r)
        } finally {
            r.close()
        }
    }

    private fun attr(r: XMLStreamReader, local: String): String? =
        (0 until r.attributeCount).firstOrNull { r.getAttributeLocalName(it) == local }?.let { r.getAttributeValue(it) }

    private fun sheets(xml: ByteArray): List<Pair<String, String>> = parse(xml) { r ->
        val out = mutableListOf<Pair<String, String>>()
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "sheet") {
                out += requireNotNull(attr(r, "name")) { "sheet without a name" } to
                    requireNotNull(attr(r, "id")) { "sheet without r:id" }
            }
        }
        out
    }

    private fun relationships(xml: ByteArray): Map<String, String> = parse(xml) { r ->
        val out = mutableMapOf<String, String>()
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "Relationship") {
                val id = attr(r, "Id")
                val target = attr(r, "Target")
                if (id != null && target != null) out[id] = target
            }
        }
        out
    }

    /** Every `<si>`, concatenating its `<t>` runs; phonetic `<rPh>` runs are not part of the text. */
    private fun sharedStrings(xml: ByteArray): List<String> = parse(xml) { r ->
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inPhonetic = false
        var inText = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "si" -> current.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inText = !inPhonetic
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inText) current.append(r.text)
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "si" -> out += current.toString()
                    "rPh" -> inPhonetic = false
                    "t" -> inText = false
                }
            }
        }
        out
    }

    private fun cells(xml: ByteArray, shared: List<String>): Map<String, Cell> = parse(xml) { r ->
        val out = mutableMapOf<String, Cell>()
        var ref: String? = null
        var type: String? = null
        val value = StringBuilder()
        var capture = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "c" -> {
                        ref = attr(r, "r")
                        type = attr(r, "t")
                        value.setLength(0)
                    }
                    "v", "t" -> capture = ref != null
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (capture) value.append(r.text)
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val at = ref
                        if (at != null && value.isNotEmpty()) out[at] = cell(type, value.toString(), shared, at)
                        ref = null
                    }
                }
            }
        }
        out
    }

    private fun cell(type: String?, raw: String, shared: List<String>, ref: String): Cell = when (type) {
        "s" -> {
            val i = requireNotNull(raw.trim().toIntOrNull()) { "cell $ref: shared-string index '$raw'" }
            require(i in shared.indices) { "cell $ref: shared-string index $i out of range" }
            Cell(shared[i], null)
        }
        "inlineStr", "str" -> Cell(raw, null)
        null, "n" -> Cell(null, raw.trim())
        else -> Cell(raw, null) // b / e: never a value this reader is asked for
    }
}
