#!/usr/bin/env python3
"""api-contract gate — OpenAPI-diff bump classification (ADR-0048 D5).

For every `openbank-*/src/main/resources/openapi.yaml` changed against the PR
base, classify the API-contract change from the OpenAPI diff (oasdiff):

    breaking   => info.version MAJOR must move (new URL major, /api/v{N+1})
    correction => a breaking DOCUMENT diff in a PR that changes nothing else in the service:
                  the served contract is unchanged, so MINOR (see below)
    idempotency-hardening
               => every breaking finding is a newly REQUIRED `Idempotency-Key` header on a
                  POST of a money-path service: MINOR (see [idempotency_hardening], ADR-0048
                  -> ADR-0330)
    additive   => info.version MINOR (or MAJOR) must move within the same /v{N}
    editorial  => info.version PATCH (or higher) must move

The `correction` class exists because `breaking` and the D2 invariant below are jointly
unsatisfiable for a spec that never described the running service. Correcting one is red at
the old version (oasdiff calls it breaking, a MAJOR is demanded) and red at MAJOR+1 (D2 rejects
it, the served URL major has not moved). Measured both ends on aml-service, #2312/#2313: the
only green document was one that kept a phantom enum value and a wrong default, so the gate was
not merely blocking the fix, it was requiring the falsehood. A spec-only PR cannot break a
working client — the server is byte-identical — so the MAJOR requirement is dropped to MINOR.
The discriminator is the diff itself, never a declared marker.

and assert the ADR-0048 D2 API invariant on the head revision:

    major(openapi.yaml:info.version) == openbank.api.version (application.yaml)
                                     == max URL major used in @Path("/api/v{N}")

The release axis (version.txt) is deliberately NOT consulted — the two axes are
independent (ADR-0048 D1); this gate classifies its own bump from the diff.

stdlib-only; shells out to `git` and the `oasdiff` binary (pinned + checksum-
verified by the CI step that installs it).

Usage:
    check-api-contract.py --base <sha> [--enforce] [--oasdiff <path>]

--base must be the CURRENT merge-base with the base branch (ci.yml resolves it
from the PR merge ref's first parent), NOT github.event.pull_request.base.sha —
that sha is frozen at PR creation and misclassifies once a competing PR moves
the same spec on main first (the #524 × #481 ledger race, fixed in #534).

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Iterator
from pathlib import Path

OPENAPI_GLOB = re.compile(r"^(openbank-[^/]+)/src/main/resources/openapi\.yaml$")


class SpecLoadError(Exception):
    """oasdiff could not load one of the specs (strict $ref resolution failed)."""


def sh(*args: str, check: bool = True) -> str:
    res = subprocess.run(args, capture_output=True, text=True)
    if res.returncode != 0 and "failed to load" in res.stderr:
        raise SpecLoadError(res.stderr.strip().splitlines()[-1])
    if check and res.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(args)}\n{res.stderr.strip()}")
    return res.stdout


def parse_semver(raw: str) -> tuple[int, int, int] | None:
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)", raw.strip())
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def info_version(openapi_text: str) -> str | None:
    """Extract info.version without a YAML dependency.

    The fleet's openapi.yaml files declare a top-level `info:` block whose
    scalar fields sit at one indent level below it. We scan that block only,
    so `version:` keys elsewhere (schemas, examples) are never picked up.
    """
    lines = openapi_text.splitlines()
    in_info = False
    info_indent = 0
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if not in_info:
            if indent == 0 and stripped == "info:":
                in_info = True
                info_indent = indent
            continue
        if indent <= info_indent:  # left the info block
            return None
        m = re.match(r"version:\s*['\"]?([0-9][^'\"\s]*)", stripped)
        if m:
            return m.group(1)
    return None


def with_info_version(openapi_text: str, value: str) -> str:
    """Return the document with info.version rewritten to `value`.

    Used to take `info.version` OUT of the material diff. The version is the gate's OUTPUT —
    the thing it asks the author to change — so leaving it in the diff it classifies from makes
    the classification self-fulfilling: adding the PATCH bump an `editorial` change requires is
    itself a non-excluded diff, which reclassifies the change as `additive` and demands MINOR.
    Measured on #6369: a document whose ONLY change was 1.2.0 -> 1.2.1 classified `additive`,
    so no version could satisfy an editorial change (#6380).

    Scans the top-level `info:` block only, exactly as [info_version] does, so a `version:` key
    inside a schema or an example is never rewritten.
    """
    out: list[str] = []
    in_info = False
    replaced = False
    for line in openapi_text.splitlines(keepends=True):
        stripped = line.strip()
        if not replaced and stripped and not stripped.startswith("#"):
            indent = len(line) - len(line.lstrip())
            if not in_info:
                if indent == 0 and stripped == "info:":
                    in_info = True
            elif indent == 0:
                in_info = False  # left the info block without finding a version
            elif re.match(r"version:\s*['\"]?[0-9]", stripped):
                pad = line[:indent]
                out.append(f'{pad}version: "{value}"\n' if line.endswith("\n") else f'{pad}version: "{value}"')
                replaced = True
                continue
        out.append(line)
    return "".join(out)


def config_api_major(service_dir: Path) -> int | None:
    """openbank.api.version from the service application.yaml, or None if the key is absent.

    Returns None (not a defaulted 1) when the service does not declare the key — the D2
    invariant is only asserted against an EXPLICIT value, so a pre-1.0 or external-contract
    service that never set openbank.api.version is not falsely flagged.
    """
    app_yaml = service_dir / "src/main/resources/application.yaml"
    if not app_yaml.is_file():
        return None
    text = app_yaml.read_text(encoding="utf-8", errors="replace")
    # nested `api:\n  version: "N"` — allow comments/blank lines between the pair, but stay
    # within the api: block's indent (scan a few lines under `api:` for `version:`).
    m = re.search(r"^(\s*)api:\s*(?:#.*)?$\n(?:\1\s+.*\n)*?\1\s+version:\s*['\"]?(\d+)", text, re.MULTILINE)
    return int(m.group(2)) if m else None


# ── D2's external-contract exemption, DECLARED rather than inferred (issue #2276) ───────────
#
# A service that serves no own `/api/v{N}` cannot have its URL major compared to
# `info.version`, so D2 has to be skipped for it. The question is WHY it serves none.
#
# Two very different answers look identical to the gate:
#   (a) it follows an external spec that dictates its URL shape — psd2 on Berlin Group
#       NextGenPSD2 serves `/v1/consents`, and its info.version tracks THAT spec;
#   (b) it simply never adopted this repo's convention — in which case its openapi.yaml major
#       is free to drift with nothing to compare it against, the precise failure ADR-0048 was
#       written to prevent.
#
# Inferring (a) from "no /api/v{N}" silently grants (b) the same exemption, with a reassuring
# green line in the log. So the exemption is a list you have to join, with a reason attached.
# A service with no own `/api/v{N}` that is NOT listed here is a FINDING.
#
# Same shape as check-pact-provider-replay.py's KNOWN_UNCOVERED: an entry that stops being
# true fails as stale, so the list cannot quietly outlive its justification.
#
# Keyed by the module directory name, which is what `service` holds in main() (OPENAPI_GLOB).
EXTERNAL_CONTRACT_SERVICES: dict[str, str] = {
    "openbank-psd2-service": (
        "Berlin Group NextGenPSD2 mandates the URL shape (/v1/consents, ...); info.version "
        "tracks that external spec, not a URL segment this repo owns."
    ),
}


# JAX-RS REST *client* stubs carry the CALLEE's URL major (e.g. an Alertmanager client at
# /api/v2), not this service's own contract — they must not drive the D2 URL-major check.
#
# `@RegisterRestClient` only, NOT a bare `RestClient` reference: the wider pattern matched any file
# that *injects* a client, which is ordinary in a resource class, and skipped that whole file — so a
# resource serving /api/v1 and calling one upstream contributed nothing. clearing-simulator was in
# exactly that state; its major was recovered only by accident, from the word "/api/v1" in a comment
# in a different file, which stopped working the moment prose stopped counting (#3119).
_CLIENT_HINT = re.compile(r"@RegisterRestClient")


# `@Path("…")` values only. Scanning raw file text for /api/v{N} matches PROSE: a comment
# explaining the URL-major rule reads as an endpoint implementing it, and the gate fails on a
# service whose every @Path says /api/v1. Measured on #3110, where the only occurrence of
# "/api/v2" in the service was a KDoc paragraph about why a major bump was avoided (#3119).
_PATH_ANNOTATION = re.compile(r'@Path\s*\(\s*"([^"]*)"')

# A `v{N}` PATH SEGMENT anywhere in the served path, not the literal prefix `/api/v{N}`.
# openbank-customer-edge serves `/customer/v1/...`, and under the old raw-text scan its "major"
# came from outbound URL strings pointing at document-service — the D2 invariant was being
# satisfied by a string addressed to a different service. D2 is about the major of the path this
# service serves; the prefix in front of it is not the point.
_VERSION_SEGMENT = re.compile(r"(?:^|/)v(\d+)(?=/|$)")

# `/api/v{N}` specifically — the shape THIS repo owns. Used only to judge whether an
# EXTERNAL_CONTRACT_SERVICES entry has gone stale: a service that starts serving the house shape
# has taken ownership of its URL major and no longer needs the exemption. A version segment under
# a mandated foreign prefix (psd2's Berlin Group `/v1/consents`) proves no such thing, which is why
# the staleness test cannot reuse the broader match above.
_OWN_API_PATH = re.compile(r"/api/v(\d+)(?=/|$)")


def strip_comments(text: str) -> str:
    """Kotlin source with comments blanked out, preserving offsets and string literals.

    Written as a scanner rather than a regex for two reasons the repo has been bitten by:
    Kotlin block comments NEST, so a naive non-greedy block-comment regex closes early on a KDoc
    that itself contains an open-comment marker
    and leaks the rest of that comment back into the scanned text; and `//` occurs inside string
    literals (`"http://…"`), so a line-comment rule that ignores strings truncates real code.
    """
    out: list[str] = []
    i, n = 0, len(text)
    depth = 0
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if depth:
            if ch == "/" and nxt == "*":
                depth += 1
                out.append("  ")
                i += 2
                continue
            if ch == "*" and nxt == "/":
                depth -= 1
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if ch == "/" and nxt == "*":
            depth = 1
            out.append("  ")
            i += 2
            continue
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch in ('"', "'"):
            quote = ch
            triple = text.startswith(quote * 3, i)
            end = quote * 3 if triple else quote
            j = i + len(end)
            while j < n:
                if not triple and text[j] == "\\":
                    j += 2
                    continue
                if text.startswith(end, j):
                    j += len(end)
                    break
                if not triple and text[j] == "\n":
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def own_api_majors(service_dir: Path) -> set[int]:
    """Majors served under this repo's own `/api/v{N}` shape. See [_OWN_API_PATH]."""
    return _majors(service_dir, _OWN_API_PATH)


def url_majors(service_dir: Path) -> set[int]:
    """Majors of every version segment this service serves, under any prefix."""
    return _majors(service_dir, _VERSION_SEGMENT)


def _majors(service_dir: Path, pattern: re.Pattern[str]) -> set[int]:
    majors: set[int] = set()
    src = service_dir / "src/main/kotlin"
    if not src.is_dir():
        return majors
    for kt in src.rglob("*.kt"):
        # Skip files under a client package or that declare a REST client — their /api/v{N}
        # is an outbound URL to another service, not this service's served contract.
        if "/client/" in kt.as_posix().lower():
            continue
        try:
            text = kt.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if _CLIENT_HINT.search(text):
            continue
        for path_value in _PATH_ANNOTATION.findall(strip_comments(text)):
            for m in pattern.finditer(path_value):
                majors.add(int(m.group(1)))
    # A design-first service may compile its JAX-RS interface from OpenAPI into build/generated,
    # so there is intentionally no handwritten @Path in src/main/kotlin. Count the provider spec
    # only when the module explicitly configures the kotlin-server generator; a plain spec file
    # still does not prove that the service exposes those routes.
    build_file = service_dir / "build.gradle.kts"
    provider_spec = service_dir / "src/main/resources/openapi.yaml"
    if build_file.is_file() and provider_spec.is_file():
        build_text = build_file.read_text(encoding="utf-8", errors="replace")
        if 'generatorName.set("kotlin-server")' in build_text and "openApiGenerate" in build_text:
            spec_text = provider_spec.read_text(encoding="utf-8", errors="replace")
            for path_value in re.findall(r"(?m)^\s{2}(/[^:]+):\s*$", spec_text):
                for m in pattern.finditer(path_value):
                    majors.add(int(m.group(1)))
    return majors


@contextlib.contextmanager
def pinned_copy(spec: Path) -> Iterator[Path]:
    """Yield a sibling copy of `spec` whose info.version is pinned to a fixed value."""
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".yaml", prefix=f".pinned-{spec.stem}-", dir=spec.parent,
        encoding="utf-8", delete=True,
    ) as fh:
        fh.write(with_info_version(spec.read_text(encoding="utf-8", errors="replace"), "0.0.0"))
        fh.flush()
        yield Path(fh.name)


