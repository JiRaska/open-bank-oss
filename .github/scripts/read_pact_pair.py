# SPDX-License-Identifier: Apache-2.0
"""Read the Pact Broker matrix for two explicitly pinned versions."""
import argparse
import base64
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

MAX_BYTES = 262_144

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def matrix_url(broker, consumer, consumer_version, provider, provider_version):
    parsed = urllib.parse.urlsplit(broker)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise ValueError("invalid broker endpoint")
    if consumer == provider or not all(re.fullmatch(r"openbank-[a-z0-9-]+", x) for x in (consumer, provider)):
        raise ValueError("invalid participant names")
    if not all(re.fullmatch(r"[a-f0-9]{40}", x) for x in (consumer_version, provider_version)):
        raise ValueError("both versions must be full commit hashes")
    query = urllib.parse.urlencode([
        ("q[][pacticipant]", consumer), ("q[][version]", consumer_version),
        ("q[][pacticipant]", provider), ("q[][version]", provider_version),
        ("latestby", "cvpv"),
    ])
    return broker.rstrip("/") + "/matrix?" + query

def has_evidence_link(entry):
    href = entry["_links"]["self"]["href"]
    return isinstance(href, str) and bool(href.strip())

def verdict(body, consumer, consumer_version, provider, provider_version):
    try:
        rows = body["matrix"]
        if not isinstance(rows, list) or not rows:
            return None
        exact = (consumer, consumer_version, provider, provider_version)
        results = []
        for row in rows:
            if row.get("ignored", False) is not False:
                return None
            left, right = row["consumer"], row["provider"]
            identity = (left["name"], left["version"]["number"], right["name"], right["version"]["number"])
            if identity != exact:
                return None
            if not has_evidence_link(row["pact"]):
                return None
            verification = row["verificationResult"]
            if type(verification["success"]) is not bool or not has_evidence_link(verification):
                return None
            results.append(verification["success"])
        if not all(results):
            return False
        return True if body["summary"]["deployable"] is True else None
    except (AttributeError, KeyError, TypeError):
        return None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("consumer", "consumer_version", "provider", "provider_version"):
        parser.add_argument(field)
    args = parser.parse_args()
    pins = (args.consumer, args.consumer_version, args.provider, args.provider_version)
    try:
        url = matrix_url(os.environ["PACT_BROKER_URL"], *pins)
        user, password = (os.environ.get(f"PACT_BROKER_{key}", "") for key in ("USERNAME", "PASSWORD"))
        if not user or not password:
            raise ValueError("authenticated broker access is required")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        req = urllib.request.Request(url, headers={"Accept": "application/hal+json", "Authorization": f"Basic {token}"})
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=12) as response:
            raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("matrix response exceeds byte limit")
        result = verdict(json.loads(raw), *pins)
    except urllib.error.HTTPError as error:
        print(f"PACT_PAIR_UNPROVEN (HTTP {error.code})")
        return 2
    except (KeyError, OSError, ValueError):
        print("PACT_PAIR_UNPROVEN")
        return 2
    if result is None:
        print("PACT_PAIR_UNPROVEN")
        return 2
    if not result:
        print("PACT_PAIR_FAILED")
        return 1
    print("PACT_PAIR_VERIFIED")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
