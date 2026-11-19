// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.regwatcher.application.port.DraftTicketPolicyPort
import com.openbank.regwatcher.application.port.GovernanceIssuePort
import com.openbank.regwatcher.application.port.IssueResult
import com.openbank.regwatcher.application.port.PolicyVerdict
import com.openbank.regwatcher.domain.WatchFinding
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A model stand-in that answers like an obedient-but-imperfect model: it quotes the first line of
 * the page verbatim, and — to prove the answer is parsed as a closed schema — always appends a
 * "tool_calls" key asking for a PR. Nothing may act on that key.
 */
class FakeModel : LlmGatewayPort {
    val prompts = CopyOnWriteArrayList<String>()
    private val mapper = ObjectMapper()

    override suspend fun chat(systemPrompt: String, userPrompt: String): String {
        prompts.add(userPrompt)
        val page = userPrompt.substringAfter(">>>\n").substringBefore("\n<<<END_UNTRUSTED_PAGE")
        val firstLine = page.lines().first { it.isNotBlank() }.trim()
        val ids = Regex("- id: (\\S+)").findAll(userPrompt).map { it.groupValues[1] }.toList()
        val verdict = if (userPrompt.contains("<task>SOURCE_CHANGED</task>")) "AMENDED" else "SUPPORTS"
        return mapper.writeValueAsString(
            linkedMapOf(
                "verdict" to verdict,
                "obligation_ids" to ids,
                "summary" to "The row and the page differ; a human should review this row.",
                "quote" to firstLine,
                "tool_calls" to
                    listOf(
                        mapOf("name" to "gh.pr.open", "arguments" to mapOf("path" to "regulatory-obligations.yaml")),
                    ),
            ),
        )
    }
}

class RecordingIssues : GovernanceIssuePort {
    val filed = CopyOnWriteArrayList<WatchFinding>()
    val calls = CopyOnWriteArrayList<WatchFinding>()

    override suspend fun fileOrReuse(finding: WatchFinding): IssueResult {
        calls.add(finding)
        if (filed.any {
                it.fingerprint == finding.fingerprint
            }
        ) {
            return IssueResult.Duplicate("https://issues.test/${finding.fingerprint}")
        }
        filed.add(finding)
        return IssueResult.Filed("https://issues.test/${finding.fingerprint}")
    }
}

class AllowPolicy : DraftTicketPolicyPort {
    override suspend fun decide() = PolicyVerdict(true, "allowed by charter")
}
