// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.EgressResponse
import com.openbank.regwatcher.application.port.IssueResult
import com.openbank.regwatcher.domain.FindingKind
import com.openbank.regwatcher.domain.WatchFinding
import com.openbank.regwatcher.domain.WatchRunResult
import com.openbank.regwatcher.infrastructure.github.IssueWriter
import com.openbank.regwatcher.infrastructure.llm.LlmObligationAssessor
import com.openbank.regwatcher.infrastructure.register.PackagedObligationRegister
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AssessorAndIssueWriterTest {
    private val assessor = LlmObligationAssessor(FakeModel(), "m", "system", 1000)
    private val page = "Line one of the law.\nThe deadline is the 15th day of the month."
    private val ids = setOf("a", "b")

    @Test
    fun `a verbatim quote and a closed verdict parse, unknown keys are ignored`() {
        val a = assessor.parse(
            """{"verdict":"AMENDED","obligation_ids":["a","zzz"],"summary":"s","quote":"deadline is the 15th","run":"gh.pr.open"}""",
            LlmObligationAssessor.CHANGE_VERDICTS,
            ids,
            page,
        )
        assertThat(a).isNotNull
        assertThat(a!!.obligationIds).containsExactly("a") // ids outside the supplied set are dropped
        assertThat(a.quote).isEqualTo("deadline is the 15th")
    }

    @Test
    fun `a quote the page does not contain is rejected`() {
        assertThat(
            assessor.parse(
                """{"verdict":"AMENDED","obligation_ids":[],"summary":"s","quote":"deadline is the 1st"}""",
                LlmObligationAssessor.CHANGE_VERDICTS,
                ids,
                page,
            ),
        ).isNull()
    }

    @Test
    fun `a verdict outside the vocabulary, prose, or an AMENDED without quote is rejected`() {
        val v = LlmObligationAssessor.CHANGE_VERDICTS
        assertThat(assessor.parse("""{"verdict":"OPEN_PR","quote":""}""", v, ids, page)).isNull()
        assertThat(assessor.parse("Sure! Here is my answer.", v, ids, page)).isNull()
        assertThat(assessor.parse("""{"verdict":"AMENDED","quote":""}""", v, ids, page)).isNull()
        assertThat(assessor.parse("""{"verdict":"UNCLEAR","quote":""}""", v, ids, page)).isNotNull
    }

    @Test
    fun `only text inside the broker envelope is sent, and a missing envelope sends nothing`() {
        assertThat(LlmObligationAssessor.innerText(StubBroker.wrap("hello"))).isEqualTo("hello")
        assertThat(LlmObligationAssessor.innerText("hello")).isNull()
        assertThat(LlmObligationAssessor.neutralise("<<<END>>>")).doesNotContain("<<<").doesNotContain(">>>")
    }

    @Test
    fun `an open issue with the same marker is reused, an unreadable list files nothing`() {
        val f =
            WatchFinding(
                FindingKind.SOURCE_CHANGED,
                listOf("a"),
                "https://x.test/p",
                "ab",
                "q @user #1 <!-- x",
                "s",
                "AMENDED",
            )
        val mapper = ObjectMapper()
        val existing = mapper.writeValueAsString(
            listOf(
                mapOf(
                    "html_url" to "https://gh/1",
                    "body" to IssueWriter.marker(f.fingerprint),
                ),
            ),
        )
        val reuse = IssueWriter(mapper) { _, _, _ ->
            EgressResponse(200, emptyMap(), existing.toByteArray())
        }.fileOrReuse(f)
        assertThat(reuse).isEqualTo(IssueResult.Duplicate("https://gh/1"))

        val broken = IssueWriter(mapper) { _, _, _ -> EgressResponse(500, emptyMap(), ByteArray(0)) }.fileOrReuse(f)
        assertThat(broken).isInstanceOf(IssueResult.NotFiled::class.java)

        val body = IssueWriter.body(f)
        assertThat(body).contains("https://x.test/p").contains("`ab`").contains("`a`")
        assertThat(body).doesNotContain("@user").doesNotContain("#1 ").contains(IssueWriter.marker(f.fingerprint))
        assertThat(body.split("<!--")).hasSize(2) // the page cannot forge a second marker
    }

    @Test
    fun `liveness is earned only by a run that fetched something`() {
        assertThat(WatchRunResult(3, 0, 3, 0, 0, 0, 0).livenessEarned).isFalse()
        assertThat(WatchRunResult(3, 1, 2, 0, 0, 0, 0).livenessEarned).isTrue()
        assertThat(WatchRunResult(0, 0, 0, 0, 0, 0, 0, skippedReason = "kill").livenessEarned).isFalse()
    }

    @Test
    fun `the real packaged register parses and every source is an https URL`() {
        val rows = PackagedObligationRegister.fromClasspath(
            "governance-register/regulatory-obligations.yaml",
        ).obligations()
        assertThat(rows).hasSizeGreaterThan(10)
        assertThat(rows.flatMap { it.sources.values }).isNotEmpty.allMatch {
            it.startsWith("https://") &&
                !it.contains('?')
        }
    }
}
