#!/usr/bin/env bash
# Build the per-release evidence documents for one released component (ADR-0029 D2 / ADR-0030 D4).
#
# Realises rules.yaml: provenance.evidence_bundle. Given a just-cut release tag and its
# already-generated CycloneDX SBOM, emit three JSON documents into the current directory:
#
#   <tag>.slsa.json             — SLSA v1.0 provenance statement (in-toto), subject = the SBOM file
#   <tag>.vex.json              — OpenVEX 0.2.0 document (vuln inventory derived from `trivy sbom`,
#                                 status `under_investigation` — never an unfounded `not_affected`)
#   <tag>.audit-provenance.json — D5 audit anchor checkpoint + runtime AI attribution
#                                 (issue #12923 gaps #4/#5, evidence-pipeline half; optional
#                                 attestation inputs via EVIDENCE_ANCHOR_ATTESTATION_JSON /
#                                 EVIDENCE_AI_ATTRIBUTION_JSON, honest not_attested otherwise)
#   <tag>.evidence.json         — the signed point-in-time audit object tying the bundle together
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

# ── Contributor attribution + runtime-AI attribution boundary ──────────────────
#
# The git range can establish who contributed to a release, but it cannot establish that a
# deployed agent emitted model_id/prompt_hash/policy_decision audit fields. Do not turn that
# category error into an AI-runtime attestation. The bundle publishes the missing runtime proof
# explicitly; D5 can only graduate when independently observed audit evidence is attached later.
PREV_TAG="$(git tag --sort=-creatordate 2>/dev/null | grep -E "^${COMPONENT}-v" | sed -n '2p' || true)"
RANGE="HEAD"; [ -n "$PREV_TAG" ] && RANGE="${PREV_TAG}..HEAD"
ATTRIB="$( { git log "$RANGE" --pretty='%an <%ae>' -- "$MODULE_DIR" 2>/dev/null;
             git log "$RANGE" --pretty='%(trailers:key=Co-authored-by,valueonly)' -- "$MODULE_DIR" 2>/dev/null; } \
           | sed '/^$/d' | sort -u )"

# ── D5 audit-provenance document (issue #12923 gaps #4/#5, evidence-pipeline half) ──
#
# Two OPTIONAL attestation inputs, each an env var pointing at a JSON file:
#
#   EVIDENCE_ANCHOR_ATTESTATION_JSON — the `verdict.json` published by the weekly audit-anchor
#       live-attestation workflow (artifact `audit-anchor-attestation`). Consumed fields:
#       `verdict` (VERIFIED|TAMPERED|UNVERIFIABLE|INPUT_ERROR) and `anchor_checkpoint`
#       {digest, keyId, signedAt} — the latest KMS-signed anchor of the export (its
#       `anchorDigest`/`keyId`/`signedAt`). `fetched_at`/`export_url`/`verifier_exit`/
#       `generations_verified` pass through when present.
#   EVIDENCE_AI_ATTRIBUTION_JSON — runtime AI-attestation output carrying
#       `latest_attested_run_window` {…} and `observed_fields` (must cover the required
#       model_id/prompt_hash/policy_decision).
#
# HONESTY RULE: an unset input yields an explicit not_attested block — never fabricated,
# never silently dropped. A MALFORMED input (unparseable JSON, or missing the fields above)
# fails the build loudly: a bundle that swallowed a bad attestation would read as "no
# attestation", which is a lie about a control that tried to run.
AP_OUT="${TAG}.audit-provenance.json"
for var in EVIDENCE_ANCHOR_ATTESTATION_JSON EVIDENCE_AI_ATTRIBUTION_JSON; do
  eval "path=\${$var:-}"
  if [ -n "$path" ] && [ ! -f "$path" ]; then
    echo "::error::$var points at '$path' which does not exist — refusing to cut a bundle that would silently drop the attestation input"
    exit 1
  fi
done