def oasdiff_classify(oasdiff: str, old: Path, new: Path) -> tuple[str, list[str]]:
    """Return (classification, breaking_titles).

    breaking  — `oasdiff breaking` reports at least one error-level change
    additive  — non-editorial diff remains after excluding docs elements
    editorial — the files differ only in description/examples/title/summary
    none      — semantically identical
    """
    breaking_raw = sh(oasdiff, "breaking", str(old), str(new), "--format", "json", check=False)
    breaking: list[str] = []
    if breaking_raw.strip():
        try:
            # `oasdiff breaking` also emits WARN-level (level 2) entries; only ERR-level
            # (level 3) changes are truly breaking and require a MAJOR bump. Keep the entry
            # when its level is error, or when no level field exists (be safe).
            for c in json.loads(breaking_raw):
                lvl = str(c.get("level", "")).lower()
                if lvl in ("", "error", "err", "3"):
                    breaking.append(c.get("text") or c.get("id", "breaking change"))
        except json.JSONDecodeError:
            breaking = ["(unparseable oasdiff breaking output — treating as breaking)"]
    if breaking:
        return "breaking", breaking

    # Compare copies whose info.version is pinned to the SAME value. oasdiff's
    # --exclude-elements vocabulary does not cover info.version, and leaving it in makes the
    # bump its own justification — see [with_info_version] and #6380.
    # Each pinned copy is written NEXT TO its source, never in a scratch directory: a spec may
    # resolve a relative $ref, and moving the document would break loading (which surfaces as
    # SpecLoadError, i.e. a red gate, rather than a wrong answer — but only after it happens).
    with pinned_copy(old) as old_pinned, pinned_copy(new) as new_pinned:
        material = sh(
            oasdiff, "diff", str(old_pinned), str(new_pinned),
            "--exclude-elements", "description,examples,title,summary",
            "--format", "json", check=False,
        ).strip()
    if material and material not in ("{}", "null"):
        return "additive", []

    any_diff = sh(oasdiff, "diff", str(old), str(new), "--format", "json", check=False).strip()
    if any_diff and any_diff not in ("{}", "null"):
        return "editorial", []
    return "none", []


