#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Transfer regenerated admin UI contracts to the trusted main publication job."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

CONSUMER = "openbank-admin-ui"
PROVIDER = re.compile(r"openbank-[a-z0-9]+(?:-[a-z0-9]+)*\Z")


def source(root, sha):
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if actual != sha:
        raise ValueError("Checkout does not match the publication revision")
    paths = subprocess.check_output(
        ["git", "ls-files", "--", f"pacts/{CONSUMER}-*.json"], cwd=root, text=True,
    ).splitlines()
    if not paths:
        raise ValueError("No tracked admin UI contracts")
    result = {}
    for name in paths:
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Contract must be a regular file")
        content = path.read_bytes()
        committed = subprocess.check_output(["git", "show", f"HEAD:{name}"], cwd=root)
        if content != committed:
            raise ValueError("Contract differs from the verified source revision")
        pact = json.loads(content)
        provider = pact.get("provider", {}).get("name")
        if pact.get("consumer", {}).get("name") != CONSUMER or not isinstance(provider, str) or not PROVIDER.fullmatch(provider):
            raise ValueError("Invalid contract participant identity")
        if path.name != f"{CONSUMER}-{provider}.json" or not pact.get("interactions"):
            raise ValueError("Contract filename or interactions do not match its identity")
        result[name] = content
    return result


def prepare(root, sha, marker):
    files = source(root, sha)
    cutoff = marker.stat().st_mtime_ns
    if any((root / name).stat().st_mtime_ns <= cutoff for name in files):
        raise ValueError("Every contract must be regenerated after the generation marker")
    return {"sourceSha": sha, "files": {
        name: {"sha256": hashlib.sha256(content).hexdigest(),
               "content": base64.b64encode(content).decode("ascii")}
        for name, content in files.items()
    }}


def payload(root, sha, bundle, build_url):
    files = source(root, sha)
    if bundle.get("sourceSha") != sha or set(bundle.get("files", {})) != set(files):
        raise ValueError("Artifact revision or complete contract set does not match checkout")
    contracts = []
    for name, expected in files.items():
        item = bundle["files"][name]
        content = base64.b64decode(item["content"], validate=True)
        if content != expected or item["sha256"] != hashlib.sha256(content).hexdigest():
            raise ValueError("Artifact content does not match the verified source contract")
        pact = json.loads(content)
        contracts.append({"consumerName": CONSUMER, "providerName": pact["provider"]["name"],
                          "specification": "pact", "contentType": "application/json",
                          "content": item["content"]})
    return {"pacticipantName": CONSUMER, "pacticipantVersionNumber": sha,
            "branch": "main", "tags": ["main"], "buildUrl": build_url, "contracts": contracts}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def publish(body, env):
    if env.get("GITHUB_EVENT_NAME") != "push" or env.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Publication requires a trusted main push")
    url, username, password = (env.get(key, "") for key in
                               ("PACT_BROKER_URL", "PACT_BROKER_USERNAME", "PACT_BROKER_PASSWORD"))
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or not username or not password:
        raise ValueError("HTTPS broker URL and credentials are required")
    auth = base64.b64encode(f"{username}:{password}".encode()).decode()
    opener = urllib.request.build_opener(NoRedirect())
    headers = {"Authorization": "Basic " + auth, "Accept": "application/json"}
    request = urllib.request.Request(
        url.rstrip("/") + "/contracts/publish", data=json.dumps(body).encode(),
        headers={**headers, "Content-Type": "application/json"}, method="POST",
    )
    # Do not forward credentials across redirects or print broker responses containing private data.
    with opener.open(request, timeout=60) as response:
        if not 200 <= response.status < 300:
            raise ValueError("Broker did not accept the publication")
    # The publish endpoint's 2xx only acknowledges a write. Read the exact consumer version
    # back for every provider before downstream verification is allowed to start.
    for contract in body["contracts"]:
        provider = contract["providerName"]
        path = ("/pacts/provider/" + urllib.parse.quote(provider, safe="") +
                "/consumer/" + CONSUMER + "/version/" +
                urllib.parse.quote(body["pacticipantVersionNumber"], safe=""))
        request = urllib.request.Request(url.rstrip("/") + path, headers=headers, method="GET")
        with opener.open(request, timeout=60) as response:
            if not 200 <= response.status < 300:
                raise ValueError("Published contract is not readable")
            received = json.load(response)
        expected = json.loads(base64.b64decode(contract["content"], validate=True))
        # Broker HAL links are transport metadata, not part of the generated contract.
        if not isinstance(received, dict):
            raise ValueError("Published contract has invalid content")
        received.pop("_links", None)
        if received != expected:
            raise ValueError("Published contract differs from the generated contract")
    return sorted(contract["providerName"] for contract in body["contracts"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "publish"))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--marker", type=Path)
    parser.add_argument("--providers-output", type=Path)
    args = parser.parse_args()
    sha = os.environ.get("GITHUB_SHA", "")
    try:
        if args.mode == "prepare":
            if args.marker is None:
                raise ValueError("Regeneration marker is required")
            args.bundle.write_text(json.dumps(prepare(args.root, sha, args.marker)) + "\n")
        else:
            build_url = (f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}"
                         f"/actions/runs/{os.environ['GITHUB_RUN_ID']}")
            body = payload(args.root, sha, json.loads(args.bundle.read_text()), build_url)
            providers = publish(body, os.environ)
            if args.providers_output is not None:
                args.providers_output.write_text(json.dumps(providers) + "\n")
            print(f"Published {len(body['contracts'])} admin UI contracts at {sha}")
    except urllib.error.HTTPError as error:
        parser.exit(1, f"Pact publication rejected: HTTP {error.code}\n")
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        # Do not render network exceptions: they may contain the broker URL.
        parser.exit(1, f"Pact publication failed ({type(error).__name__}); no success recorded\n")


if __name__ == "__main__":
    main()
