#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# OFFLINE, THIRD-PARTY AUDIT-ANCHOR VERIFIER (ADR-0031 D5, issue #5838).
#
# WHY THIS EXISTS
#   `GET /api/v1/audit/anchors/verify` is audit-service checking its own database with its own
#   signer bean. That is a useful self-check and it is NOT independent verification: the one
#   component whose tampering the anchor exists to detect is the component rendering the verdict.
#   Anyone who can rewrite `audit_entries` can equally serve a hand-written "INTACT".
#
#   This script is the other half. It runs on a laptop with no access to the bank, and takes only:
#     * an anchor export      (public evidence — the signed checkpoints)
#     * a public key in PEM   (public by construction; also obtainable straight from AWS KMS
#                              `GetPublicKey`, which is the point — you need not ask OpenBank)
#     * optionally an entry export, to check the log actually matches what was attested
#   It re-implements the canonical forms from first principles rather than importing the
#   service's own code, so a change to the producer that breaks the contract shows up here as a
#   rejection rather than being silently mirrored.
#
# WHAT IT CAN AND CANNOT ESTABLISH — read this before quoting a green run.
#   CAN:  that each anchor row is exactly what was signed (digest recomputation);
#         that the signature is valid under a key the producer does not hold at rest;
#         that the anchor sequence was not truncated, re-ordered or re-anchored backwards;
#         and, with --entries, that the exported log reproduces the attested chain head.
#   CANNOT: prove an anchor EXISTS for a period. A producer that never captured an anchor, or
#         quietly dropped a range before exporting, presents nothing to reject. Absence of
#         evidence is reported as UNVERIFIABLE (exit 3), never as VERIFIED. Nor is there a
#         trusted time source here: `signedAt` is the producer's own claim. An external
#         timestamping authority / transparency log would close both, and does not exist yet.
#
# OUTCOMES ARE FOUR, NOT TWO. A skipped/absent check must never share a result with a passing
# one (this repo already paid for that with PushResult.skipped(), #4348):
#   0  VERIFIED     every anchor recomputed, signature-valid, sequence sound
#   2  TAMPERED     something was rejected — the message names what
#   3  UNVERIFIABLE nothing to verify, or no key for the recorded key id (HMAC anchors land here:
#                   a shared secret is by definition not third-party verifiable)
#   4  INPUT_ERROR  bad arguments or malformed export
#
# Usage:
#   verify-audit-anchors.py --anchors anchors.json --public-key kms-pub.pem [--entries entries.json]
#   verify-audit-anchors.py --self-test

import argparse
import base64
import datetime as dt
import hashlib
import json
import pathlib
import sys

EXIT_VERIFIED = 0
EXIT_TAMPERED = 2
EXIT_UNVERIFIABLE = 3
EXIT_INPUT_ERROR = 4

GENESIS_HASH = "0" * 64


# ── canonical forms, re-implemented from the specification, not imported ──────────────────

def java_instant_to_string(instant: dt.datetime) -> str:
    """Reproduce java.time.Instant.toString() exactly.

    The chain hash covers `occurredAt.toString()` / `recordedAt.toString()`, so a Python
    rendering that differs by a single character rejects every honest row. Java prints the
    fraction in groups of 3, 6 or 9 digits and omits it entirely when zero -- it does NOT
    zero-pad to a fixed width the way isoformat() does.
    """
    instant = instant.astimezone(dt.timezone.utc)
    micros = instant.microsecond
    base = instant.strftime("%Y-%m-%dT%H:%M:%S")
    if micros == 0:
        return base + "Z"
    if micros % 1000 == 0:
        return f"{base}.{micros // 1000:03d}Z"
    return f"{base}.{micros:06d}Z"


def parse_instant(text: str) -> dt.datetime:
    """Parse an ISO-8601 instant, truncated to microseconds as TIMESTAMPTZ stores it."""
    cleaned = text.strip().replace("Z", "+00:00")
    parsed = dt.datetime.fromisoformat(cleaned)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def anchor_digest(last_entry_id, last_record_hash, chained_count, chain_status, signed_at) -> str:
    """AuditAnchor.digest -- SHA-256 over the pipe-joined attested fields."""
    canonical = "|".join([
        last_entry_id or "",
        last_record_hash or "",
        str(chained_count),
        chain_status,
        str(int(signed_at.timestamp() * 1000)),
    ])
    return sha256_hex(canonical)


