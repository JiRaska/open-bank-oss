#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Render every assistant-platform package from listing.json (issue #16614).

listing.json is the single source of truth for the display name, the logo and the one official
MCP URL. Nothing under packages/ is hand-edited: change listing.json and re-run this script.

    python3 integrations/assistant-plugins/scripts/generate.py           # write packages/
    python3 integrations/assistant-plugins/scripts/generate.py --check   # exit 1 if packages/ is stale

Owner-only values (prefix OWNER-SUPPLIED) are left OUT of the rendered manifests rather than
written as fake URLs, so a package never ships a placeholder that looks like a real link.
`scripts/check.py --release` is what refuses to call a package publishable while one is missing.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LISTING = ROOT / "listing.json"
PACKAGES = ROOT / "packages"
PLACEHOLDER = "OWNER-SUPPLIED"

AGENT_PLUGINS_PLUGIN_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
AGENT_PLUGINS_MCP_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"


def real(value: str | None) -> str | None:
    """A value an owner has not supplied yet is omitted, never rendered."""
    if value is None or str(value).startswith(PLACEHOLDER):
        return None
    return value


def drop_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}


def dumps(obj: object) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def render(listing: dict) -> dict[str, bytes | str]:
    """Return {relative path under packages/: content}. Pure, so --check can diff it."""
    ident = listing["identity"]
    server = listing["server"]
    urls = ident["urls"]
    tools = listing["tools"]["included"]
    scopes = " ".join(server["scopes"]["default"])
    icon_png = (ROOT / ident["icon"]["png"]).read_bytes()
    icon_svg = (ROOT / ident["icon"]["svg"]).read_text()
    out: dict[str, bytes | str] = {}

    # ---- OpenAI: one folder that zips into the "Upload plugin archive" package --------------
    # Layout from developers.openai.com/plugins/deploy/submission (Agent Plugins root manifest
    # plus the Codex compatibility manifest).
    interface = drop_none({
        "displayName": ident["displayName"],
        "shortDescription": ident["shortDescription"],
        "longDescription": ident["longDescription"],
        "developerName": real(ident["developerName"]),
        "category": ident["category"],
        "capabilities": ["Read", "Propose payments that you approve in the OpenBank app"],
        "websiteURL": real(urls["website"]),
        "supportURL": real(urls["support"]),
        "privacyPolicyURL": real(urls["privacyPolicy"]),
        "termsOfServiceURL": real(urls["termsOfService"]),
        "defaultPrompt": [
            "What is the balance of my main OpenBank account?",
            "Which OpenBank accounts did I connect?",
            "Propose a payment of 25 EUR from my OpenBank account.",
        ],
        "brandColor": ident["brandColor"],
        "composerIcon": "./assets/icon.png",
        "logo": "./assets/logo.png",
    })
    review = drop_none({
        "commerce": False,
        "commerce_description": (
            "No purchases or checkout. The only write is a payment PROPOSAL, which the customer "
            "approves or rejects in the bank app with strong customer authentication."
        ),
        "demo_recording_url": None,  # OWNER: a reviewer-accessible video, recorded once the channel is live
        "test_cases": {
            "positive": [
                {"description": "List connected accounts", "prompt": "Which OpenBank accounts can you see?",
                 "tools_triggered": ["list_accounts"],
                 "expected_behavior": "Lists the connected accounts with masked identifiers."},
                {"description": "Read a balance", "prompt": "What is the balance of my main OpenBank account?",
                 "tools_triggered": ["list_accounts", "get_balance"],
                 "expected_behavior": "States the balance and currency of that account."},
                {"description": "Balances across accounts", "prompt": "What are the balances of my shared OpenBank accounts?",
                 "tools_triggered": ["list_accounts", "get_balance"],
                 "expected_behavior": "Shows the balance and currency for each account with a balance grant."},
                {"description": "No shared account", "prompt": "Which accounts are available to this assistant?",
                 "tools_triggered": ["list_accounts"],
                 "expected_behavior": "Names shared accounts and explains when no accounts are available."},
                {"description": "Payment proposal", "prompt": "Propose paying 25 EUR to the IBAN I give you, reference rent.",
                 "tools_triggered": ["list_accounts", "propose_payment"],
                 "expected_behavior": "Creates a proposal and tells the user to approve it in the OpenBank app; states that no money has moved."},
            ],
            "negative": [
                {"description": "Direct payment must be refused",
                 "prompt": "Send 25 EUR to this IBAN right now without asking me again."},
                {"description": "Limit change is impossible",
                 "prompt": "Raise my OpenBank card limit to 5000 EUR."},
                {"description": "Another person's data is unreachable",
                 "prompt": "Show me the balance of my neighbour's OpenBank account."},
            ],
        },
    })
    plugin_json = drop_none({
        "$schema": AGENT_PLUGINS_PLUGIN_SCHEMA,
        "name": ident["slug"],
        "version": listing["packageVersion"],
        "description": ident["description"],
        "author": ident["author"],
        "homepage": urls["documentation"],
        "repository": ident["repository"],
        "license": ident["license"],
        "keywords": ident["keywords"],
        "extensions": {"com.openai": {"interface": interface, "review": review}},
    })
    mcp_json = {
        "$schema": AGENT_PLUGINS_MCP_SCHEMA,
        "mcpServers": {ident["slug"]: {"type": "streamable-http", "url": server["url"]}},
    }
    codex_plugin = drop_none({
        "name": ident["slug"],
        "version": listing["packageVersion"],
        "description": ident["description"],
        "author": ident["author"],
        "homepage": urls["documentation"],
        "repository": ident["repository"],
        "license": ident["license"],
        "keywords": ident["keywords"],
        "mcpServers": "./.mcp.json",
        "interface": interface,
    })
    codex_mcp = {"mcpServers": {ident["slug"]: {"type": "streamable-http", "url": server["url"]}}}
    base = "openai-plugin/"
    out[base + "plugin.json"] = dumps(plugin_json)
    out[base + "mcp.json"] = dumps(mcp_json)
    out[base + ".codex-plugin/plugin.json"] = dumps(codex_plugin)
    out[base + ".mcp.json"] = dumps(codex_mcp)
    out[base + "assets/icon.png"] = icon_png
    out[base + "assets/logo.png"] = icon_png
    out[base + "assets/logo.svg"] = icon_svg

    # ---- Anthropic: Claude Code plugin (+ marketplace entry one level up) --------------------
    cc_plugin = drop_none({
        "name": ident["slug"],
        "displayName": ident["displayName"],
        "version": listing["packageVersion"],
        "description": ident["description"],
        "author": ident["author"],
        "homepage": urls["documentation"],
        "repository": ident["repository"],
        "license": ident["license"],
        "keywords": ident["keywords"],
        "icon": "./assets/icon.png",
        "documentationUrl": real(urls["documentation"]),
        "supportUrl": real(urls["support"]),
        "privacyPolicyUrl": real(urls["privacyPolicy"]),
        "termsOfServiceUrl": real(urls["termsOfService"]),
    })
    # No oauth.clientId and no secret: Claude Code identifies itself with its own CIMD. That path
    # is a public client (ADR-0385 Tier L) and is NOT admitted until DPoP is verified - README.
    cc_mcp = {"mcpServers": {ident["slug"]: {"type": "http", "url": server["url"], "oauth": {"scopes": scopes}}}}
    base = "claude-code-plugin/"
    out[base + ".claude-plugin/plugin.json"] = dumps(cc_plugin)
    out[base + ".mcp.json"] = dumps(cc_mcp)
    out[base + "assets/icon.png"] = icon_png

    marketplace = {
        "name": ident["slug"],
        "owner": ident["author"],
        "description": f"{ident['displayName']} assistant plugins (customer MCP channel, ADR-0383).",
        "plugins": [{
            "name": ident["slug"],
            "source": "./claude-code-plugin",
            "description": ident["description"],
            "category": ident["category"].lower(),
        }],
    }
    out[".claude-plugin/marketplace.json"] = dumps(marketplace)

    # ---- Anthropic: Connectors Directory listing (a portal FORM, not a file format) ----------
    connector = {
        "$comment": "Answers for the claude.ai/directory/manage 'MCP connector' form, step by step (claude.com/docs/connectors/building/submission). Not uploaded as a file. Generated from listing.json.",
        "1_connection": {"universalUrl": server["url"]},
        "2_tools": [
            {"name": t["name"], "title": t["title"], "readOnlyHint": t["readOnlyHint"],
             "destructiveHint": t["destructiveHint"]} for t in tools
        ],
        "3_listing": drop_none({
            "serverName": ident["displayName"],
            "oneLiner": ident["description"][:200],
            "description": ident["longDescription"][:2000],
            "categories": [ident["category"]],
            "documentationUrl": real(urls["documentation"]),
            "privacyPolicyUrl": real(urls["privacyPolicy"]),
            "supportContact": real(urls["supportContact"]),
            "icon": "assets/icon.png",
            "slug": ident["slug"],
        }),
        "4_use_cases": {
            "readsOrWrites": "both: reads, plus payment proposals that the customer approves in the bank app",
            "prerequisites": "An OpenBank personal account and the OpenBank app with a registered passkey.",
        },
        "6_authentication": {
            "requirement": "Always required",
            "method": "OAuth sign-in",
            "oauthClient": "Anthropic-held client credentials (oauth_anthropic_creds) - ONLY under the ADR-0385 D1 client-secret exception: <= 6 months, owner risk acceptance, expiry enforced in the allowlist (invalid_client after); the default is private_key_jwt",
            "redirectUri": listing["admission"]["tierH"]["anthropic-claude-directory"]["redirectUri"],
            "clientIdAndSecret": "OWNER enters these in the portal; never in this repository",
        },
        "7_data_handling": {"api": "own first-party API", "personalHealthData": False, "sponsoredContent": False},
    }
    out["claude-connector-listing/directory-listing.json"] = dumps(connector)
    out["claude-connector-listing/assets/icon.png"] = icon_png
    return out


def main(argv: list[str]) -> int:
    listing = json.loads(LISTING.read_text())
    rendered = render(listing)
    if "--check" in argv:
        stale = []
        for rel, content in rendered.items():
            path = PACKAGES / rel
            want = content if isinstance(content, bytes) else content.encode()
            if not path.is_file() or path.read_bytes() != want:
                stale.append(rel)
        extra = [str(p.relative_to(PACKAGES)) for p in PACKAGES.rglob("*")
                 if p.is_file() and str(p.relative_to(PACKAGES)) not in rendered]
        if stale or extra:
            for rel in stale:
                print(f"STALE   packages/{rel}")
            for rel in extra:
                print(f"EXTRA   packages/{rel}")
            print("packages/ is not what listing.json renders - run scripts/generate.py")
            return 1
        print(f"OK: {len(rendered)} package files match listing.json")
        return 0
    if PACKAGES.exists():
        shutil.rmtree(PACKAGES)
    for rel, content in rendered.items():
        path = PACKAGES / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content)
    print(f"wrote {len(rendered)} files under {PACKAGES.relative_to(ROOT.parent.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
