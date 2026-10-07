#!/usr/bin/env python3
"""Guard the one-off product-catalog replay of an exact admin-ui Pact (#12196)."""

import base64
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

PROVIDER = "openbank-product-catalog"
CONSUMER = "openbank-admin-ui"
VERSION = "c5470b871282135b61e77a23408f35fdaf3f28d4"
TEST_CLASS = "ProductCatalogPactBrokerProviderVerificationTest"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError("exact historical Pact fetch redirected; refusing to forward credentials")


def validate(env):
    if env.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or env.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("historical replay requires a manual dispatch on main")
    if env.get("SERVICE") != PROVIDER or env.get("REQUESTED_REF") or env.get("REQUESTED_PROVIDER_VERSION"):
        raise ValueError("historical replay requires product-catalog at the triggering main SHA")
    version = env.get("HISTORICAL_ADMIN_UI_VERSION", "")
    if not re.fullmatch(r"[0-9a-f]{40}", version) or version != VERSION:
        raise ValueError("historical replay requires the exact full admin-ui SHA for #12196")
    broker = env.get("PACT_BROKER_URL", "").rstrip("/")
    if not broker.startswith("https://") and not broker.startswith("http://"):
        raise ValueError("Pact Broker URL is unavailable")
    return f"{broker}/pacts/provider/{PROVIDER}/consumer/{CONSUMER}/version/{version}"


def configure(env):
    url = validate(env)
    with open(env["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write(f"HISTORICAL_PACT_URL={url}\n")
    print("Historical admin-ui Pact request validated for product-catalog.")


def preflight(env):
    url = env["HISTORICAL_PACT_URL"]
    username, password = env.get("PACT_BROKER_USERNAME", ""), env.get("PACT_BROKER_PASSWORD", "")
    if not username or not password:
        raise ValueError("Pact Broker credentials are unavailable")
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    request = urllib.request.Request(url, headers={"Authorization": f"Basic {token}", "Accept": "application/json"})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            pact = json.load(response)
    except urllib.error.HTTPError as error:
        raise ValueError(f"exact historical Pact fetch failed (HTTP {error.code})") from None
    except urllib.error.URLError:
        raise ValueError("exact historical Pact fetch failed (transport error)") from None
    if pact.get("consumer", {}).get("name") != CONSUMER or pact.get("provider", {}).get("name") != PROVIDER:
        raise ValueError("historical Pact has unexpected consumer/provider names")
    if not pact.get("interactions"):
        raise ValueError("historical Pact has no interactions")
    print("Exact historical Pact exists and has interactions; broker response remains private.")


def check_result():
    reports = Path(PROVIDER, "build/reports/tests/providerPactTest")
    xml_dir = Path(PROVIDER, "build/test-results/providerPactTest")
    files = list(xml_dir.glob(f"*{TEST_CLASS}*.xml"))
    total = 0
    skipped_or_failed = 0
    for file in files:
        root = ET.parse(file).getroot()
        for case in root.iter("testcase"):
            if TEST_CLASS in case.get("classname", ""):
                if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                    skipped_or_failed += 1
                else:
                    total += 1
    if total == 0 or skipped_or_failed:
        raise ValueError(f"historical broker verifier requires successful interactions and zero skips/failures (reports: {reports})")
    print(f"Historical broker verifier completed {total} interaction(s).")


if __name__ == "__main__":
    try:
        command = sys.argv[1]
        if command == "configure":
            configure(os.environ)
        elif command == "preflight":
            preflight(os.environ)
        elif command == "check-result":
            check_result()
        else:
            raise ValueError("unsupported command")
    except (IndexError, ValueError, OSError, json.JSONDecodeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