def chain_hash(prev_hash: str, entry: dict) -> str:
    """AuditRepository.chainHash -- SHA-256 over prev_hash and the evidential fields."""
    canonical = "|".join([
        prev_hash,
        entry["entryId"],
        entry["eventType"],
        entry["aggregateType"],
        entry["aggregateId"],
        entry.get("actorId") or "",
        entry.get("actorType") or "",
        sha256_hex(entry["payload"]),
        entry["sourceService"],
        entry.get("correlationId") or "",
        java_instant_to_string(parse_instant(entry["occurredAt"])),
        java_instant_to_string(parse_instant(entry["recordedAt"])),
    ])
    return sha256_hex(canonical)


# ── signature verification (public key only) ──────────────────────────────────────────────

def verify_signature(public_key_pem: str, digest_hex: str, signature_b64: str):
    """True/False for a decidable answer, None when this key cannot speak to this signature.

    The service signs the ASCII digest string with KMS MessageType.RAW + ECDSA_SHA_256, so KMS
    hashes it; verification is therefore a plain ECDSA-over-SHA256 of those same ASCII bytes.
    """
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
        from cryptography.exceptions import InvalidSignature
    except ImportError:  # pragma: no cover - dependency is declared in the runbook
        return None
    try:
        key = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
        signature = base64.b64decode(signature_b64, validate=True)
    except Exception:
        return False
    message = digest_hex.encode("utf-8")
    try:
        if isinstance(key, ec.EllipticCurvePublicKey):
            key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
        elif isinstance(key, rsa.RSAPublicKey):
            key.verify(signature, message, padding.PKCS1v15(), hashes.SHA256())
        else:
            return None
        return True
    except InvalidSignature:
        return False
    except Exception:
        return False


# ── the verifier ──────────────────────────────────────────────────────────────────────────

class Findings:
    def __init__(self):
        self.rejections = []
        self.unverifiable = []
        self.verified = 0

    def reject(self, message):
        self.rejections.append(message)

    def cannot_verify(self, message):
        self.unverifiable.append(message)


def verify_anchor_bundle(anchors, public_key_pem, findings):
    """Recompute, signature-check and sequence-check every anchor."""
    ordered = sorted(anchors, key=lambda a: parse_instant(a["signedAt"]))
    previous = None
    for anchor in ordered:
        signed_at = parse_instant(anchor["signedAt"])
        label = f"anchor signedAt={anchor['signedAt']} count={anchor.get('chainedCount')}"

        recomputed = anchor_digest(
            anchor.get("lastEntryId"),
            anchor.get("lastRecordHash"),
            anchor["chainedCount"],
            anchor["chainStatus"],
            signed_at,
        )
        if recomputed != anchor["anchorDigest"]:
            findings.reject(
                f"ANCHOR DIGEST MISMATCH: {label} -- the stored anchor row was edited after "
                f"signing (stored {anchor['anchorDigest'][:16]}..., "
                f"recomputed {recomputed[:16]}...)"
            )
            previous = anchor
            continue

        if anchor.get("chainStatus") != "INTACT":
            findings.reject(
                f"ANCHOR ATTESTS A BROKEN CHAIN: {label} -- the producer itself recorded "
                f"chainStatus={anchor.get('chainStatus')} at capture time"
            )

        signature = anchor.get("signature")
        if not signature:
            findings.cannot_verify(
                f"UNSIGNED ANCHOR: {label} -- captured with no signature, so it attests nothing "
                f"a third party can check"
            )
        elif not public_key_pem:
            findings.cannot_verify(f"NO PUBLIC KEY SUPPLIED for keyId={anchor.get('keyId')}: {label}")
        else:
            outcome = verify_signature(public_key_pem, recomputed, signature)
            if outcome is False:
                findings.reject(
                    f"INVALID SIGNATURE: {label} keyId={anchor.get('keyId')} -- the signature "
                    f"does not verify under the supplied public key"
                )
            elif outcome is None:
                findings.cannot_verify(
                    f"KEY CANNOT VERIFY THIS ANCHOR: {label} keyId={anchor.get('keyId')} -- "
                    f"symmetric or unsupported key material (an HMAC anchor is not "
                    f"third-party verifiable by construction)"
                )
            else:
                findings.verified += 1

        if previous is not None:
            if anchor["chainedCount"] < previous["chainedCount"]:
                findings.reject(
                    f"ANCHOR SEQUENCE WENT BACKWARDS: {label} attests {anchor['chainedCount']} "
                    f"entries after an earlier anchor attested {previous['chainedCount']} -- the "
                    f"log was truncated or re-anchored over a shortened range"
                )
            if parse_instant(anchor["signedAt"]) == parse_instant(previous["signedAt"]) \
                    and anchor["anchorDigest"] != previous["anchorDigest"]:
                findings.reject(
                    f"TWO DIFFERENT ANCHORS AT THE SAME INSTANT: {label} -- a forked history"
                )
        previous = anchor
    return ordered