TS="$TS" COMPONENT="$COMPONENT" VERSION="$VERSION" TAG="$TAG" \
EVIDENCE_ANCHOR_ATTESTATION_JSON="${EVIDENCE_ANCHOR_ATTESTATION_JSON:-}" \
EVIDENCE_AI_ATTRIBUTION_JSON="${EVIDENCE_AI_ATTRIBUTION_JSON:-}" \
python3 - > "$AP_OUT" <<'PY'
import json, os, sys

REQUIRED_FIELDS = ["model_id", "prompt_hash", "policy_decision"]
VERDICTS = {"VERIFIED", "TAMPERED", "UNVERIFIABLE", "INPUT_ERROR"}


def die(message: str) -> None:
    print(f"::error::audit-provenance input malformed: {message}", file=sys.stderr)
    sys.exit(3)


def load_input(env_var: str):
    path = os.environ.get(env_var) or ""
    if not path:
        return None
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        die(f"{env_var} ({path}) is not parseable JSON: {exc}")
    if not isinstance(data, dict):
        die(f"{env_var} ({path}) must be a JSON object, got {type(data).__name__}")
    return data


anchor_in = load_input("EVIDENCE_ANCHOR_ATTESTATION_JSON")
ai_in = load_input("EVIDENCE_AI_ATTRIBUTION_JSON")

# ── audit anchor checkpoint (#12923 gap #5): digest + keyId + signedAt + verification
# status at cut time. `status: attested` ONLY for a VERIFIED input; any other verdict is
# recorded verbatim (forensics) but stays not_attested — an unverified checkpoint must not
# read as anchored.
if anchor_in is None:
    anchor_block = {
        "status": "not_attested",
        "reason": "no attestation input at cut time",
    }
else:
    verdict = anchor_in.get("verdict")
    if not isinstance(verdict, str) or verdict not in VERDICTS:
        die(f"anchor attestation verdict must be one of {sorted(VERDICTS)}, got {verdict!r}")
    checkpoint = anchor_in.get("anchor_checkpoint")
    if not isinstance(checkpoint, dict):
        die("anchor attestation has no 'anchor_checkpoint' object")
    digest, key_id, signed_at = (
        checkpoint.get("digest"), checkpoint.get("keyId"), checkpoint.get("signedAt"))
    if not all(isinstance(v, str) and v for v in (digest, key_id, signed_at)):
        die("anchor_checkpoint needs non-empty string digest, keyId and signedAt")
    attested = verdict == "VERIFIED"
    anchor_block = {
        "status": "attested" if attested else "not_attested",
        "digest": digest,
        "keyId": key_id,
        "signedAt": signed_at,
        "verification_status": verdict,
    }
    for optional in ("fetched_at", "export_url", "verifier_exit", "generations_verified"):
        if optional in anchor_in:
            anchor_block[optional] = anchor_in[optional]
    if not attested:
        anchor_block["reason"] = (
            f"attestation input at cut time reported verdict {verdict} — the checkpoint is "
            "recorded for forensics but a non-VERIFIED anchor chain is not attested"
        )

# ── runtime AI attribution (#12923 gap #4): flip to attested ONLY when a real input
# carries the required fields observed across a concrete run window.
if ai_in is None:
    ai_block = {
        "status": "not_attested",
        "required_fields": REQUIRED_FIELDS,
        "latest_attested_run_window": None,
        "reason": "no runtime AI-attestation input at cut time",
    }
else:
    window = ai_in.get("latest_attested_run_window")
    if not isinstance(window, dict) or not window:
        die("AI-attestation input has no 'latest_attested_run_window' object")
    observed = ai_in.get("observed_fields")
    if (not isinstance(observed, list) or not all(isinstance(f, str) for f in observed)
            or not set(REQUIRED_FIELDS).issubset(observed)):
        die(f"AI-attestation observed_fields must cover {REQUIRED_FIELDS}, got {observed!r}")
    ai_block = {
        "status": "attested",
        "required_fields": REQUIRED_FIELDS,
        "latest_attested_run_window": window,
        "observed_fields": observed,
    }
    for optional in ("attestation_source", "attested_at"):
        if optional in ai_in:
            ai_block[optional] = ai_in[optional]

