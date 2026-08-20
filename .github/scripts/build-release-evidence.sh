#!/usr/bin/env bash
# Build the per-release evidence documents for one released component (ADR-0029 D2 / ADR-0030 D4).
#
# Realises rules.yaml: provenance.evidence_bundle. Given a just-cut release tag and its
# already-generated CycloneDX SBOM, emit three JSON documents into the current directory:
#
#   <tag>.slsa.json      — SLSA v1.0 provenance statement (in-toto), subject = the SBOM file
#   <tag>.vex.json       — OpenVEX 0.2.0 document (vuln inventory derived from `trivy sbom`,
#                          status `under_investigation` — never an unfounded `not_affected`)
#   <tag>.evidence.json  — the signed point-in-time audit object tying the bundle together
#
# Signing (cosign KMS) and release upload are done by the caller (release-please.yml); this
# script only assembles the documents so it is testable without AWS/cosign. Best-effort: a
# missing optional tool (trivy) degrades that one document, never the whole bundle.
#
# Usage: build-release-evidence.sh <component> <version> <tag> <module_dir> <sbom_file>
set -uo pipefail

COMPONENT="${1:?component}"; VERSION="${2:?version}"; TAG="${3:?tag}"
MODULE_DIR="${4:?module_dir}"; SBOM_FILE="${5:?sbom_file}"

REPO="${GITHUB_REPOSITORY:-JiRaska/open-bank-oss}"
SERVER="${GITHUB_SERVER_URL:-https://github.com}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
RUN_ID="${GITHUB_RUN_ID:-local}"
RUN_URL="${SERVER}/${REPO}/actions/runs/${RUN_ID}"
WORKFLOW_REF="${SERVER}/${REPO}/.github/workflows/release-please.yml"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

SBOM_SHA="$(sha256 "$SBOM_FILE")"

# ── SLSA v1.0 provenance ────────────────────────────────────────────────────────
SLSA_OUT="${TAG}.slsa.json"
SBOM_BASENAME="$(basename "$SBOM_FILE")" \
SBOM_SHA="$SBOM_SHA" REPO="$REPO" SERVER="$SERVER" SHA="$SHA" TAG="$TAG" \
COMPONENT="$COMPONENT" VERSION="$VERSION" WORKFLOW_REF="$WORKFLOW_REF" RUN_URL="$RUN_URL" \
python3 - > "$SLSA_OUT" <<'PY'
import json, os
stmt = {
    "_type": "https://in-toto.io/Statement/v1",
    "subject": [{"name": os.environ["SBOM_BASENAME"],
                 "digest": {"sha256": os.environ["SBOM_SHA"]}}],
    "predicateType": "https://slsa.dev/provenance/v1",
    "predicate": {
        "buildDefinition": {
            "buildType": "https://openbank.tech/release-please-evidence/v1",
            "externalParameters": {
                "component": os.environ["COMPONENT"],
                "version": os.environ["VERSION"],
                "tag": os.environ["TAG"],
            },
            "internalParameters": {},
            "resolvedDependencies": [{
                "uri": f'git+{os.environ["SERVER"]}/{os.environ["REPO"]}@{os.environ["SHA"]}',
                "digest": {"gitCommit": os.environ["SHA"]},
            }],
        },
        "runDetails": {
            "builder": {"id": os.environ["WORKFLOW_REF"]},
            "metadata": {"invocationId": os.environ["RUN_URL"]},
        },
    },
}
print(json.dumps(stmt, indent=2))
PY
echo "wrote $SLSA_OUT"

# ── OpenVEX 0.2.0 (trivy inventory as under_investigation; merged with human triage) ──
VEX_OUT="${TAG}.vex.json"
# Human triage store (versioned, reviewed): not_affected/fixed/affected statements with
# justifications. Merged over the auto inventory below — a human verdict wins per CVE.
TRIAGE_FILE="openbank-libs/governance/vex/${COMPONENT}.openvex.json"
TRIVY_JSON=""
if command -v trivy >/dev/null 2>&1; then
  trivy sbom "$SBOM_FILE" --format json --output "/tmp/${TAG}.trivy.json" --quiet 2>/dev/null \
    && TRIVY_JSON="/tmp/${TAG}.trivy.json" || echo "::warning::trivy sbom scan failed for ${TAG}; VEX inventory will be empty"
else
  echo "::warning::trivy not on PATH; ${TAG} VEX inventory will be empty"
fi
VEX_ID="${SERVER}/${REPO}/releases/tag/${TAG}#vex" \
TS="$TS" COMPONENT="$COMPONENT" VERSION="$VERSION" TRIVY_JSON="$TRIVY_JSON" TRIAGE_FILE="$TRIAGE_FILE" \
python3 - > "$VEX_OUT" <<'PY'
import json, os, sys
product = f'pkg:openbank/{os.environ["COMPONENT"]}@{os.environ["VERSION"]}'