REQUIRED_BUMP = {
    "breaking": "MAJOR",
    "correction": "MINOR",
    "idempotency-hardening": "MINOR",
    "additive": "MINOR",
    "editorial": "PATCH",
}

RULES_YAML = Path("openbank-libs/governance/rules.yaml")
_IDEM_HEADER_TEXT = re.compile(r"new required `header` request parameter `([^`]+)`")


def breaking_entries(oasdiff: str, old: Path, new: Path) -> list[dict]:
    """The ERR-level `oasdiff breaking` entries, raw — what [idempotency_hardening] decides on."""
    raw = sh(oasdiff, "breaking", str(old), str(new), "--format", "json", check=False)
    if not raw.strip():
        return []
    try:
        entries = json.loads(raw)
    except json.JSONDecodeError:
        return [{"id": "unparseable"}]
    return [c for c in entries if str(c.get("level", "")).lower() in ("", "error", "err", "3")]


def money_path_services(rules_text: str) -> set[str]:
    """`money_path_services:` from rules.yaml (stdlib-only: a top-level block list of names).

    Read from the HEAD tree, so a PR that ADDS a service to the list is judged as money-path —
    the classification and the hardening it licenses land together.
    """
    out: set[str] = set()
    in_block = False
    for line in rules_text.splitlines():
        if not in_block:
            in_block = line.rstrip() == "money_path_services:"
            continue
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        m = re.match(r"^\s+-\s+([A-Za-z0-9._-]+)", line)
        if not m:
            break
        out.add(m.group(1))
    return out


def idempotency_hardening(entries: list[dict], money_path: bool) -> bool:
    """True when a breaking diff is ONLY idempotency hardening on money-path POSTs.

    Requiring an `Idempotency-Key` on a money-path POST that lacked one IS breaking for a client
    that does not send it — but the alternative the plain rule demands is a whole-service move
    to /api/v{N+1} (ADR-0048 D2), which no team takes for a duplicate-debit fix, so the fix is
    not shipped and the endpoint keeps double-booking on retry. The enforced
    `idempotency-coverage-money-path` gate requires exactly this header, and this gate then
    made it unreachable for any POST that existed before the service joined the money-path
    list (#11996). The obligation that replaces the MAJOR bump: the PR updates its in-repo
    callers to send a key (they 400 otherwise, loudly, not silently).

    Narrow by construction — ALL must hold, else the normal breaking rule applies:
      * the service is in `money_path_services` (as of HEAD, so a PR adding it qualifies);
      * there is at least one breaking finding, and EVERY one is
        `new-required-request-parameter` for a HEADER named `Idempotency-Key`
        (case-insensitive) on a POST (ADR-0330). Any other breaking change anywhere in the same spec —
        including on the same operation — keeps requiring MAJOR.
    """
    if not money_path or not entries:
        return False
    for e in entries:
        if e.get("id") != "new-required-request-parameter":
            return False
        if str(e.get("operation", "")).upper() != "POST":
            return False
        m = _IDEM_HEADER_TEXT.search(str(e.get("text", "")))
        if not m or m.group(1).lower() != "idempotency-key":
            return False
    return True


