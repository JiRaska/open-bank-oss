#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ANCHOR VERIFICATION-KEY REGISTRY REFRESHER (ADR-0031 D5, issue #12923 gap 6).
#
# WHY THIS EXISTS
#   A third party verifying audit anchors needs the KMS public key for the key id
#   RECORDED ON each anchor. Until now that meant AWS credentials (kms get-public-key)
#   or the bank-gated /anchors/verification-key endpoint — both gates between the
#   public evidence (the anchor export) and an independent verdict. The registry at
#   docs/audit/anchor-verification-keys.json closes that gap: public verification
#   material, committed in-repo, refreshed automatically.
#
#   This script is the refresher half. The weekly audit-anchor-attestation workflow
#   fetches the live export AND the recorded KMS public keys, independently verifies
#   the export, and only then invokes this script with the fetched PEMs. The registry
#   therefore only ever gains keys that signed an export which the offline verifier
#   just accepted — verified evidence in, verification material out.
#
# WHAT IT DOES
#   * Compares each fetched verification-key-*.pem against the registry:
#       missing keyId  -> add an entry (firstSeen = date of this run)
#       PEM differs    -> update the entry's publicKeyPem (firstSeen preserved)
#       identical      -> no-op
#     Registry entries for generations ABSENT from the export are left untouched —
#     a retired generation stays listed as historical record.
#   * Idempotent: an already-current registry produces NO commit and NO PR (exit 0).
#   * On drift (with --apply): branch agent/audit-anchor-key-registry-update off
#     origin/main, ONE signed commit, push, and create-or-reuse ONE PR whose body
#     names only the keyId(s) and the SHA-256 fingerprint(s) of the PEM(s) — it
#     claims nothing more than a verification-material refresh.
#
# WHAT IT NEVER DOES
#   * Touches main, or merges anything (the bot writes the diff; a human merges).
#   * Publishes private material: every entry is a PUBLIC key. A public key verifies;
#     it cannot sign. The registry grants no capability the KMS public half did not
#     already grant to anyone with GetPublicKey access.
#
# USAGE
#   update-anchor-key-registry.py --keys-dir <dir> [--registry <path>] [--apply]
#                                 [--branch <name>] [--base <ref>]
#   update-anchor-key-registry.py --self-test
#
# Without --apply the script is read-only: it prints the drift report and exits 3
# when the registry is stale (0 when current) — a manual check a human can run.
#
# EXIT CODES
#   0  registry current (check mode) or PR opened/updated (apply mode)
#   3  registry stale (check mode only)
#   4  input error: unreadable/malformed registry, missing PEMs, bad arguments

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

EXIT_OK = 0
EXIT_STALE = 3
EXIT_INPUT_ERROR = 4

REGISTRY_DEFAULT = "docs/audit/anchor-verification-keys.json"
BRANCH_DEFAULT = "agent/audit-anchor-key-registry-update"
BASE_DEFAULT = "main"
COMMIT_SUBJECT = "chore(audit): refresh anchor verification key registry"
KEY_SOURCE = "kms get-public-key via attestation workflow"
ALGORITHM = "ECDSA_P256_SHA256"
SAFE_FILENAME_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-")

# The filename sanitization MUST mirror the attest job's `tr -c 'A-Za-z0-9._-' '_'`
# (one '_' per non-safe character) or the registry step cannot find the PEMs the
# export step wrote.
def safe_filename(key_id: str) -> str:
    return "".join(c if c in SAFE_FILENAME_CHARS else "_" for c in key_id)


def normalize_pem(text: str) -> str:
    """Canonical PEM form: trailing whitespace stripped per line, single trailing \n.

    The registry must not churn on cosmetic differences (editor trailing spaces, a
    missing final newline), and a fingerprint is only meaningful over one canonical
    byte sequence, so both comparison and hashing go through this form.
    """
    lines = [ln.rstrip() for ln in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines) + "\n"


def fingerprint(pem: str) -> str:
    """SHA-256 over the canonical PEM bytes — what the PR body discloses per key."""
    return hashlib.sha256(normalize_pem(pem).encode("utf-8")).hexdigest()


class RegistryError(Exception):
    """The registry is unreadable or malformed — fail loud, never "refresh from scratch"."""


def load_registry(path: pathlib.Path) -> list[dict]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise RegistryError(f"{path}: unreadable or malformed registry JSON: {e}") from e
    if not isinstance(raw, list):
        raise RegistryError(f"{path}: registry must be a JSON array of entries, got {type(raw).__name__}")
    for i, entry in enumerate(raw):
        if not isinstance(entry, dict) or not isinstance(entry.get("keyId"), str) \
                or not isinstance(entry.get("publicKeyPem"), str):
            raise RegistryError(f"{path}: entry {i} lacks a string keyId/publicKeyPem: {entry!r}")
    return raw


