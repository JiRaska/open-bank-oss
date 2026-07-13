// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.adapter

import com.openbank.authzaudit.application.port.out.PolicyScanPort
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot
import com.openbank.authzaudit.domain.model.CharterAllowToken
import com.openbank.authzaudit.domain.model.CharterDenyPattern
import com.openbank.authzaudit.domain.model.PrincipalTypeComparison
import com.openbank.authzaudit.domain.model.RestBypassReference
import com.openbank.authzaudit.domain.model.UnwrappedAgentIdComparison
import com.openbank.authzaudit.infrastructure.config.AuthzPolicyAuditorConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Direct repo-checkout grep/text-scan of the fleet's canonical OPA/Rego authorization sources, the
 * `AuthorizeInterceptor` that feeds them the principal-type/id vocabulary, and the `agents.yaml`
 * charter registry (`read.governance`, ADR-0167 checks 1-4). Deliberately shallow — not a Rego AST
 * parser — mirroring `check-no-service-principal-type.sh`'s own "stdlib-only (grep); no opa/rego-
 * parser dependency" design and docs-truth-agent's/release-steward's real (not stubbed) grep/
 * text-scan adapter precedent, since this agent runs from within the monorepo.
 */
@ApplicationScoped
class PolicyScanAdapter(private val config: AuthzPolicyAuditorConfig) : PolicyScanPort {

    private val log = Logger.getLogger(PolicyScanAdapter::class.java)

    override suspend fun scan(): AuthzPolicySnapshot {
        val root = Path.of(config.repoRoot())
        log.infof("Scanning OPA/Rego policy sources and agents.yaml under %s", root.toAbsolutePath())

        val regoFiles = RegoFiles.discover(root)
        val ruleFiles = regoFiles.filterNot { it.name.endsWith("_test.rego") }
        val emittedPrincipalTypes = AuthorizeInterceptorScanner.emittedPrincipalTypes(root)

        val principalTypeComparisons = ruleFiles.flatMap { PolicyTextScanner.principalTypeComparisons(root, it) }
        val unwrappedAgentIdComparisons =
            ruleFiles.flatMap { PolicyTextScanner.unwrappedAgentIdComparisons(root, it) }
        val restBypassReferences = regoFiles
            .filterNot { it.name == "agents.rego" || it.name == "agents_test.rego" }
            .flatMap { PolicyTextScanner.charterAllowedReferences(root, it) }

        val charterScan = AgentsYamlScanner.scan(root)

        return AuthzPolicySnapshot(
            regoFilesScanned = regoFiles.size,
            emittedPrincipalTypes = emittedPrincipalTypes,
            principalTypeComparisons = principalTypeComparisons,
            unwrappedAgentIdComparisons = unwrappedAgentIdComparisons,
            toolTiersVocabulary = charterScan.toolTiersVocabulary,
            charterAllowTokens = charterScan.allowTokens,
            charterDenyPatterns = charterScan.denyPatterns,
            restBypassReferences = restBypassReferences,
        )
    }
}

/** Locates the canonical .rego sources — deliberately scoped to the two source-of-truth
 * directories, NOT the ~30 generated per-service `*-opa-bundle.yaml` copies that
 * `gen-*-opa-bundle.sh` mechanically derives FROM these files (a known scope reduction, see
 * docs/agents/authz-policy-auditor.md: Known gaps). */
private object RegoFiles {
    private val REGO_DIRS = listOf(
        "openbank-infra/opa/policies",
        "openbank-libs/governance/policies",
    )

    fun discover(root: Path): List<Path> = REGO_DIRS
        .map { root.resolve(it) }
        .filter { it.exists() }
        .flatMap { dir ->
            Files.list(dir).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() && it.extension == "rego" }
                    .toList()
            }
        }
        .sortedBy { it.name }
}

/** Parses the actual set of principal-type values `AuthorizeInterceptor.principalType()` can
 * return today — check 1 (ADR-0167) compares live rego rules against THIS, not a hardcoded list,
 * so a future change to the interceptor's own vocabulary is picked up automatically. */
private object AuthorizeInterceptorScanner {
    private const val REL_PATH = "openbank-libs-runtime/src/main/kotlin/com/openbank/libs/authz/AuthorizeInterceptor.kt"
    private val FUNCTION_START = Regex("""fun principalType\(""")
    private val STRING_LITERAL = Regex("\"([A-Z_]+)\"")

