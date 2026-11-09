#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Regulatory obligations register: validate it and keep its documentation page derived.

WHY
  openbank-libs/governance/regulatory-obligations.yaml starts from the LAW and maps each
  obligation onto the reports that cover it. Two ways for that to rot silently:
    1. the admin-ui catalogue gains a report no obligation claims (a report with no stated legal
       duty), or an obligation claims a report id that no longer exists;
    2. docs/compliance/regulatory-obligations.md is hand-edited or not regenerated, so the page
       people read disagrees with the register.
  This script fails on both, plus on schema errors (unknown status/kind/area/authority, a
  missing legal source, a service directory that does not exist).

USAGE
  check-regulatory-obligations.py            validate + fail if the doc is stale
  check-regulatory-obligations.py --write    regenerate the doc
  check-regulatory-obligations.py --self-test
"""
from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

import yaml

REGISTER = Path("openbank-libs/governance/regulatory-obligations.yaml")
CATALOG = Path("openbank-admin-ui/src/app/regulatory/page.tsx")
DOC = Path("docs/compliance/regulatory-obligations.md")

STATUS_LABEL = {
    "filed-return": "výkaz v katalogu, počítá backend",
    "bff-only": "výkaz v katalogu, počítá jen admin-ui (dluh ADR-0367)",
    "data-only": "data existují, výkaz chybí",
    "missing": "chybí",
    "external": "mimo platformu záměrně",
    "conditional": "jen za podmínky",
}
CONFIDENCE_LABEL = {"verified": "ověřeno", "known-eu": "EU text, neověřeno znovu", "unverified": "NEOVĚŘENO"}


def catalog_ids(root: Path) -> list[str]:
    text = (root / CATALOG).read_text(encoding="utf-8")
    start = text.find("const REPORT_CATALOG = [")
    if start < 0:
        return []
    block = text[start:]
    end = block.find("\n]\n")
    block = block[: end if end > 0 else len(block)]
    return re.findall(r"^    id: '([a-z0-9-]+)'", block, re.MULTILINE)


def validate(root: Path, reg: dict) -> list[str]:
    errs: list[str] = []
    statuses, kinds, confs = set(reg["statuses"]), set(reg["kinds"]), set(reg["confidences"])
    areas = {a["id"] for a in reg["areas"]}
    auths = set(reg["authorities"])
    seen: set[str] = set()
    claimed: set[str] = set()
    cat = catalog_ids(root)
    if not cat:
        errs.append(f"{CATALOG}: REPORT_CATALOG not found — the coverage check cannot run")
    for o in reg["obligations"]:
        oid = o.get("id", "?")
        if oid in seen:
            errs.append(f"{oid}: duplicate id")
        seen.add(oid)
        for field in ("area", "name", "authority", "legal_basis", "sources", "cadence",
                      "cadence_confidence", "kind", "transport", "personal_data", "applicability", "coverage"):
            if field not in o:
                errs.append(f"{oid}: missing field {field}")
        if o.get("area") not in areas:
            errs.append(f"{oid}: unknown area {o.get('area')}")
        if o.get("authority") not in auths:
            errs.append(f"{oid}: unknown authority {o.get('authority')}")
        if o.get("kind") not in kinds:
            errs.append(f"{oid}: unknown kind {o.get('kind')}")
        if o.get("cadence_confidence") not in confs:
            errs.append(f"{oid}: unknown cadence_confidence {o.get('cadence_confidence')}")
        if not (o.get("sources") or {}).get("law"):
            errs.append(f"{oid}: sources.law is required — an obligation without its legal text is an assertion")
        cov = o.get("coverage") or {}
        st = cov.get("status")
        if st not in statuses:
            errs.append(f"{oid}: unknown coverage.status {st}")
        reports = cov.get("reports") or []
        if st in ("filed-return", "bff-only") and not reports:
            errs.append(f"{oid}: status {st} needs at least one catalogue report")
        if st in ("missing", "conditional", "external") and reports:
            errs.append(f"{oid}: status {st} cannot claim reports {reports}")
        if st == "conditional" and not str(o.get("applicability", "")).startswith("conditional:"):
            errs.append(f"{oid}: status conditional needs 'applicability: conditional: <trigger>'")
        for r in reports:
            if r not in cat:
                errs.append(f"{oid}: claims unknown catalogue report '{r}'")
            claimed.add(r)
        for s in cov.get("services") or []:
            if not (root / s).is_dir():
                errs.append(f"{oid}: service directory {s} does not exist")
    for r in cat:
        if r not in claimed:
            errs.append(f"catalogue report '{r}' is claimed by no obligation — which legal duty does it serve?")
    return errs


def render(reg: dict) -> str:
    auth = reg["authorities"]
    obs = reg["obligations"]
    counts = {s: sum(1 for o in obs if o["coverage"]["status"] == s) for s in reg["statuses"]}
    L: list[str] = []
    L += [
        "<!-- GENERATED by .github/scripts/check-regulatory-obligations.py --write from",
        "     openbank-libs/governance/regulatory-obligations.yaml. Do not edit by hand. -->",
        "",
        "# Regulatorní povinnosti banky — registr a pokrytí",
        "",
        "Tato stránka vysvětluje, **co** musí česká univerzální banka vykazovat, **komu**, **podle jakého",
        "předpisu** a **kterým výkazem platformy** je povinnost pokryta. Výchozím bodem je zákon, ne kód:",
        "katalog výkazů v admin-ui vznikl z toho, co platforma uměla spočítat, a proto v něm povinnosti bez",
        "kódu (CEÚ, GSFT) nebyly vidět. Registr to obrací — každá povinnost je řádek se zdrojem, a mezera je",
        "řádek se stavem *chybí*, ne ticho.",
        "",
        "## Jak číst stránku",
        "",
        "- **Zdroj** odkazuje na znění předpisu a na technickou specifikaci úřadu (metodika, formát).",
        "- **Lhůta** je doplněna mírou jistoty: *ověřeno* (zkontrolováno proti citovanému zdroji),",
        "  *EU text* (známé evropské znění, znovu nekontrolováno), *NEOVĚŘENO* (stopa, ne fakt).",
        "  Před podáním vždy ověřte lhůtu v aktuálním znění předpisu.",
        "- **Pokrytí** popisuje stav na `main`:",
    ]
    for s in reg["statuses"]:
        L.append(f"  - `{s}` — {STATUS_LABEL.get(s, s)}")
    L += [
        "- Výkazy z regulatorního katalogu (`/regulatory`) se odkazují svým id; každý výkaz katalogu musí",
        "  patřit k nějaké povinnosti (gate `regulatory-obligations-register`).",
        "- Agent `regulatory-obligations-watcher` sleduje citované zdroje a změny navrhuje jako issue;",
        "  sám registr nemění (ADR-0367, rozhodnutí 6).",
        "",
        "## Souhrn",
        "",
        f"Celkem povinností: **{len(obs)}**.",
        "",
        "| Stav | Počet |",
        "|---|---|",
    ]
    for s in reg["statuses"]:
        L.append(f"| `{s}` — {STATUS_LABEL.get(s, s)} | {counts[s]} |")
    L.append("")
    for area in reg["areas"]:
        rows = [o for o in obs if o["area"] == area["id"]]
        if not rows:
            continue
        L += [f"## {area['cs']}", ""]
        for o in rows:
            a = auth.get(o["authority"], {"cs": o["authority"]})
            cov = o["coverage"]
            src = [f"[znění předpisu]({o['sources']['law']})"]
            if o["sources"].get("spec"):
                src.append(f"[specifikace úřadu]({o['sources']['spec']})")
            L += [
                f"### {o['name']['cs']}",
                "",
                f"*{o['name']['en']}* · `{o['id']}`",
                "",
                f"- **Adresát:** {a['cs']}",
                f"- **Právní základ:** {o['legal_basis']}",
                f"- **Zdroj:** {' · '.join(src)}",
                f"- **Lhůta:** {o['cadence']} *({CONFIDENCE_LABEL.get(o['cadence_confidence'], o['cadence_confidence'])})*",
                f"- **Druh / kanál:** {o['kind']} · {o['transport']}",
                f"- **Osobní údaje:** {o['personal_data']}",
            ]
            if o["applicability"] != "always":
                L.append(f"- **Použitelnost:** {str(o['applicability']).removeprefix('conditional: ')}")
            line = f"- **Pokrytí:** `{cov['status']}` — {STATUS_LABEL.get(cov['status'], cov['status'])}"
            if cov.get("reports"):
                line += "; výkazy " + ", ".join(f"`{r}`" for r in cov["reports"])
            if cov.get("services"):
                line += "; služby " + ", ".join(f"`{s}`" for s in cov["services"])
            if cov.get("tracking"):
                line += f"; sleduje {cov['tracking']}"
            L.append(line)
            if o.get("note"):
                L.append(f"- **Poznámka:** {o['note']}")
            L.append("")
    return "\n".join(L).rstrip() + "\n"


def run(root: Path, write: bool) -> int:
    reg = yaml.safe_load((root / REGISTER).read_text(encoding="utf-8"))
    errs = validate(root, reg)
    doc = render(reg)
    if write:
        (root / DOC).parent.mkdir(parents=True, exist_ok=True)
        (root / DOC).write_text(doc, encoding="utf-8")
    elif not (root / DOC).exists() or (root / DOC).read_text(encoding="utf-8") != doc:
        errs.append(f"{DOC} is stale — run: python3 .github/scripts/check-regulatory-obligations.py --write")
    for e in errs:
        print(f"::error::{e}")
    if errs:
        return 1
    print(f"regulatory-obligations: {len(reg['obligations'])} obligations, catalogue fully claimed, doc current")
    return 0


def self_test() -> int:
    """Known-positive and known-negative fixtures — the check must reject each defect."""
    base_reg = {
        "statuses": ["filed-return", "bff-only", "data-only", "missing", "external", "conditional"],
        "kinds": ["event"], "confidences": ["verified"],
        "authorities": {"cnb": {"cs": "ČNB", "en": "CNB", "url": None}},
        "areas": [{"id": "x", "cs": "X", "en": "X"}],
        "obligations": [{
            "id": "o1", "area": "x", "name": {"cs": "A", "en": "A"}, "authority": "cnb",
            "legal_basis": "z", "sources": {"law": "https://example.invalid/law", "spec": None},
            "cadence": "c", "cadence_confidence": "verified", "kind": "event", "transport": "t",
            "personal_data": "no", "applicability": "always",
            "coverage": {"status": "filed-return", "reports": ["r1"], "services": [], "tracking": None},
        }],
    }
    catalog = "const REPORT_CATALOG = [\n  {\n    id: 'r1',\n  },\n]\n"

    def case(mutate, catalog_text, expect_ok, label, write_first=True):
        import copy
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / CATALOG).parent.mkdir(parents=True)
            (root / CATALOG).write_text(catalog_text)
            reg = copy.deepcopy(base_reg)
            mutate(reg)
            (root / REGISTER).parent.mkdir(parents=True)
            (root / REGISTER).write_text(yaml.safe_dump(reg, allow_unicode=True))
            if write_first:
                run(root, write=True)
            rc = run(root, write=False)
            ok = (rc == 0) == expect_ok
            print(f"self-test {'PASS' if ok else 'FAIL'}: {label}")
            return ok

    def no_law(r): r["obligations"][0]["sources"]["law"] = None
    def bad_status(r): r["obligations"][0]["coverage"]["status"] = "done"
    def unknown_report(r): r["obligations"][0]["coverage"]["reports"] = ["nope"]
    def missing_with_report(r): r["obligations"][0]["coverage"]["status"] = "missing"
    results = [
        case(lambda r: None, catalog, True, "clean register passes"),
        case(no_law, catalog, False, "obligation without legal source is rejected"),
        case(bad_status, catalog, False, "unknown status is rejected"),
        case(unknown_report, catalog, False, "claim of unknown catalogue report is rejected"),
        case(missing_with_report, catalog, False, "'missing' that claims a report is rejected"),
        case(lambda r: None, catalog.replace("  },\n]", "  },\n  {\n    id: 'r2',\n  },\n]"), False,
             "unclaimed catalogue report is rejected"),
        case(lambda r: None, catalog, False, "stale doc is rejected", write_first=False),
    ]
    return 0 if all(results) else 1


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(run(Path.cwd(), write="--write" in sys.argv))
