#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Validate the assistant-platform packages (issue #16614). Exit 1 on any finding.

    python3 integrations/assistant-plugins/scripts/check.py             # drift + structure
    python3 integrations/assistant-plugins/scripts/check.py --release   # also refuse placeholders
    python3 integrations/assistant-plugins/scripts/check.py --selftest  # prove every check can fail

What it checks:
  1. TOOL DRIFT against openbank-mcp-service's McpToolRegistry.kt: every packaged tool exists with
     the same capability, input properties and required arguments; every registry tool is either
     included or explicitly excluded; every included capability is one ADR-0383 D4 allows on the
     customer channel (query.*.readonly or propose.payment).
  2. SINGLE SOURCE: packages/ is exactly what generate.py renders from listing.json, so the
     display name, logo and MCP URL cannot differ between packages.
  3. VENDOR STRUCTURE. No vendor schema is vendored: the agent-plugins.org schema carries no
     licence statement, and Anthropic publishes reference pages, not a schema file. These are
     structural checks of the documented rules (limits cited inline, read 2026-11-17). For the
     Claude Code plugin the authoritative validator is `claude plugin validate`, which the README
     runs; it is not invoked here so this script needs nothing but python3.
  4. NO CREDENTIALS anywhere in a package.
"""
from __future__ import annotations

import copy
import json
import re
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
REPO = ROOT.parent.parent
sys.path.insert(0, str(HERE))
import generate  # noqa: E402  (sibling module)

PLACEHOLDER = generate.PLACEHOLDER
ALLOWED_CAPABILITY = re.compile(r"^(query\.[a-z_]+\.readonly|propose\.payment)$")
RESERVED_CLAUDE_PREFIXES = ("claude-", "anthropic-", "anthropics-", "cc-plugin-")
CREDENTIAL_KEYS = re.compile(r'"(client_?secret|clientSecret|api_?key|password|private_?key|access_?token|refresh_?token)"', re.I)
CREDENTIAL_VALUES = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----|\bsk-[A-Za-z0-9]{16,}|\beyJ[A-Za-z0-9_-]{20,}\.")


# ------------------------------------------------------------------------------- registry parse
def parse_registry(text: str) -> dict[str, dict]:
    """Tool name -> {capability, properties, required} from McpToolRegistry.kt source."""
    caps_block = re.search(r"val capabilities: Map<String, String> = mapOf\((.*?)\n    \)", text, re.S)
    if not caps_block:
        raise ValueError("capabilities map not found in registry source")
    caps = dict(re.findall(r'"([a-z_]+)"\s+to\s+"([^"]+)"', caps_block.group(1)))
    tools: dict[str, dict] = {}
    chunks = text.split("ToolDefinition(")[1:]
    for chunk in chunks:
        m = re.search(r'name\s*=\s*"([a-z_]+)"', chunk)
        if not m:
            continue
        start = re.search(r"inputSchema\s*=\s*obj\(", chunk)
        if not start:
            raise ValueError(f"inputSchema not parsable for tool {m.group(1)}")
        depth, i = 1, start.end()
        while depth and i < len(chunk):  # balanced parens: the body may hold nested mapOf(...)
            depth += {"(": 1, ")": -1}.get(chunk[i], 0)
            i += 1
        if depth:
            raise ValueError(f"inputSchema not parsable for tool {m.group(1)}")
        body = chunk[start.end():i - 1]
        # JSON-schema keywords of a nested property map (`mapOf("type" to ..., "description" to ...)`)
        # are not property names; a real property literally called `type` would be missed - none exists.
        props = [p for p in re.findall(r'^\s*(?:mapOf\()?"([A-Za-z]+)"\s+to\b', body, re.M)
                 if p not in ("type", "description")]
        req_m = re.search(r"(?:required\s*=\s*)?(emptyList\(\)|listOf\(([^)]*)\))\s*,?\s*$", body.strip(), re.S)
        required = re.findall(r'"([A-Za-z]+)"', req_m.group(2) or "") if req_m else None
        if required is None:
            raise ValueError(f"required list not parsable for tool {m.group(1)}")
        tools[m.group(1)] = {"properties": sorted(props), "required": sorted(required)}
    for name, cap in caps.items():
        tools.setdefault(name, {"properties": None, "required": None})["capability"] = cap
    return tools


def check_tools(listing: dict, registry: dict[str, dict]) -> list[str]:
    errs = []
    included = {t["name"]: t for t in listing["tools"]["included"]}
    excluded = {t["name"] for t in listing["tools"]["excluded"]}
    for name in sorted(set(registry) - set(included) - excluded):
        errs.append(f"tool drift: registry tool '{name}' is neither included nor excluded in listing.json")
    for name in sorted((set(included) | excluded) - set(registry)):
        errs.append(f"tool drift: listing.json names '{name}', which the registry does not define")
    for name in sorted(set(included) & excluded):
        errs.append(f"tool '{name}' is both included and excluded")
    for name, t in included.items():
        if not ALLOWED_CAPABILITY.match(t["capability"]):
            errs.append(f"tool '{name}': capability '{t['capability']}' is not allowed on the customer channel (ADR-0383 D4)")
        if t["name"] != "propose_payment" and t["readOnlyHint"] is not True:
            errs.append(f"tool '{name}': a read tool must declare readOnlyHint true")
        if not t.get("title"):
            errs.append(f"tool '{name}': missing title (Claude directory requirement)")
        reg = registry.get(name)
        if not reg:
            continue
        if reg.get("capability") != t["capability"]:
            errs.append(f"tool drift: '{name}' capability registry={reg.get('capability')} listing={t['capability']}")
        if reg["properties"] is not None and sorted(t["properties"]) != reg["properties"]:
            errs.append(f"tool drift: '{name}' properties registry={reg['properties']} listing={sorted(t['properties'])}")
        if reg["required"] is not None and sorted(t["required"]) != reg["required"]:
            errs.append(f"tool drift: '{name}' required registry={reg['required']} listing={sorted(t['required'])}")
    return errs


# ------------------------------------------------------------------------------- vendor structure
def png_size(data: bytes) -> tuple[int, int] | None:
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def contrast_vs(hex_color: str, other: tuple[int, int, int]) -> float:
    def lum(rgb):
        def ch(c):
            c = c / 255
            return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
        r, g, b = (ch(x) for x in rgb)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    rgb = tuple(int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    a, b = sorted((lum(rgb), lum(other)), reverse=True)
    return (a + 0.05) / (b + 0.05)


def check_image(label: str, data: bytes) -> list[str]:
    # developers.openai.com/plugins/deploy/submission: square, >= 48x48, <= 4096 px, <= 5 MiB.
    size = png_size(data)
    if size is None:
        return [f"{label}: not a PNG"]
    w, h = size
    errs = []
    if w != h or w < 48 or w > 4096:
        errs.append(f"{label}: {w}x{h} must be square, 48..4096 px")
    if len(data) > 5 * 1024 * 1024:
        errs.append(f"{label}: larger than 5 MiB")
    return errs


def check_openai(rendered: dict, listing: dict) -> list[str]:
    errs = []
    url = listing["server"]["url"]
    tool_names = {t["name"] for t in listing["tools"]["included"]}
    pj = json.loads(rendered["openai-plugin/plugin.json"])
    if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", pj["name"]) or len(pj["name"]) > 64:
        errs.append("openai plugin.json: name must be kebab-case, <= 64 chars")
    for key in ("version", "description"):
        if not pj.get(key):
            errs.append(f"openai plugin.json: {key} required")
    oa = pj.get("extensions", {}).get("com.openai", {})
    itf = oa.get("interface", {})
    limits = {"displayName": 30, "shortDescription": 30, "longDescription": 4000}
    for key, lim in limits.items():
        if not itf.get(key):
            errs.append(f"openai interface.{key} required")
        elif len(itf[key]) > lim:
            errs.append(f"openai interface.{key} longer than {lim} chars")
    if not itf.get("category"):
        errs.append("openai interface.category required")
    caps = itf.get("capabilities", [])
    if len(caps) > 20 or any(len(c) > 120 for c in caps):
        errs.append("openai interface.capabilities: <= 20 entries, each <= 120 chars")
    prompts = itf.get("defaultPrompt", [])
    if len(prompts) > 3 or len(set(prompts)) != len(prompts) or any(len(p) > 128 or "@" in p for p in prompts):
        errs.append("openai interface.defaultPrompt: <= 3 unique prompts, each <= 128 chars, no @mentions")
    color = itf.get("brandColor", "")
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", color):
        errs.append("openai interface.brandColor must be #RRGGBB")
    elif contrast_vs(color, (255, 255, 255)) < 2.0:
        errs.append("openai interface.brandColor needs >= 2:1 contrast against white")
    for key in ("composerIcon", "logo"):
        rel = itf.get(key, "")
        if not rel.startswith("./") or "openai-plugin/" + rel[2:] not in rendered:
            errs.append(f"openai interface.{key}: './' path to a packaged file required")
        else:
            errs += check_image(f"openai {key}", rendered["openai-plugin/" + rel[2:]])
    for key in ("websiteURL", "supportURL", "privacyPolicyURL", "termsOfServiceURL"):
        v = itf.get(key)
        if v is not None and (not v.startswith("https://") or len(v) > 1024):
            errs.append(f"openai interface.{key}: HTTPS, <= 1024 chars")
    tc = oa.get("review", {}).get("test_cases", {})
    pos, neg = tc.get("positive", []), tc.get("negative", [])
    if len(pos) != 5 or len(neg) != 3:
        errs.append(f"openai review.test_cases: exactly 5 positive and 3 negative required, got {len(pos)}/{len(neg)}")
    for c in pos:
        if not all(c.get(k) for k in ("description", "prompt", "tools_triggered", "expected_behavior")):
            errs.append("openai positive test case missing a required field")
        for t in c.get("tools_triggered", []):
            if t not in tool_names:
                errs.append(f"openai test case triggers unknown tool '{t}'")
    for c in neg:
        if not all(c.get(k) for k in ("description", "prompt")):
            errs.append("openai negative test case missing a required field")
    for rel in ("openai-plugin/mcp.json", "openai-plugin/.mcp.json"):
        mj = json.loads(rendered[rel])
        servers = mj.get("mcpServers", {})
        if len(servers) != 1:
            errs.append(f"{rel}: plugin-level review requires exactly one MCP server")
        for name, s in servers.items():
            # agent-plugins.org mcp.schema.json: streamable-http entry allows only type/url/headers.
            if set(s) - {"type", "url", "headers"}:
                errs.append(f"{rel}: server '{name}' has keys outside type/url/headers")
            if s.get("type") != "streamable-http" or not str(s.get("url", "")).startswith("https://"):
                errs.append(f"{rel}: server '{name}' must be streamable-http over https")
            if s.get("headers"):
                errs.append(f"{rel}: public submission rejects non-empty headers")
            if s.get("url") != url:
                errs.append(f"{rel}: url differs from listing.json server.url")
    if set(json.loads(rendered["openai-plugin/mcp.json"])) - {"$schema", "mcpServers"}:
        errs.append("openai mcp.json: only $schema and mcpServers allowed at top level")
    cp = json.loads(rendered["openai-plugin/.codex-plugin/plugin.json"])
    if cp.get("interface") != itf:
        errs.append("codex plugin.json interface differs from the Agent Plugins interface")
    if cp.get("mcpServers") != "./.mcp.json":
        errs.append("codex plugin.json must point mcpServers at ./.mcp.json")
    return errs


def check_claude(rendered: dict, listing: dict) -> list[str]:
    errs = []
    ident, url = listing["identity"], listing["server"]["url"]
    pj = json.loads(rendered["claude-code-plugin/.claude-plugin/plugin.json"])
    name = pj.get("name", "")
    if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", name):
        errs.append("claude plugin.json: name must be kebab-case")
    low = name.lower()
    if low.startswith(RESERVED_CLAUDE_PREFIXES) or low in {"claude", "anthropic", "anthropics", "claude-code"}:
        errs.append("claude plugin.json: reserved name")
    if pj.get("displayName") != ident["displayName"]:
        errs.append("claude plugin.json: displayName differs from listing.json")
    for key in ("homepage", "documentationUrl", "supportUrl", "privacyPolicyUrl", "termsOfServiceUrl"):
        v = pj.get(key)
        if v is not None and not v.startswith("https://"):
            errs.append(f"claude plugin.json: {key} must be https://")
    icon = pj.get("icon", "")
    if "claude-code-plugin/" + icon.removeprefix("./") not in rendered:
        errs.append("claude plugin.json: icon must name a packaged file")
    mj = json.loads(rendered["claude-code-plugin/.mcp.json"])
    for sname, s in mj.get("mcpServers", {}).items():
        if s.get("type") != "http" or s.get("url") != url:
            errs.append(f"claude .mcp.json: server '{sname}' must be type http at listing.json server.url")
        if "headers" in s:
            errs.append(f"claude .mcp.json: server '{sname}' must not set headers (OAuth is the only auth)")
        if "clientId" in s.get("oauth", {}):
            errs.append(f"claude .mcp.json: server '{sname}' must not pin a clientId (no shipped client identity)")
    mk = json.loads(rendered[".claude-plugin/marketplace.json"])
    if not mk.get("name") or not mk.get("owner", {}).get("name") or not mk.get("plugins"):
        errs.append("marketplace.json: name, owner.name and plugins are required")
    for p in mk.get("plugins", []):
        if not str(p.get("source", "")).startswith("./") or ".." in p.get("source", ""):
            errs.append("marketplace.json: relative source must start with ./ and not contain ..")
    cl = json.loads(rendered["claude-connector-listing/directory-listing.json"])
    lst = cl["3_listing"]
    if len(lst["serverName"]) > 100 or len(lst["oneLiner"]) > 200 or len(lst["description"]) > 2000:
        errs.append("claude connector listing: name <= 100, one-liner <= 200, description <= 2000")
    if lst["serverName"] != ident["displayName"]:
        errs.append("claude connector listing: serverName differs from listing.json displayName")
    if cl["1_connection"]["universalUrl"] != url:
        errs.append("claude connector listing: URL differs from listing.json server.url")
    for t in cl["2_tools"]:
        if not t.get("title") or ("readOnlyHint" not in t and "destructiveHint" not in t):
            errs.append(f"claude connector listing: tool '{t.get('name')}' needs title + readOnlyHint/destructiveHint")
    errs += check_image("claude icon", rendered["claude-code-plugin/assets/icon.png"])
    return errs


def check_credentials(rendered: dict) -> list[str]:
    errs = []
    for rel, content in rendered.items():
        if isinstance(content, bytes):
            continue
        if CREDENTIAL_KEYS.search(content) or CREDENTIAL_VALUES.search(content):
            errs.append(f"packages/{rel}: looks like it carries a credential")
    return errs


def check_release(listing: dict) -> list[str]:
    errs = []
    def walk(node, path):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, f"{path}.{k}")
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f"{path}[{i}]")
        elif isinstance(node, str) and node.startswith(PLACEHOLDER):
            errs.append(f"release: {path} is still a placeholder ({node})")
    walk(listing["identity"], "identity")
    if listing["server"]["routeStatus"] != "LIVE":
        errs.append(f"release: server.routeStatus is {listing['server']['routeStatus']} (ADR-0383: no route yet)")
    if listing["server"]["scopes"]["status"] != "DEFINED":
        errs.append("release: scope names are not defined in the customers realm yet")
    errs.append("release: openai review.demo_recording_url is owner-supplied and not set")
    return errs


def run_checks(listing: dict, registry_text: str, rendered: dict) -> list[str]:
    errs = check_tools(listing, parse_registry(registry_text))
    errs += check_openai(rendered, listing)
    errs += check_claude(rendered, listing)
    errs += check_credentials(rendered)
    return errs


def on_disk_drift(rendered: dict) -> list[str]:
    errs = []
    for rel, content in rendered.items():
        want = content if isinstance(content, bytes) else content.encode()
        p = generate.PACKAGES / rel
        if not p.is_file() or p.read_bytes() != want:
            errs.append(f"packages/{rel} differs from what listing.json renders (run scripts/generate.py)")
    return errs


# ------------------------------------------------------------------------------- selftest
def selftest(listing: dict, registry_text: str) -> int:
    """Each case removes a protection or feeds the thing it must reject; each must FAIL."""
    base_rendered = generate.render(listing)
    if run_checks(listing, registry_text, base_rendered):
        print("selftest: the unmodified tree must pass first")
        return 1

    def with_listing(mut):
        lst = copy.deepcopy(listing)
        mut(lst)
        return lst, registry_text, generate.render(lst)

    def with_registry(old, new):
        assert old in registry_text, old
        return listing, registry_text.replace(old, new, 1), base_rendered

    def with_rendered(rel, mut):
        r = dict(base_rendered)
        obj = json.loads(r[rel])
        mut(obj)
        r[rel] = json.dumps(obj)
        return listing, registry_text, r

    cases = {
        "registry renames a tool": with_registry('name = "get_balance"', 'name = "get_balances"'),
        "registry adds a required arg": with_registry('listOf("paymentId")', 'listOf("paymentId", "rail")'),
        "registry changes a capability": with_registry('"get_balance" to "query.balance.readonly"',
                                                       '"get_balance" to "query.balance.full"'),
        "registry adds an unclassified tool": with_registry('"propose_payment" to "propose.payment",',
                                                            '"propose_payment" to "propose.payment",\n        "initiate_payment" to "account.initiate-payment",'),
        "package includes a write capability": with_listing(
            lambda l: l["tools"]["included"][1].update(capability="account.initiate-payment")),
        "read tool not marked read-only": with_listing(lambda l: l["tools"]["included"][0].update(readOnlyHint=False)),
        "display name over 30 chars": with_listing(
            lambda l: l["identity"].update(displayName="OpenBank Customer Assistant Connector")),
        "brand color too light": with_listing(lambda l: l["identity"].update(brandColor="#F5F5FF")),
        "wrong number of test cases": with_rendered(
            "openai-plugin/plugin.json",
            lambda o: o["extensions"]["com.openai"]["review"]["test_cases"]["negative"].pop()),
        "second URL in one package": with_rendered(
            "claude-code-plugin/.mcp.json",
            lambda o: o["mcpServers"]["openbank"].update(url="https://api.open-bank.tech/mcp")),
        "header on openai server": with_rendered(
            "openai-plugin/mcp.json",
            lambda o: o["mcpServers"]["openbank"].update(headers={"Authorization": "Bearer x"})),
        "client secret in a package": with_rendered(
            "openai-plugin/.mcp.json", lambda o: o.update(clientSecret="redacted-test-value")),
        "pinned clientId in claude plugin": with_rendered(
            "claude-code-plugin/.mcp.json", lambda o: o["mcpServers"]["openbank"]["oauth"].update(clientId="x")),
        "reserved claude plugin name": with_rendered(
            "claude-code-plugin/.claude-plugin/plugin.json", lambda o: o.update(name="claude-openbank")),
    }
    failed = 0
    for label, (lst, reg, rendered) in cases.items():
        found = run_checks(lst, reg, rendered)
        status = "caught" if found else "MISSED"
        if not found:
            failed += 1
        print(f"selftest {status}: {label}" + (f"  -> {found[0]}" if found else ""))
    placeholder_errs = check_release(listing)
    print(f"selftest {'caught' if placeholder_errs else 'MISSED'}: --release refuses placeholders ({len(placeholder_errs)} findings)")
    failed += 0 if placeholder_errs else 1
    print("selftest: PASS" if not failed else f"selftest: FAIL ({failed} case(s) not caught)")
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    listing = json.loads(generate.LISTING.read_text())
    registry_path = REPO / listing["tools"]["registry"]
    registry_text = registry_path.read_text()
    if "--selftest" in argv:
        return selftest(listing, registry_text)
    rendered = generate.render(listing)
    errs = run_checks(listing, registry_text, rendered) + on_disk_drift(rendered)
    if "--release" in argv:
        errs += check_release(listing)
    for e in errs:
        print(f"FAIL {e}")
    if errs:
        return 1
    print(f"OK: {len(listing['tools']['included'])} tools match the registry; {len(rendered)} package files "
          "match listing.json; OpenAI/Claude structural checks pass; no credentials")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