def verify_entries_against_anchors(entries, ordered_anchors, findings):
    """Recompute the record-hash chain and confirm it reproduces every attested head."""
    expected_prev = GENESIS_HASH
    positions = {}
    for index, entry in enumerate(entries, start=1):
        entry_id = entry.get("entryId")
        stored_prev = entry.get("prevHash")
        stored_record = entry.get("recordHash")
        if stored_record is None:
            findings.cannot_verify(
                f"UNCHAINED ENTRY at position {index} (entryId={entry_id}): written before the "
                f"V5 hash chain, so it cannot be verified retroactively"
            )
            continue
        if entry.get("hashVersion") is None:
            findings.cannot_verify(
                f"LEGACY HASH VERSION at position {index} (entryId={entry_id}): hashed in the "
                f"pre-#3586 canonical form whose digits the database truncated"
            )
            continue
        if stored_prev != expected_prev:
            findings.reject(
                f"CHAIN LINK BROKEN at position {index} (entryId={entry_id}): prevHash "
                f"{str(stored_prev)[:16]}... does not match the preceding record hash "
                f"{expected_prev[:16]}... -- an entry was DELETED, RE-ORDERED or INSERTED here"
            )
            expected_prev = stored_record
            positions[entry_id] = (index, stored_record)
            continue
        recomputed = chain_hash(stored_prev, entry)
        if recomputed != stored_record:
            findings.reject(
                f"ENTRY ALTERED at position {index} (entryId={entry_id}): recomputed record hash "
                f"{recomputed[:16]}... does not match the stored {stored_record[:16]}... -- the "
                f"row's evidential fields were modified after it was written"
            )
        expected_prev = stored_record
        positions[entry_id] = (index, stored_record)

    for anchor in ordered_anchors:
        attested_id = anchor.get("lastEntryId")
        if attested_id is None:
            continue
        label = f"anchor signedAt={anchor['signedAt']}"
        if attested_id not in positions:
            findings.reject(
                f"ATTESTED HEAD IS MISSING FROM THE LOG: {label} attests entryId={attested_id}, "
                f"which does not appear in the exported entries -- the attested entry was DELETED"
            )
            continue
        position, record_hash = positions[attested_id]
        if record_hash != anchor.get("lastRecordHash"):
            findings.reject(
                f"ATTESTED HEAD HASH MISMATCH: {label} attests lastRecordHash "
                f"{str(anchor.get('lastRecordHash'))[:16]}... but the log now holds "
                f"{record_hash[:16]}... for that entry -- the log was rewritten under the anchor"
            )
        if position != anchor.get("chainedCount"):
            findings.reject(
                f"CHAIN LENGTH GAP: {label} attests {anchor.get('chainedCount')} chained entries "
                f"up to entryId={attested_id}, but that entry now sits at position {position} -- "
                f"{anchor.get('chainedCount') - position} entries are MISSING from the log"
            )


def run(anchors, public_key_pem, entries):
    findings = Findings()
    if not anchors:
        findings.cannot_verify(
            "NO ANCHORS: the export contains no checkpoints at all. Nothing was attested, so "
            "nothing can be verified -- this is NOT the same result as a verified log."
        )
        return findings, EXIT_UNVERIFIABLE
    ordered = verify_anchor_bundle(anchors, public_key_pem, findings)
    if entries is not None:
        verify_entries_against_anchors(entries, ordered, findings)
    if findings.rejections:
        return findings, EXIT_TAMPERED
    if findings.unverifiable or findings.verified == 0:
        return findings, EXIT_UNVERIFIABLE
    return findings, EXIT_VERIFIED


def report(findings, code, anchors, entries):
    verdict = {
        EXIT_VERIFIED: "VERIFIED",
        EXIT_TAMPERED: "TAMPERED",
        EXIT_UNVERIFIABLE: "UNVERIFIABLE",
    }[code]
    print(f"verdict: {verdict}")
    print(f"anchors examined: {len(anchors)}  signature-verified: {findings.verified}")
    if entries is None:
        print("entries: NOT SUPPLIED -- the attested heads were not checked against a log. "
              "Signature validity alone does not establish that the log still matches.")
    else:
        print(f"entries examined: {len(entries)}")
    for message in findings.rejections:
        print(f"  REJECTED: {message}")
    for message in findings.unverifiable:
        print(f"  UNVERIFIABLE: {message}")
    if code == EXIT_VERIFIED:
        print("Every anchor recomputed to its stored digest and verified under the supplied "
              "public key. Note what this does NOT show: that an anchor exists for every period, "
              "and that signedAt is a trustworthy time (it is the producer's own claim).")


