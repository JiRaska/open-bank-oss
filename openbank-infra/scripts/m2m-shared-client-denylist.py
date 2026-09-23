#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline deny-list for dropping ROLE_OPERATOR from the shared M2M principal (#10486).

For every rest-client that still mints its bearer from the SHARED Keycloak client
`openbank-services` (a default `@OidcClientFilter`, or a named one whose client-id is still
`openbank-services`), resolve the upstream JAX-RS endpoint it calls and ask two questions with
the shared principal holding ROLE_API ONLY:

  1. RBAC  - does the endpoint's @RolesAllowed (method, else class) admit ROLE_API?
  2. OPA   - does `data.openbank.rest.allow` on the upstream's COMMITTED bundle ConfigMap
             (materialised to the sidecar layout) allow the endpoint's @Authorize action for
             principal {id: service-account-openbank-services, type: HUMAN, roles: [ROLE_API]}?

Every call that fails either question is a BLOCKER for the final step: the list printed under
"DENY" must be empty before ROLE_OPERATOR is removed (runbook 0009, final step).

Controls (the probe is held to a known-positive and a known-negative on every run, or it exits 2):
  must-ALLOW  ledger.create for service-account-openbank-lending   (identity rule, batch 1)
  must-DENY   ledger.replay for service-account-openbank-services  with ROLE_API only
              (operator-only; its ledger.create is still admitted by the legacy shared
              `service-ledger-post` rule, so that action cannot be the negative control)
A probe that cannot express the failure reports clean; these make that visible.