doc = {
    "schema_version": 1,
    "schema": "openbank.audit-provenance/v1",
    "component": os.environ["COMPONENT"],
    "version": os.environ["VERSION"],
    "tag": os.environ["TAG"],
    "generated_at": os.environ["TS"],
    "audit_anchor_checkpoint": anchor_block,
    "ai_attribution": ai_block,
}
print(json.dumps(doc, indent=2))
PY
[ -s "$AP_OUT" ] || { echo "::error::audit-provenance assembly produced no document for $TAG"; exit 1; }
echo "wrote $AP_OUT"

# ── Support period (#9881) ──────────────────────────────────────────────────────
# Derived from SECURITY.md's rules against the RELEASE date — the tagged commit's date — never
# from `built_at`: backfill-release-evidence.yml runs this months after a release, and deriving
# from the build time would silently push every backfilled end-of-support into the future. Both
# callers check out the released commit, so HEAD's committer date IS the release date.
RELEASED_ON="$(git log -1 --format=%cs HEAD)"
SUPPORT_JSON="$(python3 "$(dirname "$0")/release_support_period.py" "$VERSION" "$RELEASED_ON")" \
  || { echo "::error::could not derive the support period for $TAG ($VERSION, $RELEASED_ON)"; exit 1; }

# ── Evidence bundle manifest ────────────────────────────────────────────────────
EV_OUT="${TAG}.evidence.json"
TS="$TS" COMPONENT="$COMPONENT" VERSION="$VERSION" TAG="$TAG" SHA="$SHA" REPO="$REPO" \
SUPPORT_JSON="$SUPPORT_JSON" \
SBOM_BASENAME="$(basename "$SBOM_FILE")" SBOM_SHA="$SBOM_SHA" \
SLSA_OUT="$SLSA_OUT" VEX_OUT="$VEX_OUT" RUN_URL="$RUN_URL" \
AP_OUT="$AP_OUT" \
CHANGELOG_EXCERPT="$CHANGELOG_EXCERPT" ATTRIB="$ATTRIB" \
python3 - > "$EV_OUT" <<'PY'
import json, os, hashlib
def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()
attrib = [a for a in os.environ.get("ATTRIB", "").splitlines() if a.strip()]
# The D5 audit-provenance document is the carrier for runtime attestation; the manifest
# self-declaration below must AGREE with it — verify-release-evidence-manifest.py fails a
# bundle whose manifest claims attested without the document, or contradicts it.
audit_provenance = json.load(open(os.environ["AP_OUT"]))
ai_status = audit_provenance["ai_attribution"]["status"]
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
    "audit_provenance": {"file": os.environ["AP_OUT"], "format": "openbank.audit-provenance/v1",
                         "sha256": sha(os.environ["AP_OUT"]),
                         "signature": os.environ["AP_OUT"] + ".sig"},
    "changelog": os.environ.get("CHANGELOG_EXCERPT", "").strip(),
    # End-of-support as data (SECURITY.md; CRA Art. 13(8)). A beta release carries only the
    # determined floor and `open_ended: true` — its later bound depends on a future release.
    "support": json.loads(os.environ["SUPPORT_JSON"]),
    "ai_attribution": {
        "contributors": attrib,
        "build_attribution_note": "authors + Co-Authored-By trailers since previous component tag (ADR-0029 D6)",
        "runtime_audit_attribution": {
            "status": ai_status,
            "required_fields": ["model_id", "prompt_hash", "policy_decision"],
            "document": os.environ["AP_OUT"],
            "reason": (
                "a release build has no runtime audit-event sample; contributor trailers are not evidence of AI runtime attribution"
                if ai_status == "not_attested"
                else "attested by the audit-provenance document bundled with this release"
            ),
            "promotion_eligible": ai_status == "attested",
        },
    },
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