def render_registry(entries: list[dict]) -> str:
    """Deterministic serialization: sorted by keyId, 2-space indent, trailing newline.

    Sorting makes the file's shape independent of fetch order, so the git diff of a
    refresh is exactly the entries that changed — nothing else moves.
    """
    ordered = sorted(entries, key=lambda e: e["keyId"])
    return json.dumps(ordered, indent=2) + "\n"


def fetch_keys_from_dir(keys_dir: pathlib.Path) -> dict[str, str]:
    """keyId -> canonical PEM, from the attest bundle (kms-key-ids.txt + *.pem files)."""
    ids_file = keys_dir / "kms-key-ids.txt"
    if not ids_file.is_file():
        raise RegistryError(f"{ids_file}: absent — not an attest bundle directory")
    fetched: dict[str, str] = {}
    for key_id in ids_file.read_text(encoding="utf-8").splitlines():
        key_id = key_id.strip()
        if not key_id:
            continue
        pem_file = keys_dir / f"verification-key-{safe_filename(key_id)}.pem"
        try:
            fetched[key_id] = normalize_pem(pem_file.read_text(encoding="utf-8"))
        except OSError as e:
            raise RegistryError(f"{pem_file}: key {key_id} is listed but its PEM is missing: {e}") from e
    return fetched


def compute_changes(registry: list[dict], fetched: dict[str, str], today: str) -> tuple[bool, list[dict], list[dict]]:
    """Returns (changed, new_registry, changes).

    changes is one {keyId, kind: added|updated, old_fingerprint, new_fingerprint} per
    drifted generation. A generation in the registry but not in `fetched` is left
    untouched — retirement is not deletion.
    """
    by_id = {e["keyId"]: dict(e) for e in registry}
    changes: list[dict] = []
    for key_id, pem in fetched.items():
        pem = normalize_pem(pem)
        entry = by_id.get(key_id)
        if entry is None:
            changes.append({"keyId": key_id, "kind": "added",
                            "old_fingerprint": None, "new_fingerprint": fingerprint(pem)})
            by_id[key_id] = {
                "keyId": key_id,
                "publicKeyPem": pem,
                "algorithm": ALGORITHM,
                "firstSeen": today,
                "source": KEY_SOURCE,
                "note": "first observed by the weekly live audit-anchor attestation",
            }
        elif normalize_pem(entry["publicKeyPem"]) != pem:
            old_fp = fingerprint(entry["publicKeyPem"])
            changes.append({"keyId": key_id, "kind": "updated",
                            "old_fingerprint": old_fp, "new_fingerprint": fingerprint(pem)})
            entry["publicKeyPem"] = pem
            entry["note"] = ("PEM refreshed by the weekly live audit-anchor attestation; "
                             f"previous fingerprint {old_fp}")
    return bool(changes), list(by_id.values()), changes


def format_drift(changes: list[dict]) -> str:
    rows = "\n".join(
        f"| `{c['keyId']}` | {c['kind']} | `{c['old_fingerprint'] or '—'}` | `{c['new_fingerprint']}` |"
        for c in changes)
    return ("| keyId | Change | Old PEM SHA-256 | New PEM SHA-256 |\n"
            "|---|---|---|---|\n" + rows)


def pr_body(changes: list[dict]) -> str:
    """The PR claims ONLY a verification-material refresh — keyIds + fingerprints."""
    listing = "\n".join(f"- `{c['keyId']}` ({c['kind']}) — PEM SHA-256 `{c['new_fingerprint']}`"
                        for c in changes)
    return (
        "## What\n\n"
        "Refresh of the public audit-anchor verification-key registry "
        "(`docs/audit/anchor-verification-keys.json`) — public verification material only. "
        "The PEMs are public keys (they verify signatures; they cannot sign).\n\n"
        f"{listing}\n\n"
        "Fetched from the live KMS via the weekly `audit-anchor-attestation` workflow, "
        "after the anchor export independently verified. This PR claims nothing more "
        "than a verification-material refresh. A human reviews and merges; the bot "
        "writes the diff, never presses merge.\n\n"
        "Refs #12923\n")


def run_git(*args: str) -> None:
    subprocess.run(["git", *args], check=True, capture_output=True, text=True)