_MIGRATION_SECTION = re.compile(
    r"(?ms)^## Idempotency caller migration[ \t]*\n(.*?)(?=^## |\Z)"
)
_MIGRATION_LINE = re.compile(
    r"^- `(openbank-[^` ]+) POST ([^`]+)` \| callers: (.+) \| proof: `([^`]+)`$"
)


def migration_assertion_findings(
    body: str, service: str, entries: list[dict], changed: set[str], repo: Path = Path("."),
) -> list[str]:
    """Require reviewable caller inventory and changed test evidence for each MINOR exception.

    This does not claim to discover every caller. The human reviewer checks that the declared
    inventory is complete; the gate makes its absence and an unchanged/nonexistent proof fail.
    """
    expected = {str(e.get("path", "")) for e in entries}
    section = _MIGRATION_SECTION.search(body)
    if not section:
        return [f"{service}: add a '## Idempotency caller migration' PR section with one line "
                "per hardened operation, e.g. - `openbank-service POST /path` | "
                "callers: `file` (or none) | proof: `changed-test-file` (ADR-0330)"]
    lines = [line.strip() for line in section.group(1).splitlines() if line.strip().startswith("- ")]
    found: dict[str, tuple[str, str]] = {}
    findings = []
    for line in lines:
        match = _MIGRATION_LINE.fullmatch(line)
        if not match:
            findings.append(f"{service}: malformed migration assertion: {line}")
            continue
        declared_service, path, callers, proof = match.groups()
        if declared_service != service:
            continue  # A different changed service checks its own entries below.
        if path in found:
            findings.append(f"{service}: duplicate caller migration assertion for POST {path}")
        found[path] = (callers, proof)
    for path in sorted(expected):
        if path not in found:
            findings.append(f"{service}: missing caller migration assertion for POST {path}")
            continue
        callers, proof = found[path]
        if callers != "none":
            caller_paths = re.findall(r"`([^`]+)`", callers)
            if not caller_paths or ", ".join(f"`{p}`" for p in caller_paths) != callers:
                findings.append(f"{service}: POST {path}: callers must be comma-separated repo paths or none")
            for caller in caller_paths:
                if Path(caller).is_absolute() or ".." in Path(caller).parts or not (repo / caller).is_file():
                    findings.append(f"{service}: POST {path}: caller path does not exist: {caller}")
        if Path(proof).is_absolute() or ".." in Path(proof).parts or not (repo / proof).is_file():
            findings.append(f"{service}: POST {path}: proof test path does not exist: {proof}")
        elif proof not in changed or not ("/test/" in proof or "/tests/" in proof or ".test." in proof):
            findings.append(f"{service}: POST {path}: proof must be a test file changed in this PR: {proof}")
    for path in sorted(set(found) - expected):
        findings.append(f"{service}: migration assertion names unchanged operation POST {path}")
    return findings


def bump_satisfied(kind: str, old_v: tuple[int, int, int], new_v: tuple[int, int, int]) -> bool:
    if kind == "breaking":
        return new_v[0] > old_v[0]
    if kind in ("additive", "correction", "idempotency-hardening"):
        return new_v[0] > old_v[0] or (new_v[0] == old_v[0] and new_v[1] > old_v[1])
    if kind == "editorial":
        return new_v > old_v
    return True


# Derived files that cannot change what the service serves, so their presence in a diff does
# not disqualify a spec correction. release-please writes both.
BEHAVIOURLESS = {"CHANGELOG.md", "version.txt"}

# Trees inside a service that cannot change what it serves either. Tests never ship, and the
# authored docs are prose that check-service-docs-freshness.py REQUIRES alongside any src/main
# change, openapi.yaml included — so without this the two gates were jointly unsatisfiable for a
# correction, and a test pinning the corrected spec to the running service disqualified it (#11972).
BEHAVIOURLESS_TREES = ("src/test/", "src/main/resources/docs/")


def service_touched_beyond_spec(service: str, spec_rel: str, changed_all: list[str]) -> list[str]:
    """Files in this service the PR changed other than its openapi.yaml (and behaviourless files)."""
    return [
        f for f in changed_all
        if f.startswith(service + "/") and f != spec_rel
        and f.rsplit("/", 1)[-1] not in BEHAVIOURLESS
        and not f[len(service) + 1:].startswith(BEHAVIOURLESS_TREES)
    ]


def touched_beyond_spec_self_test() -> int:
    """The behaviourless exemptions must not swallow a file that can change the served contract."""
    svc, spec = "openbank-x", "openbank-x/src/main/resources/openapi.yaml"
    cases = [
        ("a test", f"{svc}/src/test/kotlin/XIT.kt", False),
        ("authored docs", f"{svc}/src/main/resources/docs/03-api.en.md", False),
        ("release-please output", f"{svc}/version.txt", False),
        ("production code", f"{svc}/src/main/kotlin/X.kt", True),
        ("application config", f"{svc}/src/main/resources/application.yaml", True),
        ("a migration", f"{svc}/src/main/resources/db/migration/V2__x.sql", True),
        ("the build", f"{svc}/build.gradle.kts", True),
        ("a docs dir that is not the authored one", f"{svc}/docs/x.md", True),
        ("another service's code", "openbank-y/src/main/kotlin/Y.kt", False),
    ]
    failures = 0
    for why, path, disqualifies in cases:
        got = bool(service_touched_beyond_spec(svc, spec, [spec, path]))
        if got != disqualifies:
            print(f"SELF-TEST FAIL: touched-beyond-spec: {why} ({path}) disqualifies={got}, expected {disqualifies}")
            failures += 1
        else:
            print(f"ok: touched-beyond-spec: {why} disqualifies={got}")
    return failures


