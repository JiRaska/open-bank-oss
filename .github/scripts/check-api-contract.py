#!/usr/bin/env python3
"""api-contract gate — OpenAPI-diff bump classification (ADR-0048 D5).

For every `openbank-*/src/main/resources/openapi.yaml` changed against the PR
base, classify the API-contract change from the OpenAPI diff (oasdiff):

    breaking   => info.version MAJOR must move (new URL major, /api/v{N+1})
    correction => a breaking DOCUMENT diff in a PR that changes nothing else in the service:
                  the served contract is unchanged, so MINOR (see below)
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
import json
import re
import shutil
import subprocess
import sys
import tempfile
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


# JAX-RS REST *client* stubs carry the CALLEE's URL major (e.g. an Alertmanager client at
# /api/v2), not this service's own contract — they must not drive the D2 URL-major check.
_CLIENT_HINT = re.compile(r"@RegisterRestClient|RestClient\b")


def url_majors(service_dir: Path) -> set[int]:
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
        for m in re.finditer(r"/api/v(\d+)", text):
            majors.add(int(m.group(1)))
    return majors


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

    material = sh(
        oasdiff, "diff", str(old), str(new),
        "--exclude-elements", "description,examples,title,summary",
        "--format", "json", check=False,
    ).strip()
    if material and material not in ("{}", "null"):
        return "additive", []

    any_diff = sh(oasdiff, "diff", str(old), str(new), "--format", "json", check=False).strip()
    if any_diff and any_diff not in ("{}", "null"):
        return "editorial", []
    return "none", []


REQUIRED_BUMP = {"breaking": "MAJOR", "correction": "MINOR", "additive": "MINOR", "editorial": "PATCH"}


def bump_satisfied(kind: str, old_v: tuple[int, int, int], new_v: tuple[int, int, int]) -> bool:
    if kind == "breaking":
        return new_v[0] > old_v[0]
    if kind in ("additive", "correction"):
        return new_v[0] > old_v[0] or (new_v[0] == old_v[0] and new_v[1] > old_v[1])
    if kind == "editorial":
        return new_v > old_v
    return True


# Derived files that cannot change what the service serves, so their presence in a diff does
# not disqualify a spec correction. release-please writes both.
BEHAVIOURLESS = {"CHANGELOG.md", "version.txt"}


def service_touched_beyond_spec(service: str, spec_rel: str, changed_all: list[str]) -> list[str]:
    """Files in this service the PR changed other than its openapi.yaml (and derived files)."""
    return [
        f for f in changed_all
        if f.startswith(service + "/") and f != spec_rel and f.rsplit("/", 1)[-1] not in BEHAVIOURLESS
    ]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="git sha of the PR base")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--oasdiff", default="oasdiff")
    args = ap.parse_args()

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
            except SpecLoadError as e:
                findings.append(
                    f"{rel}: cannot classify — spec fails strict OpenAPI resolution "
                    f"(fix the dangling $ref): {e}"
                )
                kind = None
            finally:
                old_path.unlink(missing_ok=True)

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
        #  - the service serves no own /api/v{N} path: external-contract services (e.g. psd2 on
        #    the Berlin Group scheme serve /v1/consents, not /api/v{N}); their info.version major
        #    tracks the external spec, not a URL segment / api.version this repo owns.
        cfg_major = config_api_major(service_dir)
        majors = url_majors(service_dir)
        doc_major = new_v[0]
        if doc_major == 0:
            print(f"api-contract gate: {service}: info.version {new_raw} is pre-1.0 — D2 invariant not asserted.")
        elif not majors:
            print(f"api-contract gate: {service}: serves no own /api/v{{N}} path — external contract, D2 not asserted.")
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