def gh_json(repo: str, path: str) -> list:
    out = subprocess.run(["gh", "api", f"repos/{repo}/{path}"],
                         check=True, capture_output=True, text=True)
    return json.loads(out.stdout)


def open_or_update_pr(changes: list[dict], branch: str, base: str) -> None:
    repo = subprocess.run(["git", "remote", "get-url", "origin"],
                          check=True, capture_output=True, text=True).stdout.strip()
    repo = repo.removeprefix("https://github.com/").removesuffix(".git")
    owner = repo.split("/")[0]

    run_git("fetch", "origin", base)
    run_git("checkout", "-b", branch, f"origin/{base}")
    run_git("add", REGISTRY_DEFAULT)
    body_detail = "\n".join(f"- {c['keyId']}: {c['kind']} (PEM sha256 {c['new_fingerprint']})"
                            for c in changes)
    run_git("-c", "user.name=github-actions[bot]",
            "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "-s", "-m",
            f"{COMMIT_SUBJECT}\n\n{body_detail}\n\nRegistry diff: {format_drift(changes)}\n\n"
            "Refs #12923")

    existing = gh_json(repo, f"pulls?state=open&head={owner}:{branch}")
    if existing:
        # The branch is bot-owned with a fixed name and the workflow's concurrency group
        # serializes runs, so a force push to the SAME branch keeps ONE PR per drift
        # instead of accumulating one PR per week until a human merges.
        print(f"PR #{existing[0]['number']} already open for {branch} — updating it")
        run_git("push", "--force", "origin", f"HEAD:refs/heads/{branch}")
        return
    # First drift: create the branch and the PR together.
    run_git("push", "-u", "origin", branch)
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as bf:
        bf.write(pr_body(changes))
        body_path = bf.name
    subprocess.run(["gh", "pr", "create", "--title", COMMIT_SUBJECT,
                    "--body-file", body_path, "--base", base],
                   check=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="refresh the public audit-anchor verification-key registry")
    ap.add_argument("--keys-dir", type=pathlib.Path,
                    help="attest bundle dir holding kms-key-ids.txt + verification-key-*.pem")
    ap.add_argument("--registry", type=pathlib.Path, default=pathlib.Path(REGISTRY_DEFAULT))
    ap.add_argument("--apply", action="store_true",
                    help="open/update the registry PR on drift (default: check only)")
    ap.add_argument("--branch", default=BRANCH_DEFAULT)
    ap.add_argument("--base", default=BASE_DEFAULT)
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.keys_dir:
        ap.error("--keys-dir is required unless --self-test")

    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    try:
        registry = load_registry(a.registry)
        fetched = fetch_keys_from_dir(a.keys_dir)
        changed, new_registry, changes = compute_changes(registry, fetched, today)
    except RegistryError as e:
        print(f"::error::{e}")
        return EXIT_INPUT_ERROR

    if not changed:
        print(f"registry {a.registry} already current ({len(fetched)} fetched key generation(s))")
        return EXIT_OK

    print(f"registry drift: {len(changes)} key generation(s) changed")
    print(format_drift(changes))
    if not a.apply:
        print("check mode: re-run with --apply to open the registry update PR")
        return EXIT_STALE

    a.registry.write_text(render_registry(new_registry), encoding="utf-8")
    open_or_update_pr(changes, a.branch, a.base)
    print(f"registry update PR opened/updated on branch {a.branch}")
    return EXIT_OK


# ── self-test ─────────────────────────────────────────────────────────────────

PEM_A = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaaaaaaaaaaaaaaaaaaaaaaaa\n-----END PUBLIC KEY-----\n"
PEM_B = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbbbbbbbbbbbbbbbbbbbbbbbb\n-----END PUBLIC KEY-----\n"
KEY_ID = "arn:aws:kms:eu-central-1:123456789012:key/11111111-2222-3333-4444-555555555555"