def _self_test() -> int:
    """Feed url_majors the cases it MUST reject, and the ones it must still find.

    A gate that has only ever passed is unfalsified. This one shipped a false positive for months
    because it scanned raw text (#3119), so the interesting cases here are the ones that must NOT
    contribute a major: prose, KDoc, and a plain string that is not a @Path.
    """
    import tempfile

    cases: list[tuple[str, str, set[int]]] = [
        (
            "a line comment naming a URL major is not an endpoint",
            '// under ADR-0048 a major bump means moving every path to /api/v2\n'
            '@Path("/api/v1/campaigns")\nclass R\n',
            {1},
        ),
        (
            "a KDoc naming a URL major is not an endpoint",
            '/**\n * A major bump means serving under /api/v2, which we avoided.\n */\n'
            '@Path("/api/v1/campaigns")\nclass R\n',
            {1},
        ),
        (
            "a KDoc containing a nested open-comment marker still ends where it ends",
            '/**\n * Kotlin block comments nest: /* like this */ and the KDoc continues.\n'
            ' * Mentioning /api/v9 here must not count.\n */\n'
            '@Path("/api/v1/x")\nclass R\n',
            {1},
        ),
        (
            "a bare string that is not a @Path does not count",
            'const val UPSTREAM = "http://alertmanager/api/v2/alerts"\n'
            '@Path("/api/v1/x")\nclass R\n',
            {1},
        ),
        (
            "a URL containing // inside a string does not swallow the rest of the line",
            'val u = "http://host/x"; @Path("/api/v4/y")\nclass R\n',
            {4},
        ),
        (
            "real @Path annotations are still found, including several majors",
            '@Path("/api/v1/a")\nclass A\n@Path("/api/v3/b")\nclass B\n',
            {1, 3},
        ),
        (
            "a served version segment counts under any prefix, not just /api",
            '@Path("/customer/v1/feedback")\nclass R\n',
            {1},
        ),
        (
            "a path segment that merely starts with v is not a version",
            '@Path("/verify/thing")\nclass R\n',
            set(),
        ),
        (
            "a resource that also injects a client still contributes its own served path",
            'import org.eclipse.microprofile.rest.client.inject.RestClient\n'
            'class R {\n  @RestClient lateinit var up: Upstream\n}\n'
            '@Path("/api/v1/clearing")\nclass ClearingResource\n',
            {1},
        ),
        (
            "a commented-out @Path does not count",
            '// @Path("/api/v7/dead")\n@Path("/api/v1/live")\nclass R\n',
            {1},
        ),
    ]

    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, source, expected in cases:
            svc = Path(tmp) / name.replace(" ", "_")
            pkg = svc / "src/main/kotlin/com/openbank/x"
            pkg.mkdir(parents=True, exist_ok=True)
            (pkg / "R.kt").write_text(source, encoding="utf-8")
            got = url_majors(svc)
            if got != expected:
                print(f"SELF-TEST FAIL: {name}: expected {sorted(expected)}, got {sorted(got)}")
                failures += 1
            else:
                print(f"ok: {name}")
        generated = Path(tmp) / "generated_server_contract"
        generated_src = generated / "src/main/kotlin/com/openbank/x"
        generated_src.mkdir(parents=True, exist_ok=True)
        (generated_src / "Adapter.kt").write_text("class Adapter\n", encoding="utf-8")
        (generated / "src/main/resources").mkdir(parents=True, exist_ok=True)
        (generated / "src/main/resources/openapi.yaml").write_text(
            "paths:\n  /api/v1/compatibility:\n    get: {}\n  /api/v2/items:\n    get: {}\n",
            encoding="utf-8",
        )
        (generated / "build.gradle.kts").write_text(
            'openApiGenerate { generatorName.set("kotlin-server") }\n',
            encoding="utf-8",
        )
        got = url_majors(generated)
        if got != {1, 2}:
            print(f"SELF-TEST FAIL: generated server spec majors: expected [1, 2], got {sorted(got)}")
            failures += 1
        else:
            print("ok: generated kotlin-server contract contributes its provider spec majors")
    # Two real services pin the distinction between the two major sets. Both were one edit away
    # from a regression while #3119 was being fixed, and neither is reachable from the synthetic
    # cases above.
    repo = Path(__file__).resolve().parents[2]
    live: list[tuple[str, str, set[int], set[int]]] = [
        (
            "openbank-psd2-service",
            "Berlin Group mandates /v1/consents, so it serves a version segment but NOT the "
            "house /api/v{N} shape — treating that as ownership would declare its "
            "EXTERNAL_CONTRACT_SERVICES exemption stale and fail every psd2 spec PR",
            set(),
            {1, 2},
        ),
        (
            "openbank-customer-edge",
            "serves /customer/v1, so D2 is assertable — under the old raw-text scan its major came "
            "from outbound URL strings addressed to document-service",
            set(),
            {1},
        ),
    ]
    for name, why, want_own, want_url in live:
        svc = repo / name
        if not (svc / "src/main/kotlin").is_dir():
            print(f"skip (not in tree): {name}")
            continue
        got_own, got_url = own_api_majors(svc), url_majors(svc)
        if got_own != want_own or got_url != want_url:
            print(
                f"SELF-TEST FAIL: {name}: own={sorted(got_own)} url={sorted(got_url)}, "
                f"expected own={sorted(want_own)} url={sorted(want_url)} — {why}"
            )
            failures += 1
        else:
            print(f"ok: {name} own={sorted(got_own)} url={sorted(got_url)}")

    failures += classification_self_test()
    failures += idempotency_hardening_self_test()
    failures += migration_assertion_self_test()
    failures += touched_beyond_spec_self_test()

    if failures:
        print(f"{failures} self-test case(s) failed")
        return 1
    print("all API contract self-test groups passed")
    return 0


