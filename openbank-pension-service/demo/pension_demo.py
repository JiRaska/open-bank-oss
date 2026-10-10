#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run existing HTTP/DB journeys twice and produce honest, fresh demo evidence (stdlib only)."""
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import html
import fcntl
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PENSION = "openbank-pension-service"
FUND = "openbank-pension-fund-service"
JOURNEY = "com.openbank.pension.e2e.PensionFullLifecycleJourneyE2E"
BOUNDARY = "com.openbank.pension.e2e.PensionDemoBoundaryIT"
STRATEGY = "com.openbank.pension.infrastructure.fund.PensionFundRestAdapterTest"
LIMITS = [
    "Skutečné HTTP, PostgreSQL a implementace penzijních workflow; samostatný testovací JVM, databáze a Temporal server pro každou společnost.",
    "Alpha a Beta běží postupně. Identita volajícího je testovací; OIDC/relay/OPA napříč současně běžícími společnostmi tento běh neověřuje (#12472).",
    "Platby, identita/SCA, dokumenty, státní agentura, anuitní partneři a účastnické ocenění používají testovací adaptéry/simulátory. Nejde o skutečné bankovní převody nebo regulatorní podání.",
    "Fondový registr a NAV se ověřují samostatně přes HTTP/DB, propojení služeb pomocí Pact. Souvislý běh obou služeb se skutečnými adaptéry zůstává #12479.",
    "Migrační zkouška, trvalé opravy NAV/kompenzace, plná izolace poskytovatelů a odolnost produkčního provozu nejsou tímto demem potvrzené.",
]


@dataclasses.dataclass(frozen=True)
class Phase:
    name: str
    title: str
    arguments: tuple[str, ...]
    suites: tuple[tuple[str, str, int], ...]  # relative result directory, exact class, minimum tests
    company: str | None = None


def phases() -> list[Phase]:
    result = []
    for company in ("alpha", "beta"):
        result.append(Phase(
            company, f"Syntetická penzijní společnost {company.title()}",
            (f":{PENSION}:test", "--rerun", "--tests", JOURNEY, "--tests", BOUNDARY, "--tests", STRATEGY,
             f"-PpensionDemoCompany={company}"),
            ((f"{PENSION}/build/test-results/test", JOURNEY, 23),
             (f"{PENSION}/build/test-results/test", BOUNDARY, 3),
             (f"{PENSION}/build/test-results/test", STRATEGY, 3)), company,
        ))
    prefix = "com.openbank.pensionfund."
    classes = [("integration.PensionFundApiIT", 5), ("integration.OutgoingReservationIT", 2),
               ("application.UnitRegisterFlowTest", 15)]
    contracts = [("PensionFundPactFolderProviderVerificationTest", 6),
                 ("PensionFundNegativeAuthProviderVerificationTest", 2)]
    args = [f":{FUND}:test", "--rerun"]
    suites = []
    for name, count in classes:
        args += ["--tests", prefix + name]
        suites.append((f"{FUND}/build/test-results/test", prefix + name, count))
    args += [f":{FUND}:providerPactTest", "--rerun"]
    for name, count in contracts:
        args += ["--tests", prefix + "contract." + name]
        suites.append((f"{FUND}/build/test-results/providerPactTest", prefix + "contract." + name, count))
    result.append(Phase("fund", "Fondový registr, chyby a kontrakt mezi službami", tuple(args), tuple(suites)))
    return result


def read_suite(path: Path, started: float, minimum: int) -> dict:
    if not path.is_file() or path.stat().st_mtime < started:
        raise ValueError(f"Chybějící nebo starý výsledek: {path.name}")
    root = ET.parse(path).getroot()
    cases = root.findall("testcase")
    if len(cases) < minimum or int(root.get("tests", "0")) != len(cases):
        raise ValueError(f"Neúplná sada {path.name}: {len(cases)}, očekáváno alespoň {minimum}")
    if any(int(root.get(key, "0")) for key in ("failures", "errors", "skipped")):
        raise ValueError(f"Selhání nebo přeskočený test: {path.name}")
    if any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
        raise ValueError(f"Neúspěšný jednotlivý test: {path.name}")
    return {"suite": root.get("name"), "tests": [case.get("name") for case in cases],
            "output": "\n".join(node.text or "" for node in root.iter("system-out"))}


def verify_context(output: str, company: str) -> dict:
    matches = re.findall(r"PENSION_DEMO_CONTEXT\|([a-z]+)\|([0-9a-f-]+)\|([a-z_]+)", output)
    suffix = "1" if company == "alpha" else "2"
    expected = (company, f"00000000-0000-4000-8000-00000000000{suffix}", f"openbank_pension_demo_{company}")
    if set(matches) != {expected}:
        raise ValueError(f"Chybí důkaz správné konfigurace společnosti {company}; nepřebírám pouze popisek běhu")
    return dict(zip(("company", "providerId", "database"), expected))