def vuln_name(stmt):
    v = stmt.get("vulnerability")
    return v.get("name") if isinstance(v, dict) else v

# 1) Auto inventory from trivy: every finding as under_investigation (never auto not_affected).
by_cve = {}
tj = os.environ.get("TRIVY_JSON") or ""
if tj and os.path.exists(tj):
    try:
        data = json.load(open(tj))
        for res in data.get("Results", []) or []:
            for v in res.get("Vulnerabilities", []) or []:
                vid = v.get("VulnerabilityID")
                if vid and vid not in by_cve:
                    by_cve[vid] = {"vulnerability": {"name": vid},
                                   "products": [{"@id": product}],
                                   "status": "under_investigation"}
    except Exception as e:
        print(f"::warning::trivy result parse failed: {e}", file=sys.stderr)

# 2) Human triage overlay: a reviewed verdict (not_affected + justification, fixed, affected)
#    wins over the auto inventory for the same CVE; triaged CVEs not seen by trivy are kept too.
tf = os.environ.get("TRIAGE_FILE") or ""
human = 0
if tf and os.path.exists(tf):
    try:
        td = json.load(open(tf))
        for st in (td.get("statements") if isinstance(td, dict) else td) or []:
            name = vuln_name(st)
            if not name:
                continue
            st.setdefault("products", [{"@id": product}])
            by_cve[name] = st  # human verdict overrides auto
            human += 1
        print(f"::notice::merged {human} human VEX statement(s) from {tf}", file=sys.stderr)
    except Exception as e:
        print(f"::warning::VEX triage parse failed ({tf}): {e}", file=sys.stderr)

doc = {
    "@context": "https://openvex.dev/ns/v0.2.0",
    "@id": os.environ["VEX_ID"],
    "author": "OpenBank CI (release-please evidence)",
    "timestamp": os.environ["TS"],
    "version": 1,
    "statements": list(by_cve.values()),
}
print(json.dumps(doc, indent=2))
PY
echo "wrote $VEX_OUT ($(python3 -c "import json;print(len(json.load(open('$VEX_OUT')).get('statements',[])))" 2>/dev/null || echo '?') statements)"

# ── Changelog excerpt for this version ──────────────────────────────────────────
CHANGELOG_EXCERPT=""
CL="${MODULE_DIR}/CHANGELOG.md"
if [ -f "$CL" ]; then
  CHANGELOG_EXCERPT="$(awk -v ver="[$VERSION]" '
    $0 ~ /^## / { if (inblk) exit; if (index($0, ver)) { inblk=1 } }
    inblk { print }
  ' "$CL")"
fi

# ── AI attribution: authors + Co-Authored-By since previous component tag ────────
#
# Coverage, not just a name list (issue #5838). The previous version emitted a bare
# `contributors` array and nothing else, so the two cases that matter were indistinguishable
# from a good bundle: a range that produced no commits at all (a first release, a wrong
# module path, a shallow clone with no tags) and a range whose commits carry no AI
# attribution both rendered as `contributors: []` — an empty success. ADR-0031 D5 requires
# AI attribution to be *reported*, and a control that cannot report its own absence is not a
# control. So the bundle now carries a status the reader can act on:
#
#   REPORTED      — commits were found and classified; counts below are meaningful
#   NO_ATTRIBUTION— commits were found and NONE carries an AI trailer (a real finding)
#   UNAVAILABLE   — the commit range could not be established; nothing is claimed
#
# UNAVAILABLE is deliberately not merged into NO_ATTRIBUTION: "we looked and found none" and
# "we could not look" need different follow-ups.
PREV_TAG="$(git tag --sort=-creatordate 2>/dev/null | grep -E "^${COMPONENT}-v" | sed -n '2p' || true)"
RANGE="HEAD"; [ -n "$PREV_TAG" ] && RANGE="${PREV_TAG}..HEAD"
ATTRIB="$( { git log "$RANGE" --pretty='%an <%ae>' -- "$MODULE_DIR" 2>/dev/null;
             git log "$RANGE" --pretty='%(trailers:key=Co-authored-by,valueonly)' -- "$MODULE_DIR" 2>/dev/null; } \
           | sed '/^$/d' | sort -u )"

# One line per commit: "<sha> <co-authored-by trailers, space separated>". Commits with no
# trailer still emit their sha, so the denominator is every commit in range, never only the
# attributed ones (a coverage figure whose denominator is its own numerator is always 100%).
COMMIT_TRAILERS="$(git log "$RANGE" --pretty=$'%H\x1f%(trailers:key=Co-authored-by,valueonly,separator=%x20)' \
                     -- "$MODULE_DIR" 2>/dev/null || true)"
