// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Against the REAL ČNB workbook `PMR_historie_zmen.xlsx` (downloaded 2026-10-04; committed with
 * only the printer-settings binaries removed — every sheet, string and cell is the original), plus
 * malformed variants derived from it, each of which must reject the WHOLE file.
 */
class CnbMinimumReserveParserTest {

    private val real: ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/cnb/policy-rates/PMR_historie_zmen.xlsx")).readBytes()

    private fun obs(date: String, rate: String) = CnbPolicyRateObservation(LocalDate.parse(date), BigDecimal(rate))

    @Test
    fun `the real workbook yields the ratio and remuneration history as change points`() {
        val r = CnbMinimumReserveParser.parse(real)

        // 1999-10-07: the first single-ratio row (2 % for every institution); unchanged until
        // 2025-01-02 (4 %). Earlier multi-ratio rows (bank / building society) are pre-history.
        assertThat(r.ratio.map { it.effectiveFrom to it.rate.stripTrailingZeros() })
            .containsExactly(
                LocalDate.of(1999, 10, 7) to BigDecimal("0.02"),
                LocalDate.of(2025, 1, 2) to BigDecimal("0.04"),
            )
        // 2023-10-05: the first unconditional fixed remuneration (0 %); before it the file states
        // the 2W repo rate (conditionally floored), which is not a fixed number.
        assertThat(r.remuneration).hasSize(1)
        assertThat(r.remuneration.single().effectiveFrom).isEqualTo(LocalDate.of(2023, 10, 5))
        assertThat(r.remuneration.single().rate).isEqualByComparingTo("0")
        assertThat(r.ratio.last()).usingRecursiveComparison()
            .withComparatorForType(BigDecimal::compareTo, BigDecimal::class.java)
            .isEqualTo(obs("2025-01-02", "0.04"))
    }

    @Test
    fun `an ambiguous ratio after the history began rejects the whole file`() {
        val broken = setText(real, "2025", "D9", "4 % ze základny bank, 3 % ze základny spořitelen")
        assertThatThrownBy { CnbMinimumReserveParser.parse(broken) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not a single rate after the history began")
    }

    @Test
    fun `a remuneration wording that stops being a fixed number rejects the whole file`() {
        val broken = rewrite(real, "xl/sharedStrings.xml") {
            it.replace("PMR se úročí úrokovou sazbou ve výši 0 %", "PMR se úročí 2T repo sazbou")
        }
        // Changing the wording everywhere means the history never begins — still a valid file.
        // Changing it only on 2025, AFTER 2023 began the history, must reject the file.
        val r = CnbMinimumReserveParser.parse(broken)
        assertThat(r.remuneration).isEmpty()
        val mixed = setText(real, "2025", "F9", "PMR se úročí dle rozhodnutí bankovní rady")
        assertThatThrownBy { CnbMinimumReserveParser.parse(mixed) }.hasMessageContaining("MIN_RESERVE_REMUNERATION")
    }

    @Test
    fun `a percentage above 100 rejects the whole file`() {
        val broken = setText(real, "2025", "D9", "400 % ze základny")
        assertThatThrownBy { CnbMinimumReserveParser.parse(broken) }.hasMessageContaining("above 100")
    }

    @Test
    fun `a date outside its sheet's year rejects the whole file`() {
        val broken = rewrite(real, sheetPart("2025")) { it.replace("<v>45659</v>", "<v>45000</v>") }
        assertThatThrownBy { CnbMinimumReserveParser.parse(broken) }.hasMessageContaining("outside the sheet's year")
    }

    @Test
    fun `a sheet without the header labels rejects the whole file`() {
        val broken = rewrite(real, "xl/sharedStrings.xml") { it.replace(">Úročení<", ">Poznámka<") }
        assertThatThrownBy { CnbMinimumReserveParser.parse(broken) }.hasMessageContaining("header row")
    }

    @Test
    fun `the header is found by its labels, not by coordinates`() {
        // Shift every cell of every sheet one column right: same content, different coordinates.
        var shifted = real
        for (part in sheetParts(real)) {
            shifted = rewrite(shifted, part) { xml ->
                xml.replace(Regex("""r="([A-Z])(\d+)"""")) { m ->
                    "r=\"${m.groupValues[1][0] + 1}${m.groupValues[2]}\""
                }
            }
        }
        assertThat(CnbMinimumReserveParser.parse(shifted).ratio.map { it.effectiveFrom })
            .containsExactly(LocalDate.of(1999, 10, 7), LocalDate.of(2025, 1, 2))
    }

    @Test
    fun `not a workbook, an HTML page, and an empty body are rejected`() {
        assertThatThrownBy { CnbMinimumReserveParser.parse("<!DOCTYPE html><html></html>".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CnbMinimumReserveParser.parse(ByteArray(0))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a zip bomb entry is refused while reading, not after`() {
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("xl/workbook.xml"))
                val zeros = ByteArray(1 shl 20)
                repeat(9) { z.write(zeros) }
                z.closeEntry()
            }
        }.toByteArray()
        assertThatThrownBy { SafeXlsxReader.read(bomb) }.hasMessageContaining("exceeds")
    }

    @Test
    fun `a DOCTYPE with an external entity is never resolved`() {
        val xxe = rewrite(real, "xl/sharedStrings.xml") {
            it.replaceFirst("?>", "?><!DOCTYPE sst [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>")
                .replaceFirst("Základna", "&x;")
        }
        assertThatThrownBy { CnbMinimumReserveParser.parse(xxe) }.isInstanceOf(Exception::class.java)
    }

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun rewrite(bytes: ByteArray, part: String, transform: (String) -> String): ByteArray {
        val all = entries(bytes)
        val before = String(requireNotNull(all[part]) { "no part $part" }, Charsets.UTF_8)
        val after = transform(before)
        check(after != before) { "the variant changed nothing in $part — it would prove nothing" }
        all[part] = after.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                all.forEach { (n, b) ->
                    z.putNextEntry(ZipEntry(n))
                    z.write(b)
                    z.closeEntry()
                }
            }
        }.toByteArray()
    }

    /** Replaces one cell of one sheet with an inline string. */
    private fun setText(bytes: ByteArray, sheet: String, ref: String, text: String): ByteArray =
        rewrite(bytes, sheetPart(sheet)) {
            it.replace(Regex("""<c r="$ref"[^>]*?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)) {
                """<c r="$ref" t="inlineStr"><is><t>$text</t></is></c>"""
            }
        }

    private fun sheetParts(bytes: ByteArray) = entries(bytes).keys.filter { it.startsWith("xl/worksheets/sheet") }

    /** The worksheet part behind a tab name, resolved the way the reader does (workbook rels). */
    private fun sheetPart(name: String): String {
        val all = entries(real)
        val wb = String(all.getValue("xl/workbook.xml"), Charsets.UTF_8)
        val rid = Regex("""<sheet [^>]*name="$name"[^>]*r:id="([^"]+)"""").find(wb)!!.groupValues[1]
        val rels = String(all.getValue("xl/_rels/workbook.xml.rels"), Charsets.UTF_8)
        val target = Regex(
            """<Relationship [^>]*Id="$rid"[^>]*Target="([^"]+)"|<Relationship [^>]*Target="([^"]+)"[^>]*Id="$rid"""",
        )
            .find(rels)!!.groupValues.drop(1).first { it.isNotEmpty() }
        return "xl/$target"
    }
}