Usage:  python3 openbank-infra/scripts/m2m-shared-client-denylist.py [--json] [--all]
Exit:   0 = deny-list empty, 1 = blockers remain, 2 = probe broken (controls failed / no opa).
Needs `opa` on PATH. Reads only the working tree; touches no cluster and mints no token.
"""

from __future__ import annotations

import atexit
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHARED = "openbank-services"
SHARED_PRINCIPAL = "service-account-openbank-services"
VERBS = ("GET", "POST", "PUT", "DELETE", "PATCH")
ROLE_CONST = {
    "ADMIN": "ROLE_ADMIN", "OPERATOR": "ROLE_OPERATOR", "VIEWER": "ROLE_VIEWER",
    "COMPLIANCE": "ROLE_COMPLIANCE", "AUDITOR": "ROLE_AUDITOR", "SUPERVISOR": "ROLE_SUPERVISOR",
    "KYC": "ROLE_KYC", "KYC_OPENER": "ROLE_KYC_OPENER", "KYC_REVIEWER": "ROLE_KYC_REVIEWER",
    "PAYMENTS": "ROLE_PAYMENTS", "API": "ROLE_API",
}


def modules() -> list[Path]:
    return sorted(p for p in ROOT.glob("openbank-*") if (p / "src/main").is_dir())


def kt_files(mod: Path):
    yield from (mod / "src/main").rglob("*.kt")


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"(?m)//.*$", "", src)


def path_ann(text: str) -> str | None:
    m = re.search(r'@(?:jakarta\.ws\.rs\.)?Path\(\s*(?:value\s*=\s*)?"([^"]*)"', text)
    return m.group(1) if m else None


def roles_of(text: str) -> list[str] | None:
    m = re.search(r"@RolesAllowed\(([^)]*)\)", text)
    if not m:
        if "@PermitAll" in text or "@Authenticated" in text:
            return ["*"]
        return None
    out = []
    for tok in re.findall(r'Roles\.(\w+)|"([^"]+)"', m.group(1)):
        out.append(ROLE_CONST.get(tok[0], tok[0]) if tok[0] else tok[1])
    return out


def authorize_of(text: str) -> str | None:
    m = re.search(r'@Authorize\(\s*(?:action\s*=\s*)?"([^"]+)"', text)
    return m.group(1) if m else None


def verb_of(text: str) -> str | None:
    for v in VERBS:
        if re.search(rf"@(?:jakarta\.ws\.rs\.)?{v}\b", text):
            return v
    return None


def split_methods(body: str):
    """Yield (annotation_block, fun_name) for each fun in a class/interface body."""
    last = 0
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", body):
        seg = body[last:m.start()]
        # Annotations of THIS fun only: walk back over lines that are an annotation (or a
        # continuation of a multi-line annotation argument list), stop at anything else.
        kept, depth = [], 0
        lines = seg.split("\n")
        for i, line in enumerate(reversed(lines)):
            s = line.strip()
            if i == 0:  # the partial line holding modifiers before `fun` (e.g. `suspend`)
                kept.append(line)
                continue
            depth += s.count(")") - s.count("(")
            if depth > 0 or s == "" or s.startswith("@"):
                kept.append(line)
                if depth < 0:
                    depth = 0
                continue
            break
        yield "\n".join(reversed(kept)), m.group(1)
        last = m.end()


def join(a: str | None, b: str | None) -> str:
    p = "/".join(s.strip("/") for s in (a or "", b or "") if s and s.strip("/"))
    return "/" + p


def norm(path: str) -> list[str]:
    return ["*" if s.startswith("{") else s for s in path.strip("/").split("/") if s]


def path_match(a: str, b: str) -> bool:
    x, y = norm(a), norm(b)
    return len(x) == len(y) and all(p == q or "*" in (p, q) for p, q in zip(x, y, strict=True))


# ------------------------------------------------------------------ server side
def server_endpoints(mod: Path):
    eps = []
    for f in kt_files(mod):
        src = strip_comments(f.read_text(errors="ignore"))
        if "@RegisterRestClient" in src or "@Path" not in src:
            continue
        for cm in re.finditer(r"\bclass\s+(\w+)", src):
            head = src[:cm.start()]
            # class-level annotations = text after the last '}' or import line before `class`
            cut = max(head.rfind("}\n"), head.rfind("\nimport "))
            chead = head[cut:]
            cpath = path_ann(chead)
            if cpath is None:
                continue
            croles = roles_of(chead)
            # body up to the next top-level class
            nxt = re.search(r"\n(?:@\w+[^\n]*\n)*(?:(?:open|data|abstract|internal|private)\s+)*class\s+\w+", src[cm.end():])
            body = src[cm.end(): cm.end() + nxt.start()] if nxt else src[cm.end():]
            for ann, name in split_methods(body):
                verb = verb_of(ann)
                if not verb:
                    continue
                eps.append({
                    "module": mod.name, "file": str(f.relative_to(ROOT)), "method": name,
                    "verb": verb, "path": join(cpath, path_ann(ann)),
                    "roles": roles_of(ann) or croles, "action": authorize_of(ann),
                })
    return eps


# ------------------------------------------------------------------ client side
def yaml_text(mod: Path) -> str:
    p = mod / "src/main/resources/application.yaml"
    return p.read_text(errors="ignore") if p.exists() else ""


def named_client_id(mod: Path, name: str | None) -> str | None:
    y = yaml_text(mod)
    m = re.search(r"(?m)^(\s*)oidc-client:\s*$", y)
    if not m:
        return None
    ind = len(m.group(1))
    block = []
    for line in y[m.end():].splitlines():
        if line.strip() and (len(line) - len(line.lstrip())) <= ind and not line.lstrip().startswith("#"):
            break
        block.append(line)
    sub_ind = None
    cur = None
    ids: dict[str | None, str] = {}
    for line in block:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        li = len(line) - len(line.lstrip())
        if sub_ind is None:
            sub_ind = li
        k = line.strip().split(":", 1)[0]
        if li == sub_ind:
            cur = None
            if k == "client-id":
                ids[None] = line.split(":", 1)[1].strip()
            elif line.strip().endswith(":"):
                cur = k
        elif cur and k == "client-id":
            ids[cur] = line.split(":", 1)[1].strip()
    v = ids.get(name)
    if v:
        m2 = re.search(r"\$\{[^:}]+:([^}]+)\}", v)
        return (m2.group(1) if m2 else v).strip("'\"")
    return None


def client_url(mod: Path, key: str) -> str | None:
    y = yaml_text(mod)
    for m in re.finditer(rf'(?m)^\s*"?{re.escape(key)}"?:\s*$', y):
        for line in y[m.end():].splitlines()[:8]:
            if re.match(r"\s*url:", line):
                return line.split(":", 1)[1].strip()
    m = re.search(rf"rest-client\.\"?{re.escape(key)}\"?\.url[=:]\s*(\S+)", y)
    return m.group(1) if m else None


_PORTS: dict[str, str] = {}


def port_map() -> dict[str, str]:
    """Local-dev default port -> module (the rest-client URL defaults are `localhost:<port>`)."""
    if not _PORTS:
        for mod in modules():
            lines = yaml_text(mod).splitlines()
            for i, line in enumerate(lines):
                if line.strip() != "http:":
                    continue
                ind = len(line) - len(line.lstrip())
                for nxt in lines[i + 1:]:
                    if nxt.strip() and len(nxt) - len(nxt.lstrip()) <= ind:
                        break
                    pm = re.match(r"\s+port:\s*(?:\$\{[A-Z_]+:)?(\d+)", nxt)
                    if pm and len(nxt) - len(nxt.lstrip()) == ind + 2:
                        _PORTS.setdefault(pm.group(1), mod.name)
                        break
                break
    return _PORTS


def target_module(url: str | None, mods: set[str]) -> str | None:
    if not url:
        return None
    pm = re.search(r"localhost:(\d+)", url)
    if pm and pm.group(1) in port_map():
        return port_map()[pm.group(1)]
    for host in re.findall(r"//([a-z0-9-]+)", url) + re.findall(r"(openbank-[a-z0-9-]+)", url):
        for cand in (host, "openbank-" + host, host.removesuffix("-svc"), "openbank-" + host + "-service"):
            if cand in mods:
                return cand
    return None


def clients(mod: Path):
    out = []
    for f in kt_files(mod):
        src = strip_comments(f.read_text(errors="ignore"))
        if "@RegisterRestClient" not in src:
            continue
        ifaces = list(re.finditer(r"\binterface\s+(\w+)", src))
        for idx, im in enumerate(ifaces):
            # This interface's annotations = text between the previous interface's body and it;
            # its body = up to the next interface. Several clients often share one file.
            start = ifaces[idx - 1].end() if idx else 0
            prev = src[start:im.start()]
            cut = prev.rfind("\n}")
            head = prev[cut + 2:] if (idx and cut >= 0) else prev
            end = ifaces[idx + 1].start() if idx + 1 < len(ifaces) else len(src)
            out.extend(_client_rows(mod, f, src, im, head, src[im.end():end]))
    return out


def _client_rows(mod: Path, f: Path, src: str, im, head: str, body: str):
    out = []
    if "@RegisterRestClient" in head:
        km = re.search(r'configKey\s*=\s*"([^"]+)"', head)
        fm = re.search(r'@OidcClientFilter(?:\(\s*(?:value\s*=\s*)?"?([^")]*)"?\s*\))?', head)
        # The fleet's dominant idiom is `@RegisterProvider(OidcClientRequest[Reactive]Filter::class)`,
        # which always mints from the DEFAULT oidc-client. Missing it made this probe report
        # "0 calls inspected, clean" on its first run.
        rp = re.search(r"@RegisterProvider\(\s*OidcClientRequest(?:Reactive)?Filter::class", head)
        if not fm and not rp:
            return out  # no minted token at all (propagation / anonymous) - not this principal
        name = ((fm.group(1) or "").strip() or None) if fm else None
        cid = named_client_id(mod, name)
        for ann, fun in split_methods(body):
            verb = verb_of(ann)
            if verb:
                out.append({
                    "caller": mod.name, "file": str(f.relative_to(ROOT)), "iface": im.group(1),
                    "fun": fun, "verb": verb, "path": join(path_ann(head), path_ann(ann)),
                    "configKey": km.group(1) if km else None, "oidc": name or "<default>",
                    "client_id": cid,
                })
    return out


# ------------------------------------------------------------------ OPA
def bundles() -> dict[str, Path]:
    out = {}
    for b in (ROOT / "openbank-infra/gitops/components").rglob("*opa-bundle*.yaml"):
        t = b.read_text(errors="ignore")
        m = re.search(r"app\.kubernetes\.io/name:\s*(\S+)", t)
        if m and "rest.rego: |" in t:
            out.setdefault(m.group(1), b)
    return out


def enforce_map(mods: set[str]) -> dict[str, bool]:
    """module -> AUTHZ_ENFORCE as deployed (gitops env). Absent => libs default `true`."""
    import yaml

    out: dict[str, bool] = {}
    for f in (ROOT / "openbank-infra/gitops/components").rglob("*.yaml"):
        t = f.read_text(errors="ignore")
        if "AUTHZ_ENFORCE" not in t:
            continue
        try:
            docs = list(yaml.safe_load_all(t))
        except yaml.YAMLError:
            continue
        for d in docs:
            if not isinstance(d, dict) or d.get("kind") not in ("Deployment", "Rollout", "StatefulSet"):
                continue
            name = (d.get("metadata") or {}).get("name", "")
            mod = next((c for c in (f"openbank-{name}", f"openbank-{name}-service",
                                    f"openbank-{name.removesuffix('-service')}") if c in mods), None)
            if not mod:
                continue
            for c in ((d.get("spec") or {}).get("template") or {}).get("spec", {}).get("containers", []) or []:
                for ev in c.get("env") or []:
                    if ev.get("name") == "AUTHZ_ENFORCE" and "value" in ev:
                        out[mod] = str(ev["value"]).lower() == "true"
    return out


def materialise(bundle: Path, dest: Path) -> None:
    import yaml  # PyYAML

    doc = yaml.safe_load(bundle.read_text())
    for k, v in doc["data"].items():
        if k.endswith(".rego"):
            (dest / k).write_text(v)
        elif k == "rules-data.yaml":
            (dest / "rules").mkdir(exist_ok=True)
            (dest / "rules/data.yaml").write_text(v)
        elif k == "agents-data.yaml":
            (dest / "agents").mkdir(exist_ok=True)
            (dest / "agents/data.yaml").write_text(v)


_opa_cache: dict[tuple, bool] = {}
_dirs: dict[str, str] = {}


def opa_allow(bundle: Path, principal_id: str, roles: list[str], action: str) -> bool:
    key = (str(bundle), principal_id, tuple(roles), action)
    if key in _opa_cache:
        return _opa_cache[key]
    if str(bundle) not in _dirs:
        d = tempfile.mkdtemp(prefix="m2m-denylist-")
        atexit.register(shutil.rmtree, d, True)
        materialise(bundle, Path(d))
        _dirs[str(bundle)] = d
    d = _dirs[str(bundle)]
    if True:
        inp = {"principal": {"type": "HUMAN", "id": principal_id, "roles": roles}, "action": action}
        r = subprocess.run(
            ["opa", "eval", "-f", "json", "-b", d, "-I", "data.openbank.rest.allow"],
            input=json.dumps(inp), capture_output=True, text=True,
        )
        if r.returncode != 0:
            raise RuntimeError(f"opa eval failed on {bundle}: {r.stderr[:400]}")
        res = json.loads(r.stdout).get("result") or []
        v = res[0]["expressions"][0]["value"] if res else None
        # `allow` is a decision OBJECT ({allow, reason, ...}) in this policy, not a bare boolean.
        val = bool(v.get("allow") is True) if isinstance(v, dict) else v is True
    _opa_cache[key] = val
    return val


def svc_name(module: str) -> str:
    return module


def main() -> int:
    as_json = "--json" in sys.argv
    show_all = "--all" in sys.argv
    if not shutil.which("opa"):
        print("opa not on PATH", file=sys.stderr)
        return 2
    mods = modules()
    names = {m.name for m in mods}
    bmap = bundles()
    enf = enforce_map(names)
    servers: dict[str, list] = {m.name: server_endpoints(m) for m in mods}

    # controls - on the ledger bundle
    lb = bmap.get("ledger-service")
    if not lb:
        print("control bundle ledger-service not found", file=sys.stderr)
        return 2
    if not opa_allow(lb, "service-account-openbank-lending", ["ROLE_API"], "ledger.create"):
        print("CONTROL FAILED: must-ALLOW lending ledger.create denied - probe broken", file=sys.stderr)
        return 2
    if opa_allow(lb, SHARED_PRINCIPAL, ["ROLE_API"], "ledger.replay"):
        print("CONTROL FAILED: must-DENY shared ledger.replay allowed - probe broken", file=sys.stderr)
        return 2

    rows = []
    for m in mods:
        for c in clients(m):
            if c["client_id"] != SHARED:
                continue
            tgt = target_module(client_url(m, c["configKey"] or ""), names) if c["configKey"] else None
            def find(cands, c=c):
                return [e for n in cands for e in servers.get(n, [])
                        if e["verb"] == c["verb"] and path_match(e["path"], c["path"])]

            # URL-derived target first; the local-dev port defaults are not unique across the
            # fleet, so fall back to a path match over every OTHER module.
            hits = find([tgt]) if tgt else []
            if not hits:
                hits = find([n for n in names if n != m.name])
                if len({e["module"] for e in hits}) > 1:
                    key = (c["configKey"] or "").replace("-api", "").replace("-client", "")
                    pref = [e for e in hits if key and key.split("-")[0] in e["module"]]
                    exact = [e for e in hits if norm(e["path"]) == norm(c["path"])]
                    hits = pref or exact or hits
            # Most specific first: a server literal matched by a client TEMPLATE segment
            # (`/transactions/pending` vs the client's `/transactions/{id}`) is the weakest match
            # and must lose to a server template in the same position.
            def score(e, c=c):
                # hits already passed path_match, so both paths have equal segment counts
                s, cp = norm(e["path"]), norm(c["path"])
                return (-sum(1 for p, q in zip(s, cp, strict=True) if q == "*" and p != "*"),
                        sum(1 for p, q in zip(s, cp, strict=True) if p == q != "*"))
            hits.sort(key=score, reverse=True)
            row = dict(c, target=tgt)
            if not hits:
                row.update(status="UNRESOLVED", reason="no matching upstream endpoint in repo")
                rows.append(row)
                continue
            e = hits[0]
            row.update(target=e["module"], endpoint=f'{e["file"]}#{e["method"]}', action=e["action"],
                       roles=e["roles"])
            rbac_ok = e["roles"] is None or "*" in e["roles"] or "ROLE_API" in e["roles"]
            b = bmap.get(e["module"].removeprefix("openbank-")) or bmap.get(e["module"]) \
                or bmap.get(e["module"].removeprefix("openbank-") + "-service")
            if e["action"] and b:
                opa_ok = opa_allow(b, SHARED_PRINCIPAL, ["ROLE_API"], e["action"])
            else:
                opa_ok = None  # no @Authorize, or no bundle => no OPA decision on this call
            reasons = []
            if not rbac_ok:
                reasons.append(f"RBAC {e['roles']} lacks ROLE_API")
            if opa_ok is False:
                reasons.append(f"OPA denies {e['action']}")
            # Does the call work TODAY (shared principal with ROLE_API + ROLE_OPERATOR)? A call
            # that is already denied is a pre-existing defect, not something the final step breaks.
            today_roles = ["ROLE_API", "ROLE_OPERATOR"]
            rbac_today = e["roles"] is None or "*" in e["roles"] or bool(set(today_roles) & set(e["roles"]))
            opa_today = opa_allow(b, SHARED_PRINCIPAL, today_roles, e["action"]) if (e["action"] and b) else None
            enforced = enf.get(e["module"], True)
            works_today = rbac_today and (opa_today is not False or not enforced)
            if not reasons:
                status = "ok"
            elif not works_today:
                status = "BROKEN"
            elif rbac_ok and not enforced:
                # Only OPA says no, and this upstream runs advisory: the call keeps working after
                # the final step, but it becomes a 403 the day AUTHZ_ENFORCE flips.
                status = "ADVISORY"
            else:
                status = "DENY"
            row.update(rbac_ok=rbac_ok, opa_ok=opa_ok, works_today=works_today, enforced=enforced,
                       status=status, reason="; ".join(reasons))
            rows.append(row)

    deny = [r for r in rows if r["status"] == "DENY"]
    shown = rows if show_all else [r for r in rows if r["status"] != "ok"]
    if as_json:
        print(json.dumps(shown, indent=1))
    else:
        for r in shown:
            kind = "write" if r["verb"] != "GET" else "read"
            print(f'{r["status"]:10} {kind:5} {r["caller"]:34} {r["verb"]:6} {r["path"]:52} -> '
                  f'{r.get("target") or "?"} {r.get("action") or "-"}  {r.get("reason", "")}')
        n = {s: sum(r["status"] == s for r in rows) for s in ("DENY", "ADVISORY", "BROKEN", "UNRESOLVED")}
        print(f"\n{n['DENY']} blocker(s) [DENY: works today, denied with ROLE_API only]; "
              f"{n['ADVISORY']} advisory [OPA-only deny on an AUTHZ_ENFORCE=false upstream]; "
              f"{n['BROKEN']} already denied today [BROKEN: pre-existing, not a blocker]; "
              f"{n['UNRESOLVED']} unresolved (verify by hand); {len(rows)} shared-client calls inspected. "
              "Controls: must-allow OK, must-deny OK.")
    return 1 if deny else 0


if __name__ == "__main__":
    sys.exit(main())