def self_test() -> int:
    failures: list[str] = []

    def check(label: str, cond: bool) -> None:
        print(("  ok   " if cond else "  FAIL ") + label)
        if not cond:
            failures.append(label)

    # Missing key adds an entry with the full required shape.
    changed, new_reg, changes = compute_changes([], {KEY_ID: PEM_A}, "2026-10-22")
    entry = new_reg[0]
    check("missing key: adds an entry", changed and len(new_reg) == 1)
    check("missing key: entry carries keyId/algorithm/firstSeen/source",
          entry["keyId"] == KEY_ID and entry["algorithm"] == ALGORITHM
          and entry["firstSeen"] == "2026-10-22" and entry["source"] == KEY_SOURCE)
    check("missing key: change record is kind=added with the new fingerprint",
          changes[0]["kind"] == "added" and changes[0]["new_fingerprint"] == fingerprint(PEM_A)
          and changes[0]["old_fingerprint"] is None)

    # Identical registry is a no-op — the idempotence the weekly run relies on.
    reg_with_a = [{"keyId": KEY_ID, "publicKeyPem": PEM_A, "algorithm": ALGORITHM,
                   "firstSeen": "2026-10-01", "source": KEY_SOURCE, "note": "seeded"}]
    changed, new_reg, changes = compute_changes(reg_with_a, {KEY_ID: PEM_A}, "2026-10-22")
    check("identical: no change, registry untouched", not changed and not changes)
    check("identical: cosmetic PEM difference (no trailing newline) is still a no-op",
          compute_changes(reg_with_a, {KEY_ID: PEM_A.rstrip("\n")}, "2026-10-22")[0] is False)

    # Changed PEM updates the entry but preserves firstSeen (the generation's history).
    changed, new_reg, changes = compute_changes(reg_with_a, {KEY_ID: PEM_B}, "2026-10-22")
    check("changed PEM: updates and keeps firstSeen",
          changed and new_reg[0]["publicKeyPem"] == PEM_B and new_reg[0]["firstSeen"] == "2026-10-01")
    check("changed PEM: change record carries old and new fingerprints",
          changes[0]["kind"] == "updated" and changes[0]["old_fingerprint"] == fingerprint(PEM_A)
          and changes[0]["new_fingerprint"] == fingerprint(PEM_B))
    check("changed PEM: note records the previous fingerprint",
          fingerprint(PEM_A) in new_reg[0]["note"])

    # A generation in the registry but absent from the export is NOT removed.
    retired = dict(reg_with_a[0], keyId=KEY_ID + "-retired")
    changed, new_reg, _ = compute_changes(reg_with_a + [retired], {KEY_ID: PEM_A}, "2026-10-22")
    check("retired generation: kept, not deleted", not changed and len(new_reg) == 2)

    # Malformed registries fail loud, never "refresh from scratch".
    for label, bad in [
        ("not JSON at all", "this is not json"),
        ("not an array", '{"keyId": "x"}'),
        ("entry missing keyId", json.dumps([{"publicKeyPem": PEM_A}])),
    ]:
        tmp = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        tmp.write(bad)
        tmp.close()
        try:
            load_registry(pathlib.Path(tmp.name))
            check(f"malformed registry fails loud: {label}", False)
        except RegistryError:
            check(f"malformed registry fails loud: {label}", True)

    # Bundle reading mirrors the attest job's filename sanitization.
    with tempfile.TemporaryDirectory() as d:
        bundle = pathlib.Path(d)
        (bundle / "kms-key-ids.txt").write_text(KEY_ID + "\n\n")
        (bundle / f"verification-key-{safe_filename(KEY_ID)}.pem").write_text(PEM_A)
        got = fetch_keys_from_dir(bundle)
        check("bundle: reads keyId -> PEM with tr-compatible filename sanitization",
              got == {KEY_ID: PEM_A})
        (bundle / f"verification-key-{safe_filename(KEY_ID)}.pem").unlink()
        try:
            fetch_keys_from_dir(bundle)
            check("bundle: listed-but-missing PEM fails loud", False)
        except RegistryError:
            check("bundle: listed-but-missing PEM fails loud", True)

    # Serialization is deterministic (sorted by keyId) and round-trips.
    two = [dict(reg_with_a[0], keyId="b-key"), dict(reg_with_a[0], keyId="a-key")]
    rendered = render_registry(two)
    check("render: sorted by keyId, parses back identically",
          [e["keyId"] for e in json.loads(rendered)] == ["a-key", "b-key"])
    check("render: ends with exactly one trailing newline",
          rendered.endswith("]\n") and not rendered.endswith("\n\n"))

    # The PR body names keyId + fingerprint and nothing more ambitious.
    body = pr_body([{"keyId": KEY_ID, "kind": "added", "old_fingerprint": None,
                     "new_fingerprint": fingerprint(PEM_A)}])
    check("PR body: names the keyId and the PEM fingerprint", KEY_ID in body and fingerprint(PEM_A) in body)
    check("PR body: claims no more than a verification-material refresh",
          "verification-material refresh" in body and "Refs #12923" in body)

    if failures:
        print(f"self-test: {len(failures)} case(s) FAILED")
        return 1
    print("self-test: all cases behaved as required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