BASE_SPEC = """openapi: "3.0.3"
info:
  title: Self Test API
  version: "1.2.0"
paths:
  /api/v1/things:
    get:
      responses:
        "200":
          description: ok
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Thing"
components:
  schemas:
    Thing:
      type: object
      properties:
        id:
          type: string
          description: the id
"""


def classification_self_test() -> int:
    """Falsify [oasdiff_classify] itself — the half this gate's self-test never covered.

    Every case above exercises `url_majors`; none has ever run the classifier, which is the
    part that decides whether a PR is red. That gap is exactly how #6380 survived: `editorial`
    demands a PATCH bump, the bump was itself a non-excluded diff, so the change reclassified as
    `additive` and no version could satisfy it. The estate shows the shape — 231 MINOR bumps and
    17 PATCH bumps across 50 specs, every PATCH dated 2026-07-06/07 when the gate was being
    wired, none in the six weeks since.

    Case 1 is the negative control: it FAILS against the code as it stood before #6380.
    """
    oasdiff = shutil.which("oasdiff")
    if not oasdiff:
        # Never silently pass: an absent binary means these cases did not run, and a self-test
        # that cannot fail is decoration.
        print("SELF-TEST FAIL: oasdiff not on PATH — classification cases did not run")
        return 1

    bumped = BASE_SPEC.replace('version: "1.2.0"', 'version: "1.2.1"')
    cases: list[tuple[str, str, str, str]] = [
        (
            "a PATCH bump and NOTHING else is editorial, not additive (#6380)",
            BASE_SPEC,
            bumped,
            "editorial",
        ),
        (
            "a reworded description plus its PATCH bump stays editorial",
            BASE_SPEC,
            bumped.replace("description: the id", "description: the identifier of the thing"),
            "editorial",
        ),
        (
            "a new property is additive even with only a PATCH bump",
            BASE_SPEC,
            bumped.replace(
                "        id:\n          type: string\n",
                "        id:\n          type: string\n        label:\n          type: string\n",
            ),
            "additive",
        ),
        (
            "an identical document is none",
            BASE_SPEC,
            BASE_SPEC,
            "none",
        ),
    ]

    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, old_text, new_text, expected in cases:
            old_p = Path(tmp) / "old.yaml"
            new_p = Path(tmp) / "new.yaml"
            old_p.write_text(old_text, encoding="utf-8")
            new_p.write_text(new_text, encoding="utf-8")
            try:
                got, _ = oasdiff_classify(oasdiff, old_p, new_p)
            except (SpecLoadError, RuntimeError) as exc:
                print(f"SELF-TEST FAIL: {name}: classifier raised {exc}")
                failures += 1
                continue
            if got != expected:
                print(f"SELF-TEST FAIL: {name}: expected {expected}, got {got}")
                failures += 1
            else:
                print(f"ok: {name}")

    # The bump the classification then demands must be reachable — the property #6380 broke.
    for kind, old_v, new_v, want in [
        ("editorial", (1, 2, 0), (1, 2, 1), True),
        ("editorial", (1, 2, 0), (1, 2, 0), False),
        ("additive", (1, 2, 0), (1, 2, 1), False),
        ("additive", (1, 2, 0), (1, 3, 0), True),
        ("breaking", (1, 2, 0), (2, 0, 0), True),
    ]:
        if bump_satisfied(kind, old_v, new_v) is not want:
            print(f"SELF-TEST FAIL: bump_satisfied({kind}, {old_v}, {new_v}) is not {want}")
            failures += 1
    if not failures:
        print("ok: every classification has a reachable version that satisfies it")
    return failures


IDEM_BASE_SPEC = """openapi: 3.0.3
info:
  title: fixture
  version: "1.2.0"
paths:
  /api/v1/things/{id}/refresh:
    post:
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: ok
  /api/v1/things/{id}:
    get:
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: ok
  /api/v1/other:
    post:
      responses:
        "200":
          description: ok
"""


def _with_header(spec: str, path_line: str, name: str) -> str:
    """Insert a REQUIRED header parameter as the first parameter of the operation under path_line."""
    head, tail = spec.split(path_line, 1)
    marker = "      parameters:\n"
    header = (
        f"      parameters:\n        - name: {name}\n          in: header\n          required: true\n"
        f"          schema:\n            type: string\n"
    )
    return head + path_line + tail.replace(marker, header, 1)