    fun emittedPrincipalTypes(root: Path): Set<String> {
        val file = root.resolve(REL_PATH)
        if (!file.exists()) return emptySet()
        val lines = runCatching { file.readLines() }.getOrElse { return emptySet() }
        val startIndex = lines.indexOfFirst { FUNCTION_START.containsMatchIn(it) }
        if (startIndex == -1) return emptySet()
        val endIndex = ((startIndex + 1) until lines.size).firstOrNull { lines[it].trim() == "}" } ?: lines.lastIndex
        return lines.subList(startIndex, (endIndex + 1).coerceAtMost(lines.size))
            .flatMap { line -> STRING_LITERAL.findAll(line).map { it.groupValues[1] } }
            .toSet()
    }
}

/** Line-level scans over a single .rego file's text — checks 1, 2 and 4 (ADR-0167). Split out of
 * [PolicyScanAdapter] to keep that class's function count within the fleet's `TooManyFunctions`
 * detekt threshold, the same reason docs-truth-agent's `RepoScanAdapter` splits out
 * `AdrTextScanner`/`RepoFileFilter`. */
private object PolicyTextScanner {
    private val PRINCIPAL_TYPE_COMPARISON = Regex("principal\\.type\\s*==\\s*\"([A-Z_]+)\"")
    private val AGENT_ID_EQUALITY = Regex("""(==\s*input\.agent\b)|(\binput\.agent\s*==)""")
    private const val TRIM_PREFIX_AGENT = "trim_prefix(input.agent"
    private const val CHARTER_ALLOWED_TOKEN = "charter_allowed"

    // Check 1: every `principal.type == "X"` rule-body comparison (generalizes
    // check-no-service-principal-type.sh's single "SERVICE" literal to any never-emitted value).
    fun principalTypeComparisons(root: Path, file: Path): List<PrincipalTypeComparison> {
        val rel = root.relativize(file).toString()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        return lines.withIndex().mapNotNull { (idx, rawLine) ->
            if (isCommentLine(rawLine)) return@mapNotNull null
            val match = PRINCIPAL_TYPE_COMPARISON.find(rawLine) ?: return@mapNotNull null
            PrincipalTypeComparison(
                file = rel,
                line = idx + 1,
                literalValue = match.groupValues[1],
                snippet = rawLine.trim(),
            )
        }
    }

    // Check 2: input.agent compared for equality without a nearby trim_prefix(input.agent wrap on
    // the same line — the exact shape of the agent-id-prefix-mismatch defect class.
    fun unwrappedAgentIdComparisons(root: Path, file: Path): List<UnwrappedAgentIdComparison> {
        val rel = root.relativize(file).toString()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        return lines.withIndex().mapNotNull { (idx, rawLine) ->
            if (isCommentLine(rawLine)) return@mapNotNull null
            if (!AGENT_ID_EQUALITY.containsMatchIn(rawLine)) return@mapNotNull null
            if (rawLine.contains(TRIM_PREFIX_AGENT)) return@mapNotNull null
            UnwrappedAgentIdComparison(file = rel, line = idx + 1, snippet = rawLine.trim())
        }
    }

    // Check 4: any `charter_allowed` reference outside agents.rego/agents_test.rego (the only place
    // that predicate is meant to be defined and consumed) — a REST/MCP bridge bypassing agents.allow.
    fun charterAllowedReferences(root: Path, file: Path): List<RestBypassReference> {
        val rel = root.relativize(file).toString()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        return lines.withIndex().mapNotNull { (idx, rawLine) ->
            if (isCommentLine(rawLine)) return@mapNotNull null
            if (!rawLine.contains(CHARTER_ALLOWED_TOKEN)) return@mapNotNull null
            RestBypassReference(file = rel, line = idx + 1, snippet = rawLine.trim())
        }
    }

    // Excludes rego line-comments (mirrors check-no-service-principal-type.sh) so these scans flag
    // live rule bodies, not explanatory prose about the defect classes themselves — every one of
    // checks 1/2/4 has extensive comment-level prose in the current, already-fixed rego discussing
    // exactly these defect shapes, which would otherwise self-trigger every run.
    private fun isCommentLine(line: String): Boolean = line.trimStart().startsWith("#")
}