# ── self-test: the verifier is only worth what it REJECTS ─────────────────────────────────

def self_test():
    """Falsify the verifier: it must reject four distinct tamperings and accept a clean log.

    Every case is constructed here from scratch with a throwaway EC key, so the test needs no
    cluster, no database and no KMS. A verifier that could only ever answer VERIFIED would pass
    a test that only fed it good data -- so the good case is one of six, not the whole suite.
    """
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
    except ImportError:
        print("SELF-TEST CANNOT RUN: python 'cryptography' is not installed.", file=sys.stderr)
        return EXIT_INPUT_ERROR

    private_key = ec.generate_private_key(ec.SECP256R1())
    pem = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("utf-8")

    def make_entries(count):
        built = []
        prev = GENESIS_HASH
        for i in range(count):
            entry = {
                "entryId": f"00000000-0000-0000-0000-00000000000{i}",
                "eventType": "payment.initiated",
                "aggregateType": "Payment",
                "aggregateId": f"PMT-{i}",
                "actorId": "operator-1",
                "actorType": "HUMAN",
                "payload": json.dumps({"amount": 100 + i}),
                "sourceService": "openbank-payment-service",
                "correlationId": f"corr-{i}",
                "occurredAt": f"2026-08-21T10:0{i}:00Z",
                "recordedAt": f"2026-08-21T10:0{i}:01Z",
                "prevHash": prev,
                "hashVersion": 1,
            }
            entry["recordHash"] = chain_hash(prev, entry)
            prev = entry["recordHash"]
            built.append(entry)
        return built

    def make_anchor(entries):
        head = entries[-1]
        signed_at = parse_instant("2026-08-21T11:00:00Z")
        digest = anchor_digest(head["entryId"], head["recordHash"], len(entries), "INTACT", signed_at)
        signature = private_key.sign(digest.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return {
            "lastEntryId": head["entryId"],
            "lastRecordHash": head["recordHash"],
            "chainedCount": len(entries),
            "chainStatus": "INTACT",
            "anchorDigest": digest,
            "signature": base64.b64encode(signature).decode("ascii"),
            "keyId": "arn:aws:kms:eu-north-1:000000000000:key/self-test",
            "signedAt": "2026-08-21T11:00:00Z",
        }

    failures = []
    cases = 0

    def expect(name, entries, anchors, want_code, want_phrase):
        nonlocal cases
        cases += 1
        findings, code = run(anchors, pem, entries)
        messages = " | ".join(findings.rejections + findings.unverifiable)
        if code != want_code:
            failures.append(f"{name}: expected exit {want_code}, got {code} ({messages})")
        elif want_phrase and want_phrase not in messages:
            failures.append(f"{name}: expected a message naming '{want_phrase}', got: {messages}")
        else:
            print(f"  ok  {name}: exit {code}"
                  + (f" -- {want_phrase}" if want_phrase else " -- accepted"))

    # 1. the control: an untampered range must be ACCEPTED, or every rejection below is vacuous.
    clean = make_entries(4)
    expect("clean range is accepted", clean, [make_anchor(clean)], EXIT_VERIFIED, "")

    # 2. an ALTERED entry -- the classic in-place edit the DB rules silently allow to no-op.
    altered = json.loads(json.dumps(clean))
    anchor_for_altered = make_anchor(clean)
    altered[1]["payload"] = json.dumps({"amount": 999999})
    expect("altered entry is rejected", altered, [anchor_for_altered],
           EXIT_TAMPERED, "ENTRY ALTERED")

    # 3. a DELETED entry -- the count no longer reaches the attested head.
    deleted = json.loads(json.dumps(clean))
    del deleted[1]
    expect("deleted entry is rejected", deleted, [make_anchor(clean)],
           EXIT_TAMPERED, "CHAIN LINK BROKEN")

    # 4. a RE-ORDERED pair -- both rows are individually valid; only the links disagree.
    reordered = json.loads(json.dumps(clean))
    reordered[1], reordered[2] = reordered[2], reordered[1]
    expect("re-ordered pair is rejected", reordered, [make_anchor(clean)],
           EXIT_TAMPERED, "CHAIN LINK BROKEN")

    # 5. a GAP -- a self-consistent shorter log that an internal walk alone would call INTACT.
    truncated = make_entries(2)
    gap_anchor = make_anchor(clean)
    expect("gap in the chain is rejected", truncated, [gap_anchor],
           EXIT_TAMPERED, "ATTESTED HEAD IS MISSING FROM THE LOG")

    # 6. an EDITED ANCHOR ROW -- digest recomputation catches it before any signature check.
    edited = make_anchor(clean)
    edited["chainedCount"] = 99
    expect("edited anchor row is rejected", clean, [edited],
           EXIT_TAMPERED, "ANCHOR DIGEST MISMATCH")

    # 7. a FORGED SIGNATURE under a foreign key.
    forged = make_anchor(clean)
    other = ec.generate_private_key(ec.SECP256R1())
    forged["signature"] = base64.b64encode(
        other.sign(forged["anchorDigest"].encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    ).decode("ascii")
    expect("foreign-key signature is rejected", clean, [forged],
           EXIT_TAMPERED, "INVALID SIGNATURE")

    # 8. NO ANCHORS must be its own outcome, never a pass (the PushResult.skipped() lesson).
    expect("absent anchors are UNVERIFIABLE, not verified", clean, [],
           EXIT_UNVERIFIABLE, "NO ANCHORS")

    # 9. an UNSIGNED anchor is likewise not a verification.
    unsigned = make_anchor(clean)
    unsigned["signature"] = None
    expect("unsigned anchor is UNVERIFIABLE", clean, [unsigned],
           EXIT_UNVERIFIABLE, "UNSIGNED ANCHOR")

    # 10. an anchor that went BACKWARDS -- a re-anchor over a shortened range.
    long_anchor = make_anchor(clean)
    short = make_entries(2)
    short_anchor = make_anchor(short)
    short_anchor["signedAt"] = "2026-08-21T12:00:00Z"
    signed_at = parse_instant(short_anchor["signedAt"])
    short_anchor["anchorDigest"] = anchor_digest(
        short_anchor["lastEntryId"], short_anchor["lastRecordHash"], 2, "INTACT", signed_at)
    short_anchor["signature"] = base64.b64encode(
        private_key.sign(short_anchor["anchorDigest"].encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    ).decode("ascii")
    expect("backwards re-anchor is rejected", None, [long_anchor, short_anchor],
           EXIT_TAMPERED, "ANCHOR SEQUENCE WENT BACKWARDS")

    if failures:
        print("\nSELF-TEST FAILED:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    # The runner checks this against gates.yaml `min_subjects`, so a suite that silently
    # stopped constructing cases cannot read as a pass on the strength of the ones left.
    print(f"SUBJECTS={cases}")
    print(f"\nself-test: {cases}/{cases} cases behaved as specified "
          "(1 acceptance, 7 rejections, 2 distinct UNVERIFIABLE outcomes)")
    return 0


def load_json(path, what):
    try:
        return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"cannot read {what} from {path}: {exc}", file=sys.stderr)
        sys.exit(EXIT_INPUT_ERROR)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--anchors", help="anchor export from GET /api/v1/audit/anchors")
    parser.add_argument("--public-key", help="PEM public key (KMS GetPublicKey)")
    parser.add_argument("--entries", help="optional audit-entry export, to check attested heads")
    parser.add_argument("--self-test", action="store_true", help="falsify this verifier")
    args = parser.parse_args()

    if args.self_test:
        sys.exit(self_test())
    if not args.anchors:
        print("--anchors is required (or use --self-test)", file=sys.stderr)
        sys.exit(EXIT_INPUT_ERROR)

    anchors = load_json(args.anchors, "anchor export")
    if isinstance(anchors, dict):
        anchors = anchors.get("anchors", [])
    if not isinstance(anchors, list):
        print("anchor export must be a JSON array of anchors", file=sys.stderr)
        sys.exit(EXIT_INPUT_ERROR)

    entries = None
    if args.entries:
        entries = load_json(args.entries, "entry export")
        if isinstance(entries, dict):
            entries = entries.get("entries", [])
        if not isinstance(entries, list):
            print("entry export must be a JSON array of entries", file=sys.stderr)
            sys.exit(EXIT_INPUT_ERROR)

    public_key_pem = None
    if args.public_key:
        try:
            public_key_pem = pathlib.Path(args.public_key).read_text(encoding="utf-8")
        except Exception as exc:
            print(f"cannot read public key from {args.public_key}: {exc}", file=sys.stderr)
            sys.exit(EXIT_INPUT_ERROR)

    findings, code = run(anchors, public_key_pem, entries)
    report(findings, code, anchors, entries)
    sys.exit(code)


if __name__ == "__main__":
    main()
