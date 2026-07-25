#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# AP2 mandate verification — sandbox demo (ADR-0193, issue #1923).
#
# Demonstrates the bank-side AP2 flow end to end WITHOUT moving funds: an agent presents a
# signed Payment Mandate, openbank-ap2-service verifies its Ed25519 signature chain + its
# constraints (payee / amount cap / currency / expiry) against a presented payment, and a
# human-in-the-loop threshold decides auto-eligible vs step-up. Nothing here debits an
# account — the verifier returns AUTHORIZATION EVIDENCE only (ADR-0193): a valid verdict is
# an input to the SCA/payment decision, never a payment.
#
# The demo issuer key is derived DETERMINISTICALLY from a fixed, human-readable 32-byte seed
# (DEMO_SEED below) — no private-key file is committed, and the seed is a label, not a secret.
# Because it is deterministic the public key is fixed, so it can be trust-listed on the sandbox
# ap2-service once (AP2_TRUST_LIST="demo-issuer=<DEMO_SPKI_B64>"); see README. It is NOT a
# production key and authorizes nothing real.
#
# Usage (point at the live sandbox service; port-forward in another shell):
#   kubectl port-forward -n platform svc/ap2-service 8151:8151
#   AP2_URL=http://localhost:8151 python3 ap2-mandate-demo.py
#   python3 ap2-mandate-demo.py --print-trust     # emit the AP2_TRUST_LIST value to configure
#
#   HITL threshold (minor units) is configurable:  HITL_THRESHOLD_MINOR=50000 python3 ap2-mandate-demo.py
#
# Requires: cryptography (pip install cryptography).
import base64, json, os, sys, urllib.request, urllib.error
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

DEMO_SEED = b"openbank-ap2-demo-issuer-seed-01"   # exactly 32 bytes, a label — NOT a secret
KEY = Ed25519PrivateKey.from_private_bytes(DEMO_SEED)
DEMO_SPKI_B64 = base64.b64encode(KEY.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()
AP2_URL = os.environ.get("AP2_URL", "http://localhost:8151").rstrip("/")
HITL_THRESHOLD_MINOR = int(os.environ.get("HITL_THRESHOLD_MINOR", "50000"))  # >500.00 CZK needs a human

ISSUER, PAYEE, CURRENCY = "demo-issuer", "CZ6508000000192000145399", "CZK"
CAP_MINOR = 100_000            # mandate authorises up to 1000.00 CZK
EXPIRES = "2027-12-31T00:00:00Z"


def b64url(obj: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj, separators=(",", ":")).encode()).rstrip(b"=").decode()


def signing_input(cap_minor: int) -> str:
    # a JWS-style base64url(header).base64url(payload) — the exact string the service hashes + verifies
    header = b64url({"alg": "EdDSA", "typ": "vc+jwt"})
    payload = b64url({"kind": "PAYMENT", "iss": ISSUER, "sub": "cust-1", "payee": PAYEE,
                      "amountCapMinor": cap_minor, "currency": CURRENCY, "exp": EXPIRES})
    return f"{header}.{payload}"


def sign(si: str) -> str:
    return base64.b64encode(KEY.sign(si.encode())).decode()


def verify(si: str, sig: str, amount_minor: int) -> dict | None:
    body = {
        "mandate": {"kind": "PAYMENT", "issuer": ISSUER, "subject": "cust-1",
                    "constraints": {"payee": PAYEE, "amountCapMinor": CAP_MINOR, "currency": CURRENCY, "expiresAt": EXPIRES},
                    "signingInput": si, "signatureB64": sig, "algorithm": "ED25519"},
        "payment": {"payee": PAYEE, "amountMinor": amount_minor, "currency": CURRENCY, "at": "2026-06-01T00:00:00Z"},
    }
    req = urllib.request.Request(f"{AP2_URL}/ap2/verify", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "X-Agent-Id": "agent:ap2-anonymous"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            v = json.load(r)
            print(f"  HTTP {r.status}  valid={v.get('valid')}  failures={v.get('failures')}")
            return v
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}  {e.read().decode()}")
        if e.code == 503:
            print("  (503 = no OPA sidecar yet: the ADR-0034 PDP is unreachable, so verify fails CLOSED)")
        return None


def hitl(amount_minor: int) -> None:
    if amount_minor > HITL_THRESHOLD_MINOR:
        print(f"  HITL: {amount_minor} > threshold {HITL_THRESHOLD_MINOR} → route to a HUMAN (step-up). No auto-execution.")
    else:
        print(f"  HITL: {amount_minor} ≤ threshold {HITL_THRESHOLD_MINOR} → auto-eligible (still evidence-only under ADR-0193).")


def main() -> None:
    if "--print-trust" in sys.argv:
        print(f"demo-issuer={DEMO_SPKI_B64}")
        return
    print(f"== AP2 mandate demo → {AP2_URL}  (issuer={ISSUER}, cap={CAP_MINOR} {CURRENCY}, HITL>{HITL_THRESHOLD_MINOR})\n")
    si = signing_input(CAP_MINOR)
    sig = sign(si)

    print("[1] valid, in-bounds payment (250.00 CZK) — expect valid=true, auto-eligible")
    verify(si, sig, 25_000); hitl(25_000); print()

    print("[2] valid mandate, LARGE payment (750.00 CZK, within cap) — expect valid=true, HITL step-up")
    verify(si, sig, 75_000); hitl(75_000); print()

    print("[3] payment OVER the mandate cap (1500.00 CZK) — expect valid=false (amount exceeds cap)")
    verify(si, sig, 150_000); print()

    print("[4] TAMPERED signature — expect valid=false (signature invalid)")
    verify(si, sign(signing_input(999_999)), 25_000); print()

    print("A valid verdict is authorization EVIDENCE, not a payment — no funds moved (ADR-0193).")


if __name__ == "__main__":
    main()
