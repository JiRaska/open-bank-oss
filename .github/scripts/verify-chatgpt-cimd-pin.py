#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.
"""Check the public document and key for the pre-registered ChatGPT OAuth client."""

import argparse
import hashlib
import http.client
import json
import ssl
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "openbank-mcp-service/src/main/resources"
CLIENT_ID = "https://chatgpt.com/oauth/client.json"
CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect"
JWKS_URI = "https://chatgpt.com/oauth/jwks.json"
MAX_BYTES = 8192


def unique_fields(items):
    fields = {}
    for name, value in items:
        if name in fields:
            raise ValueError("Duplicate JSON field")
        fields[name] = value
    return fields


def parse_document(data):
    if len(data) > MAX_BYTES:
        raise ValueError("Public metadata exceeds size limit")
    document = json.loads(data, object_pairs_hook=unique_fields)
    if not isinstance(document, dict):
        raise ValueError("Public metadata must be a JSON object")
    return document


def fetch(path):
    connection = http.client.HTTPSConnection("chatgpt.com", timeout=5, context=ssl.create_default_context())
    try:
        connection.request("GET", path, headers={"Accept": "application/json"})
        response = connection.getresponse()
        if response.status != 200 or response.headers.get_content_type() != "application/json":
            raise ValueError("Official metadata did not return 200 application/json")
        return response.read(MAX_BYTES + 1)
    finally:
        connection.close()


def validate(metadata, keys, registration, pinned_bytes):
    auth = registration["auth"]
    if (registration["client_id"] != CLIENT_ID or registration["tier"] != "H"
            or registration["redirect_uris"] != [CALLBACK] or auth["method"] != "private_key_jwt"):
        raise ValueError("Committed ChatGPT registration differs")
    if (metadata.get("client_id") != CLIENT_ID or metadata.get("redirect_uris") != [CALLBACK]
            or metadata.get("jwks_uri") != JWKS_URI):
        raise ValueError("Official client ID, callback or published key URL differs")
    methods = metadata.get("token_endpoint_auth_methods_supported", [metadata.get("token_endpoint_auth_method")])
    grants = metadata.get("grant_types")
    if (not isinstance(methods, list) or "private_key_jwt" not in methods
            or metadata.get("token_endpoint_auth_signing_alg") != "RS256"
            or metadata.get("response_types") != ["code"] or not isinstance(grants, list)
            or not all(grant in grants for grant in ("authorization_code", "refresh_token"))):
        raise ValueError("Official authorization-code or signed assertion metadata differs")
    if hashlib.sha256(pinned_bytes).hexdigest() != auth["jwks_sha256"]:
        raise ValueError("Committed JWKS digest differs")
    if keys != parse_document(pinned_bytes):
        raise ValueError("Official JWKS differs from reviewed key set")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, help="previously retrieved public CIMD document")
    parser.add_argument("--jwks", type=Path, help="previously retrieved public JWKS document")
    args = parser.parse_args()
    if (args.metadata is None) != (args.jwks is None):
        parser.error("supply both offline documents or neither")
    allowlist = yaml.safe_load((RESOURCES / "mcp-client-allowlist.yaml").read_text())
    registration, = [entry for entry in allowlist["clients"] if entry["client_id"] == CLIENT_ID]
    pinned = (RESOURCES / "mcp-clients/chatgpt.jwks.json").read_bytes()
    try:
        metadata = parse_document(args.metadata.read_bytes() if args.metadata else fetch("/oauth/client.json"))
        keys = parse_document(args.jwks.read_bytes() if args.jwks else fetch("/oauth/jwks.json"))
        validate(metadata, keys, registration, pinned)
    except (ValueError, OSError, http.client.HTTPException) as error:
        parser.exit(1, f"ChatGPT CIMD pin check failed: {error}\n")
    inline = json.dumps(parse_document(pinned), separators=(",", ":")).encode()
    print("ChatGPT public client document and reviewed JWKS match")
    print("MCP_CHATGPT_CIMD_STATIC_JWKS_SHA256=" + hashlib.sha256(inline).hexdigest())


if __name__ == "__main__":
    main()