GIT_RANGE_OK=1
git rev-list --count "$RANGE" -- "$MODULE_DIR" >/dev/null 2>&1 || GIT_RANGE_OK=0

# ── Evidence bundle manifest ────────────────────────────────────────────────────
EV_OUT="${TAG}.evidence.json"
TS="$TS" COMPONENT="$COMPONENT" VERSION="$VERSION" TAG="$TAG" SHA="$SHA" REPO="$REPO" \
SBOM_BASENAME="$(basename "$SBOM_FILE")" SBOM_SHA="$SBOM_SHA" \
SLSA_OUT="$SLSA_OUT" VEX_OUT="$VEX_OUT" RUN_URL="$RUN_URL" \
CHANGELOG_EXCERPT="$CHANGELOG_EXCERPT" ATTRIB="$ATTRIB" \
COMMIT_TRAILERS="$COMMIT_TRAILERS" GIT_RANGE_OK="$GIT_RANGE_OK" RANGE="$RANGE" \
python3 - > "$EV_OUT" <<'PY'
import json, os, hashlib
def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()
attrib = [a for a in os.environ.get("ATTRIB", "").splitlines() if a.strip()]

# Markers that identify an AI co-author trailer. Kept as substrings (case-insensitive) rather
# than exact addresses so a model or harness rename does not silently drop coverage to zero
# while still reporting REPORTED.
AI_MARKERS = ("noreply@anthropic.com", "claude", "copilot", "openai", "ai-agent", "aider")

def is_ai(trailers: str) -> bool:
    low = trailers.lower()
    return any(m in low for m in AI_MARKERS)

lines = [ln for ln in os.environ.get("COMMIT_TRAILERS", "").splitlines() if ln.strip()]
commits_total = len(lines)
commits_ai = sum(1 for ln in lines if is_ai(ln.split("\x1f", 1)[1] if "\x1f" in ln else ""))

if os.environ.get("GIT_RANGE_OK") != "1" or commits_total == 0:
    # Nothing was measured. Say so — never render an unmeasured range as a clean result.
    attribution = {
        "status": "UNAVAILABLE",
        "reason": "commit range %s produced no commits for %s (shallow clone, wrong path, or first release)"
                  % (os.environ.get("RANGE", "?"), os.environ.get("COMPONENT", "?")),
        "commits_total": commits_total,
        "commits_ai_attributed": 0,
        "coverage_pct": None,
        "contributors": attrib,
    }
elif commits_ai == 0:
    attribution = {
        "status": "NO_ATTRIBUTION",
        "reason": "no commit in range carries an AI co-author trailer",
        "commits_total": commits_total,
        "commits_ai_attributed": 0,
        "coverage_pct": 0.0,
        "contributors": attrib,
    }
else:
    attribution = {
        "status": "REPORTED",
        "commits_total": commits_total,
        "commits_ai_attributed": commits_ai,
        "coverage_pct": round(100.0 * commits_ai / commits_total, 1),
        "contributors": attrib,
    }
attribution["note"] = (
    "AI-attribution coverage over commits touching this module since the previous component "
    "tag (ADR-0029 D6 / ADR-0031 D5). status UNAVAILABLE means the range could not be "
    "measured — it is NOT a passing result."
)
bundle = {
    "schema": "openbank.evidence/v1",
    "component": os.environ["COMPONENT"],
    "version": os.environ["VERSION"],
    "tag": os.environ["TAG"],
    "git_commit": os.environ["SHA"],
    "built_at": os.environ["TS"],
    "sbom": {"file": os.environ["SBOM_BASENAME"], "format": "cyclonedx",
             "sha256": os.environ["SBOM_SHA"], "signature": os.environ["SBOM_BASENAME"] + ".sig"},
    "slsa_provenance": {"file": os.environ["SLSA_OUT"], "predicate": "slsa.dev/provenance/v1",
                        "sha256": sha(os.environ["SLSA_OUT"]),
                        "signature": os.environ["SLSA_OUT"] + ".sig"},
    "vex": {"file": os.environ["VEX_OUT"], "format": "openvex/0.2.0",
            "sha256": sha(os.environ["VEX_OUT"]), "signature": os.environ["VEX_OUT"] + ".sig"},
    "changelog": os.environ.get("CHANGELOG_EXCERPT", "").strip(),
    "ai_attribution": attribution,
    # Referenced, not embedded: these live on the CI run that produced them.
    "scan_results": {"trivy": os.environ["RUN_URL"],
                     "codeql": "n/a — GHAS code-scanning gated while repo is private (security.yml)"},
    "coverage_summary": {"ref": os.environ["RUN_URL"]},
    "test_results": {"ref": os.environ["RUN_URL"]},
    "signing": {"method": "cosign sign-blob", "key": "awskms:///alias/openbank-cosign-signing"},
}
print(json.dumps(bundle, indent=2))
PY
echo "wrote $EV_OUT"