def collect(root: Path, phase: Phase, started: float, returncode: int, evidence: Path) -> dict:
    if returncode:
        raise ValueError(f"Gradle skončil s kódem {returncode}; staré zelené XML se nepoužije")
    suites = []
    for directory, classname, minimum in phase.suites:
        source = root / directory / f"TEST-{classname}.xml"
        suite = read_suite(source, started, minimum)
        if suite["suite"] != classname:
            raise ValueError(f"Nesouhlasí identita sady: {source.name}")
        suites.append(suite)
        shutil.copy2(source, evidence / source.name)
    context = verify_context("\n".join(s["output"] for s in suites), phase.company) if phase.company else None
    for suite in suites:
        suite.pop("output")  # full logs stay local; the presentation contains only test names/status
    return {"status": "PASSED", "context": context, "suites": suites}


def render(report: dict, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    sections = []
    for phase in report["phases"]:
        tests = "".join(f"<li>{html.escape(name)}</li>" for suite in phase.get("suites", []) for name in suite["tests"])
        context = html.escape(json.dumps(phase.get("context"), ensure_ascii=False)) if phase.get("context") else ""
        sections.append(f'<section><h2>{html.escape(phase["title"])}</h2><b>{phase["status"]}</b>'
                        f'<p>{context}</p><p>Trvání: {phase.get("durationSeconds", "—")} s</p><p>{html.escape(phase.get("error", ""))}</p>'
                        f'<details><summary>Ověřené kroky</summary><ol>{tests}</ol></details></section>')
    limitations = "".join(f"<li>{html.escape(item)}</li>" for item in LIMITS)
    page = f'''<!doctype html><html lang="cs"><meta charset="utf-8"><title>Penzijní demo – důkazy</title>
<style>body{{font:17px system-ui;max-width:1050px;margin:40px auto;padding:0 24px;color:#172b4d;background:#f6f8fc}}
section{{background:white;padding:20px;margin:18px 0;border:1px solid #ccd6e4;border-radius:12px}}li{{margin:10px 0}}code{{overflow-wrap:anywhere}}</style>
<h1>Opakovatelné technické demo penzijní platformy</h1><p>Výsledek: <strong>{report["status"]}</strong> · výhradně syntetická data</p>
<p>Revize: <code>{html.escape(report["revision"])}</code> · necommitnuté změny: {report["dirty"]} · {report["createdAt"]}</p>
<p>Výsledek hodnotí níže uvedené testovací scénáře. Není potvrzením kompletního produkčního propojení.</p>
{''.join(sections)}<h2>Rozsah a simulované části</h2><ul>{limitations}</ul>
<p>Backlog: #12479 demo a provoz, #12472 izolace, #12474 výjimky, #12478 migrace, #12475 rozsah produktu.</p></html>'''
    (output / "index.html").write_text(page)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "run"), nargs="?", default="plan")
    args = parser.parse_args()
    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = ROOT / "build" / "pension-demo" / run_id
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    report = {"runId": run_id, "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(), "revision": revision,
              "dirty": dirty, "status": "NOT_RUN", "limitations": LIMITS, "phases": []}
    for phase in phases():
        command = [str(ROOT / "gradlew"), *phase.arguments, "--max-workers=2", "-Dquarkus.http.test-port=0"]
        report["phases"].append({"name": phase.name, "title": phase.title, "status": "NOT_RUN", "command": command})
    render(report, output)
    print(f"Demo report: {output / 'index.html'}", flush=True)
    if args.action == "plan":
        return 0
    lock = (output.parent / ".lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        report["status"] = "FAILED"
        report["error"] = "Another demo is running in this checkout"
        render(report, output)
        print(report["error"], flush=True)
        return 1
    # Keep the lock handle alive until this process exits.
    # Never truncate an existing evidence directory or reset a user's database. Each run is new.
    # A dedicated checkout avoids interference with another Gradle test invocation in that checkout.
    try:
        for phase, result in zip(phases(), report["phases"]):
            evidence = output / phase.name
            evidence.mkdir()
            result["status"] = "RUNNING"
            report["status"] = "RUNNING"
            render(report, output)
            print(f"Running {phase.name} …", flush=True)
            started = time.time()
            with (evidence / "gradle.log").open("w") as log:
                process = subprocess.run(result["command"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            result["durationSeconds"] = round(time.time() - started, 2)
            try:
                result.update(collect(ROOT, phase, started, process.returncode, evidence))
            except (ValueError, ET.ParseError, OSError) as exc:
                result.update(status="FAILED", error=str(exc))
                report["status"] = "FAILED"
                render(report, output)
                print(f"FAILED: {exc}. Log: {evidence / 'gradle.log'}", flush=True)
                return 1
            render(report, output)
        report["status"] = "DEMO_PROOFS_PASSED"
    except (KeyboardInterrupt, OSError) as exc:
        report["status"] = "INTERRUPTED"
        for result in report["phases"]:
            if result["status"] == "RUNNING":
                result.update(status="INTERRUPTED", error=str(exc))
        render(report, output)
        return 1
    render(report, output)
    print(f"DEMO_PROOFS_PASSED: {output / 'index.html'}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