/** Best-effort, indentation-based text scan of `agents.yaml`'s `tool_tiers` vocabulary and every
 * flat-list charter's `tools.allow`/`tools.deny` — check 3 (ADR-0167). Not a full YAML parse (the
 * same "grep, not a parser" bootstrap-phase design as `GovernanceRulesAdapter`'s proximity scan in
 * docs-truth-agent) but structure-aware enough to follow `agents.yaml`'s multi-line flow-sequence
 * convention (`allow: [a, b,\n        c, d]`) correctly. */
private object AgentsYamlScanner {
    private const val REL_PATH = "openbank-libs/governance/agents.yaml"
    private const val TOOL_TIERS_MARKER = "\ntool_tiers:\n"
    private val NEXT_TOP_LEVEL_KEY = Regex("(?m)^[a-zA-Z_]+:")
    private val CHARTER_SPLIT = Regex("\\n {2}- id: ")
    private const val TOOLS_MARKER = "\n    tools:\n"
    private val CHARTER_LEVEL_KEY = Regex("^ {4}\\S")

    data class Result(
        val toolTiersVocabulary: Set<String>,
        val allowTokens: List<CharterAllowToken>,
        val denyPatterns: List<CharterDenyPattern>,
    )

    private val EMPTY_RESULT = Result(emptySet(), emptyList(), emptyList())

    fun scan(root: Path): Result {
        val file = root.resolve(REL_PATH)
        if (!file.exists()) return EMPTY_RESULT
        val text = runCatching { file.readText() }.getOrElse { return EMPTY_RESULT }
        val toolTiers = parseToolTiers(text)
        val (allow, deny) = parseCharters(text)
        return Result(toolTiers, allow, deny)
    }

    private fun parseToolTiers(text: String): Set<String> {
        val start = text.indexOf(TOOL_TIERS_MARKER)
        if (start == -1) return emptySet()
        val bodyStart = start + TOOL_TIERS_MARKER.length
        val end = NEXT_TOP_LEVEL_KEY.find(text, bodyStart)?.range?.first ?: text.length
        return text.substring(bodyStart, end)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("-").trim().substringBefore('#').trim() }
            .filterNot { it.isEmpty() }
            .toSet()
    }

    private fun parseCharters(text: String): Pair<List<CharterAllowToken>, List<CharterDenyPattern>> {
        val allow = mutableListOf<CharterAllowToken>()
        val deny = mutableListOf<CharterDenyPattern>()
        for (block in CHARTER_SPLIT.split(text).drop(1)) {
            val agentId = block.substringBefore('\n').trim()
            val toolsBlock = extractToolsBlock(block) ?: continue
            extractFlowListTokens(toolsBlock, "allow:").forEach { allow.add(CharterAllowToken(agentId, it)) }
            extractFlowListTokens(toolsBlock, "deny:").forEach { deny.add(CharterDenyPattern(agentId, it)) }
        }
        return allow to deny
    }

    private fun extractToolsBlock(charterBlock: String): String? {
        val start = charterBlock.indexOf(TOOLS_MARKER)
        if (start == -1) return null
        val bodyStart = start + TOOLS_MARKER.length
        val sb = StringBuilder()
        for (line in charterBlock.substring(bodyStart).lineSequence()) {
            if (line.isNotBlank() && CHARTER_LEVEL_KEY.matches(line)) break
            sb.appendLine(line)
        }
        return sb.toString()
    }

    // Returns emptyList when `key` isn't found, or its value isn't a flow-list (`[...]`) — the
    // tier/resources object shape every control-plane agent charter uses (`allow:\n  - tier: read`)
    // is a different, adjacent vocabulary and deliberately out of scope here (ADR-0167 Decision).
    private fun extractFlowListTokens(toolsBlock: String, keyPrefix: String): List<String> {
        val lines = toolsBlock.lines()
        val startIdx = lines.indexOfFirst { it.trim().startsWith(keyPrefix) }
        if (startIdx == -1) return emptyList()
        val afterKey = lines[startIdx].substringAfter(keyPrefix, "").trim()
        if (!afterKey.startsWith("[")) return emptyList()
        val collected = StringBuilder(afterKey)
        var idx = startIdx
        while (!collected.contains("]") && idx + 1 < lines.size) {
            idx += 1
            collected.append('\n').append(lines[idx])
        }
        val bracketContent = collected.toString().substringAfter("[").substringBeforeLast("]")
        return bracketContent
            .lineSequence()
            .joinToString(" ") { it.substringBefore('#') }
            .split(",")
            .map { it.trim().trim('"').trim('\'') }
            .filter { it.isNotEmpty() }
    }
}