def idempotency_hardening_self_test() -> int:
    """Falsify [idempotency_hardening]: one must-PASS case, and every narrowing must bite."""
    oasdiff = shutil.which("oasdiff")
    if not oasdiff:
        print("SELF-TEST FAIL: oasdiff not on PATH — idempotency-hardening cases did not run")
        return 1
    post_line = "  /api/v1/things/{id}/refresh:\n"
    get_line = "  /api/v1/things/{id}:\n"
    post_key = _with_header(IDEM_BASE_SPEC, post_line, "Idempotency-Key")
    cases: list[tuple[str, str, bool, bool]] = [
        ("MUST PASS: only a required Idempotency-Key on a money-path POST", post_key, True, True),
        ("lower-case header name still qualifies", _with_header(IDEM_BASE_SPEC, post_line, "idempotency-key"), True, True),
        ("MUST FAIL: the same header on a GET", _with_header(IDEM_BASE_SPEC, get_line, "Idempotency-Key"), True, False),
        ("MUST FAIL: a different required header on a POST", _with_header(IDEM_BASE_SPEC, post_line, "X-Tenant"), True, False),
        (
            "MUST FAIL: Idempotency-Key plus another breaking change (an endpoint removed)",
            post_key.replace('  /api/v1/other:\n    post:\n      responses:\n        "200":\n          description: ok\n', ""),
            True,
            False,
        ),
        ("MUST FAIL: a service that is not money-path", post_key, False, False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        old_p = Path(tmp) / "old.yaml"
        new_p = Path(tmp) / "new.yaml"
        old_p.write_text(IDEM_BASE_SPEC, encoding="utf-8")
        for name, new_text, money_path, expected in cases:
            new_p.write_text(new_text, encoding="utf-8")
            entries = breaking_entries(oasdiff, old_p, new_p)
            got = idempotency_hardening(entries, money_path)
            if not entries and expected is False and money_path:
                print(f"SELF-TEST FAIL: {name}: oasdiff reported nothing breaking — the fixture proves nothing")
                failures += 1
            elif got is not expected:
                print(f"SELF-TEST FAIL: {name}: expected {expected}, got {got} ({[e.get('id') for e in entries]})")
                failures += 1
            else:
                print(f"ok: {name}")

    rules = "x: 1\nmoney_path_services:\n  - openbank-ledger-service\n  # a comment\n  - openbank-new-service\nnext_key:\n  - not-a-service\n"
    got_set = money_path_services(rules)
    if got_set != {"openbank-ledger-service", "openbank-new-service"}:
        print(f"SELF-TEST FAIL: money_path_services parsed {sorted(got_set)}")
        failures += 1
    else:
        print("ok: money_path_services reads the block list and stops at the next key")
    return failures


def migration_assertion_self_test() -> int:
    entries = [{"operation": "POST", "path": "/api/v1/things/{id}/refresh"}]
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        (repo / "src").mkdir()
        (repo / "tests").mkdir()
        (repo / "src/client.ts").write_text("// caller\n")
        (repo / "tests/client.test.ts").write_text("// proof\n")
        line = ("- `openbank-example POST /api/v1/things/{id}/refresh` | callers: `src/client.ts` "
                "| proof: `tests/client.test.ts`")
        body = f"## Idempotency caller migration\n{line}\n"
        changed = {"src/client.ts", "tests/client.test.ts"}
        cases = [
            ("complete assertion", body, changed, False),
            ("missing section", "", changed, True),
            ("missing operation", "## Idempotency caller migration\n", changed, True),
            ("other service cannot assert this operation", body.replace("openbank-example", "openbank-other"), changed, True),
            ("unlisted test change", body, {"src/client.ts"}, True),
            ("missing caller file", body.replace("src/client.ts", "src/missing.ts"), changed, True),
            ("no in-repo callers", body.replace("`src/client.ts`", "none"), changed, False),
        ]
        failures = 0
        for name, candidate, paths, must_find in cases:
            findings = migration_assertion_findings(candidate, "openbank-example", entries, paths, repo)
            if bool(findings) != must_find:
                print(f"SELF-TEST FAIL: {name}: findings={findings}")
                failures += 1
        if not failures:
            print("ok: caller migration assertions require each operation and changed test proof")
        return failures


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", help="git sha of the PR base (not needed with --self-test)")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--oasdiff", default="oasdiff")
    ap.add_argument("--self-test", action="store_true", help="run the url_majors falsification cases")
    args = ap.parse_args()
    if args.self_test:
        return _self_test()

    if shutil.which(args.oasdiff) is None:
        print(f"::error::oasdiff binary not found ({args.oasdiff}) — install step missing?")
        return 1

    level = "error" if args.enforce else "warning"
    findings: list[str] = []

    changed_all = sh("git", "diff", "--name-only", args.base, "HEAD").splitlines()
    changed = [line for line in changed_all if OPENAPI_GLOB.match(line)]
    if not changed:
        print("api-contract gate: no openapi.yaml changed — nothing to classify.")
        return 0

    for rel in changed:
        service = OPENAPI_GLOB.match(rel).group(1)
        service_dir = Path(service)
        head_path = Path(rel)
        if not head_path.is_file():
            print(f"api-contract gate: {rel} deleted — skipping (service removal is reviewed elsewhere).")
            continue
        new_text = head_path.read_text(encoding="utf-8", errors="replace")
        new_raw = info_version(new_text)
        new_v = parse_semver(new_raw) if new_raw else None
        if new_v is None:
            findings.append(f"{rel}: cannot parse info.version on HEAD ({new_raw!r})")
            continue

        old_text = subprocess.run(
            ["git", "show", f"{args.base}:{rel}"], capture_output=True, text=True
        )
        if old_text.returncode != 0:
            print(f"api-contract gate: {rel} is new at base — no diff to classify (invariant still checked).")
        else:
            old_raw = info_version(old_text.stdout)
            old_v = parse_semver(old_raw) if old_raw else None
            with tempfile.NamedTemporaryFile(
                "w", suffix=".yaml", prefix="openapi-base-", delete=False
            ) as tf:
                tf.write(old_text.stdout)
                old_path = Path(tf.name)
            try:
                kind, breaking = oasdiff_classify(args.oasdiff, old_path, head_path)
            except SpecLoadError as e:  # old_path is removed after the reclassification below
                findings.append(
                    f"{rel}: cannot classify — spec fails strict OpenAPI resolution "
                    f"(fix the dangling $ref): {e}"
                )
                kind = None

            # A breaking DOCUMENT diff is not a breaking CONTRACT change when the PR changed
            # nothing else in the service: the running server is byte-identical, so no client
            # that works today can stop working. What breaks is a client generated from a
            # document that never described this server — already broken before the edit.
            # Demanding a MAJOR bump there is not merely strict, it is unsatisfiable: D2 below
            # requires major(info.version) == the served URL major, which a spec-only PR cannot
            # move. Measured on aml (#2312/#2313): red at 1.0.0 for want of a MAJOR, red at
            # 2.0.0 for violating D2, and the only green spec was one that kept a phantom enum
            # value and a wrong default.
            #
            # The discriminator is mechanical, not declared — no marker file, no PR-body token
            # to assert a spec "was never served". Touch any other file in the service and the
            # normal breaking rule applies unchanged.
            #
            # This opens no new hole. Landing a genuinely breaking change as code-only in one PR
            # and the spec in another already escapes this gate entirely, because it is scoped to
            # spec diffs and the first PR has none. What the reclassification does NOT check is
            # whether the corrected document is true — that is openapi-route-conformance for the
            # route set, and still nothing for schemas.
            if kind == "breaking":
                rules_text = RULES_YAML.read_text(encoding="utf-8") if RULES_YAML.is_file() else ""
                is_money_path = service in money_path_services(rules_text)
                entries = breaking_entries(args.oasdiff, old_path, head_path)
                if idempotency_hardening(entries, is_money_path):
                    if os.environ.get("GITHUB_EVENT_NAME") != "push":
                        findings.extend(migration_assertion_findings(
                            os.environ.get("PR_BODY", ""), service, entries, set(changed_all),
                        ))
                    print(
                        f"::notice::api-contract gate: {service}: every breaking change is a newly "
                        f"required Idempotency-Key header on a money-path POST — classifying as "
                        f"IDEMPOTENCY-HARDENING (MINOR). The PR must inventory callers and "
                        f"change a proof test for every operation (ADR-0330)."
                    )
                    kind = "idempotency-hardening"
            old_path.unlink(missing_ok=True)
            if kind == "breaking":
                others = service_touched_beyond_spec(service, rel, changed_all)
                if not others:
                    detail = f" (first: {breaking[0]})" if breaking else ""
                    print(
                        f"::notice::api-contract gate: {service}: spec-only PR — reclassifying "
                        f"{len(breaking)} breaking document change(s){detail} as a CORRECTION. "
                        f"No other file in {service} changed, so the served contract is unchanged "
                        f"and a MAJOR bump would violate the ADR-0048 D2 URL-major invariant. "
                        f"Requiring MINOR instead."
                    )
                    kind = "correction"
                else:
                    print(
                        f"api-contract gate: {service}: breaking, and the PR also changes "
                        f"{len(others)} other file(s) in the service (e.g. {others[0]}) — "
                        f"treating as a real contract change."
                    )

            if kind is None:
                pass  # unloadable spec already reported; invariant checks below still run
            elif kind == "none":
                print(f"api-contract gate: {service}: openapi.yaml touched but semantically unchanged.")
            elif old_v is None:
                findings.append(f"{rel}: cannot parse info.version at base ({old_raw!r})")
            else:
                required = REQUIRED_BUMP[kind]
                if bump_satisfied(kind, old_v, new_v):
                    print(
                        f"api-contract gate: {service}: {kind} change, "
                        f"info.version {old_raw} -> {new_raw} — OK (required >= {required})."
                    )
                else:
                    detail = f" First breaking change: {breaking[0]}" if breaking else ""
                    findings.append(
                        f"{rel}: {kind} API change requires an info.version {required} bump, "
                        f"but it moved {old_raw} -> {new_raw}.{detail} "
                        f"(API axis is independent of version.txt — ADR-0048 D5)"
                    )

        # D2 API invariant on HEAD. Only meaningful for a stable (>=1.0) contract that the
        # service actually serves under its own /api/v{N}. Skip when:
        #  - doc_major == 0: a pre-1.0 spec (semver major 0 is explicitly unstable; product-catalog)
        #  - the service serves no own /api/v{N} path AND is DECLARED external-contract in
        #    EXTERNAL_CONTRACT_SERVICES above. Serving no /api/v{N} is not itself evidence of an
        #    external contract — it is equally true of a service that just skipped the convention,
        #    and that one must not inherit the exemption silently (issue #2276).
        cfg_major = config_api_major(service_dir)
        majors = url_majors(service_dir)
        doc_major = new_v[0]
        external_reason = EXTERNAL_CONTRACT_SERVICES.get(service)
        own_majors = own_api_majors(service_dir)
        if own_majors and external_reason:
            # The declaration has stopped being true: it now serves its own /api/v{N}, so D2 is
            # assertable and the exemption is stale. Fail rather than let the list outlive its
            # justification (KNOWN_UNCOVERED shape).
            findings.append(
                f"{service}: listed in EXTERNAL_CONTRACT_SERVICES but now serves its own "
                f"/api/v{max(own_majors)} — the exemption is stale. Drop the entry from "
                f".github/scripts/check-api-contract.py so D2 is asserted again (issue #2276)."
            )
        if doc_major == 0:
            print(f"api-contract gate: {service}: info.version {new_raw} is pre-1.0 — D2 invariant not asserted.")
        elif external_reason:
            print(
                f"api-contract gate: {service}: declared external contract — D2 not asserted. "
                f"Reason: {external_reason}"
            )
        elif not majors:
            findings.append(
                f"{service}: serves no versioned path (no `v{{N}}` segment in any @Path), so "
                f"ADR-0048 D2 cannot be asserted and info.version {new_raw} has nothing to drift "
                f"against. Either adopt /api/v{{N}} or "
                f"add the service to EXTERNAL_CONTRACT_SERVICES in "
                f".github/scripts/check-api-contract.py with the external spec it follows "
                f"(issue #2276)."
            )
        else:
            if cfg_major is not None and cfg_major != doc_major:
                findings.append(
                    f"{service}: API invariant broken — major(info.version)={doc_major} but "
                    f"openbank.api.version={cfg_major} (ADR-0048 D2)"
                )
            if max(majors) != doc_major:
                findings.append(
                    f"{service}: API invariant broken — major(info.version)={doc_major} but the newest "
                    f"served URL major is /api/v{max(majors)} (ADR-0048 D2/D4)"
                )

    for f in findings:
        print(f"::{level}::api-contract gate: {f}")

    if findings and args.enforce:
        return 1
    if findings:
        print(
            f"api-contract gate: {len(findings)} finding(s) — advisory until the ADR-0144 "
            "target_enforce_date; will become a hard gate."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
