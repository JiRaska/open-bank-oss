#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every bank-only scheduler and outbound edge is OFF on a non-bank ledger-service instance.

ADR-0337 runs a second deployment of openbank-ledger-service for the pension company
(`openbank-infra/gitops/components/ledger-pension-co`). The image is the bank's, so every
scheduler, outbox dispatcher, Kafka channel and outbound REST client in it would, by default,
act on the company's books exactly as it acts on the bank's: publish journal postings onto the
bank's `openbank.ledger.journal.posted` topic, tie out deposit-control accounts the company does
not have, revalue FX against the bank's fixing, and page on-call about all of it.

The switch list is DERIVED from the ledger source, never typed here, so a scheduler added to
ledger-service later fails this check until the instance switches it off (or it is declared
entity-local below, with a reason):

  * every `@Scheduled(...)`: its trigger must be a config expression (`cron = "{p:...}"` /
    `every = "${p:...}"`) whose EFFECTIVE value on the instance is `off`/`disabled` — Quarkus never
    runs such a method. A literal trigger cannot be switched off by configuration at all and is a
    finding unless listed in LOCAL_ONLY;
  * every `@ConfigProperty(name = "...enabled...")` boolean: effective value must be false;
  * every `mp.messaging.outgoing.<ch>` channel: no Kafka bootstrap/credentials on the instance and
    the topic overridden away from the bank's;
  * every `mp.messaging.incoming.<ch>` channel: `MP_MESSAGING_INCOMING_<CH>_ENABLED=false`;
  * every `quarkus.rest-client.<key>.url: ${ENV:...}`: ENV must not be set (no bank dependency);
  * any Temporal worker (`io.temporal`) in the source is a finding — none has a switch yet.

EFFECTIVE value = the Rollout container env (SmallRye env-name mapping) if present, else the
`application.yaml` value (with `${ENV:default}` resolved against the env), else the annotation's
`defaultValue`.

  python3 .github/scripts/check-ledger-instance-switches.py            # check the instances
  python3 .github/scripts/check-ledger-instance-switches.py --self-test
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

SERVICE = "openbank-ledger-service"
# Non-bank instances of ledger-service: component dir -> Rollout manifest (ADR-0337).
INSTANCES = {"ledger-pension-co": "ledger-service.yaml"}
CONTAINER = "ledger-service"

# Schedulers that act only on the instance's OWN database and reach nothing outside it.
# Key: "<ClassName>.<method>". A stale entry (method gone) is a finding.
LOCAL_ONLY = {
    "JournalPartitionMaintainer.maintain":
        "creates next years' journal_entries partitions in the instance's own DB; retention is "
        "detach-only and dry-run by default (drop-enabled is checked false below)",
    "LedgerOutboxBacklogGauge.refresh":
        "reads the instance's own outbox row count into a gauge; publishes nothing",
}

OFF_VALUES = {"off", "disabled"}

SCHEDULED_RE = re.compile(r"@Scheduled\((?P<args>[^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:suspend\s+)?fun\s+(?P<fn>\w+)", re.S)
TRIGGER_RE = re.compile(r'\b(?P<kind>cron|every)\s*=\s*"(?P<val>[^"]*)"')
EXPR_RE = re.compile(r"^\\?\$?\{(?P<prop>[^:}]+)(?::(?P<default>[^}]*))?\}$")
CLASS_RE = re.compile(r"^\s*class\s+(\w+)", re.M)
ENABLED_PROP_RE = re.compile(
    r'@ConfigProperty\(\s*name\s*=\s*"(?P<prop>[^"]*enabled[^"]*)"(?:\s*,\s*defaultValue\s*=\s*"(?P<default>[^"]*)")?')
PLACEHOLDER_RE = re.compile(r"^\$\{(?P<env>[A-Za-z0-9_.-]+)(?::(?P<default>.*))?\}$")


def env_name(prop: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", prop).upper()


def flatten(node, prefix="") -> dict[str, object]:
    out: dict[str, object] = {}
    if isinstance(node, dict):
        for k, v in node.items():
            key = f"{prefix}.{k}" if prefix else str(k)
            if isinstance(v, dict):
                out.update(flatten(v, key))
            else:
                out[key] = v
    return out


def load_app_config(service_dir: pathlib.Path) -> dict[str, object]:
    doc = yaml.safe_load((service_dir / "src/main/resources/application.yaml").read_text()) or {}
    # profile blocks ("%test", "%dev", "%prod") are dropped; the instance runs prod, whose
    # overrides in this service are HTTP/TLS only — none touches a switch derived here.
    return {k: v for k, v in flatten(doc).items() if not k.startswith("%")}


def effective(prop: str, env: dict[str, str], app: dict[str, object], default: str | None) -> str | None:
    if env_name(prop) in env:
        return env[env_name(prop)]
    if prop in app:
        raw = str(app[prop]).strip()
        m = PLACEHOLDER_RE.match(raw)
        if m:
            return env.get(m.group("env"), m.group("default"))
        return raw
    return default


def scheduled_methods(src: pathlib.Path):
    for f in sorted(src.rglob("*.kt")):
        text = f.read_text()
        cls = CLASS_RE.search(text)
        for m in SCHEDULED_RE.finditer(text):
            yield f, (cls.group(1) if cls else f.stem), m.group("fn"), m.group("args")


def container_env(manifest: pathlib.Path) -> tuple[dict[str, str], list[dict]]:
    for doc in yaml.safe_load_all(manifest.read_text()):
        if not doc or doc.get("kind") not in {"Rollout", "Deployment"}:
            continue
        spec = doc["spec"]["template"]["spec"]
        for c in spec.get("containers", []):
            if c.get("name") == CONTAINER:
                env = {e["name"]: str(e.get("value")) if "value" in e else "<secretRef>" for e in c.get("env", [])}
                return env, spec.get("volumes", [])
    raise SystemExit(f"::error::{manifest}: no Rollout/Deployment container '{CONTAINER}' — cannot check")


def check(root: pathlib.Path) -> tuple[list[str], int]:
    findings: list[str] = []
    svc = root / SERVICE
    src = svc / "src/main/kotlin"
    app = load_app_config(svc)
    subjects = 0
    for inst, manifest_name in INSTANCES.items():
        manifest = root / "openbank-infra/gitops/components" / inst / manifest_name
        env, volumes = container_env(manifest)
        where = f"{inst}/{manifest_name}"

        seen_local: set[str] = set()
        for f, cls, fn, args in scheduled_methods(src):
            subjects += 1
            key = f"{cls}.{fn}"
            if key in LOCAL_ONLY:
                seen_local.add(key)
                continue
            trig = TRIGGER_RE.search(args)
            expr = EXPR_RE.match(trig.group("val")) if trig else None
            if not expr:
                findings.append(f"{where}: @Scheduled {key} ({f.name}) has a literal trigger "
                                f"{trig.group(0) if trig else '(none)'} — it cannot be switched off on this "
                                f"instance. Make it a config expression or declare it LOCAL_ONLY with a reason.")
                continue
            prop = expr.group("prop").lstrip("$")
            val = effective(prop, env, app, expr.group("default"))
            if (val or "").strip().lower() not in OFF_VALUES:
                findings.append(f"{where}: @Scheduled {key} runs ({prop} = {val!r}); set {env_name(prop)}=off")
        for key in sorted(set(LOCAL_ONLY) - seen_local):
            findings.append(f"STALE LOCAL_ONLY entry {key}: no such @Scheduled method in {SERVICE}")

        for f in sorted(src.rglob("*.kt")):
            for m in ENABLED_PROP_RE.finditer(f.read_text()):
                subjects += 1
                prop = m.group("prop")
                val = effective(prop, env, app, m.group("default"))
                if (val or "").strip().lower() != "false":
                    findings.append(f"{where}: switch {prop} ({f.name}) is {val!r}; set {env_name(prop)}=\"false\"")

        for direction in ("outgoing", "incoming"):
            channels = sorted({k.split(".")[3] for k in app if k.startswith(f"mp.messaging.{direction}.")})
            for ch in channels:
                subjects += 1
                if direction == "incoming":
                    if env.get(env_name(f"mp.messaging.incoming.{ch}.enabled"), "").lower() != "false":
                        findings.append(f"{where}: incoming channel {ch} consumes; set "
                                        f"{env_name(f'mp.messaging.incoming.{ch}.enabled')}=\"false\"")
                    continue
                bank_topic = str(app.get(f"mp.messaging.outgoing.{ch}.topic", ch))
                topic = env.get(env_name(f"mp.messaging.outgoing.{ch}.topic"), bank_topic)
                if topic == bank_topic:
                    findings.append(f"{where}: outgoing channel {ch} still targets the bank topic {bank_topic}")
        if any(k.startswith("mp.messaging.outgoing.") for k in app):
            for k in ("KAFKA_BOOTSTRAP_SERVERS", "KAFKA_SSL_KEYSTORE_LOCATION", "KAFKA_SECURITY_PROTOCOL"):
                if k in env:
                    findings.append(f"{where}: {k} is set — the instance must have no route to the bank's Kafka")
            for v in volumes:
                if "kafka" in v.get("name", "") or "kafka" in str(v.get("secret", {}).get("secretName", "")):
                    findings.append(f"{where}: volume {v.get('name')} mounts Kafka credentials")

        for k, v in app.items():
            m = re.match(r"quarkus\.rest-client\.([^.]+)\.url$", k)
            if not m:
                continue
            subjects += 1
            ph = PLACEHOLDER_RE.match(str(v).strip())
            if ph and ph.group("env") in env:
                findings.append(f"{where}: outbound REST client {m.group(1)} is wired ({ph.group('env')}) — "
                                f"a bank dependency on a non-bank instance")

        for f in sorted(src.rglob("*.kt")):
            if "io.temporal" in f.read_text():
                findings.append(f"{where}: {f.name} registers a Temporal worker; no switch exists for it")
    return findings, subjects


# ── self-test: a minimal ledger source + instance manifest, mutated case by case ──────────────
_SRC = {
    "Tie.kt": 'class Tie {\n  @Scheduled(cron = "{openbank.x.tie.cron:0 0 6 * * ?}")\n  suspend fun run() {}\n}\n',
    "Part.kt": 'class JournalPartitionMaintainer {\n  @Scheduled(every = "24h")\n  suspend fun maintain() {}\n}\n',
    "Gauge.kt": 'class LedgerOutboxBacklogGauge {\n  @Scheduled(every = "10s")\n  suspend fun refresh() {}\n}\n',
    "Out.kt": 'class Out(@ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false") val e: Boolean)\n',
}
_APP = {"openbank": {"outbox": {"dispatch-enabled": True}},
        "mp": {"messaging": {"outgoing": {"ev-out": {"topic": "bank.topic"}}}},
        "quarkus": {"rest-client": {"fx": {"url": "${FX_URL:http://localhost}"}}}}
_GOOD_ENV = {"OPENBANK_X_TIE_CRON": "off", "OPENBANK_OUTBOX_DISPATCH_ENABLED": "false",
             "MP_MESSAGING_OUTGOING_EV_OUT_TOPIC": "other.topic"}


def _fixture(tmp: pathlib.Path, src=None, env=None, app=None) -> pathlib.Path:
    k = tmp / SERVICE / "src/main/kotlin"
    k.mkdir(parents=True)
    for n, t in (src if src is not None else _SRC).items():
        (k / n).write_text(t)
    (tmp / SERVICE / "src/main/resources").mkdir(parents=True)
    (tmp / SERVICE / "src/main/resources/application.yaml").write_text(yaml.safe_dump(app or _APP))
    for inst, name in INSTANCES.items():
        d = tmp / "openbank-infra/gitops/components" / inst
        d.mkdir(parents=True)
        e = [{"name": n, "value": v} for n, v in (env if env is not None else _GOOD_ENV).items()]
        doc = {"kind": "Rollout", "spec": {"template": {"spec": {"containers": [{"name": CONTAINER, "env": e}]}}}}
        (d / name).write_text(yaml.safe_dump(doc))
    return tmp


def self_test() -> int:
    cases = [
        ("all switches off", {}, 0),
        ("scheduler flipped on (negative case)", {"env": {**_GOOD_ENV, "OPENBANK_X_TIE_CRON": "0 0 6 * * ?"}}, 1),
        ("scheduler switch absent -> annotation default runs", {"env": {k: v for k, v in _GOOD_ENV.items() if k != "OPENBANK_X_TIE_CRON"}}, 1),
        ("outbox dispatch flipped on", {"env": {**_GOOD_ENV, "OPENBANK_OUTBOX_DISPATCH_ENABLED": "true"}}, 1),
        ("new scheduler with a literal trigger", {"src": {**_SRC, "New.kt": 'class New {\n  @Scheduled(every = "1h")\n  fun go() {}\n}\n'}}, 1),
        ("new scheduler with an unset expression", {"src": {**_SRC, "New.kt": 'class New {\n  @Scheduled(cron = "{a.b.cron:0 0 * * * ?}")\n  fun go() {}\n}\n'}}, 1),
        ("bank topic kept", {"env": {k: v for k, v in _GOOD_ENV.items() if "TOPIC" not in k}}, 1),
        ("kafka bootstrap wired", {"env": {**_GOOD_ENV, "KAFKA_BOOTSTRAP_SERVERS": "bus:9093"}}, 1),
        ("bank REST dependency wired", {"env": {**_GOOD_ENV, "FX_URL": "http://fx"}}, 1),
        ("stale LOCAL_ONLY entry", {"src": {k: v for k, v in _SRC.items() if k != "Gauge.kt"}}, 1),
        ("temporal worker", {"src": {**_SRC, "W.kt": "import io.temporal.worker.WorkerFactory\n"}}, 1),
    ]
    failures = 0
    for label, kw, want in cases:
        with tempfile.TemporaryDirectory() as t:
            got, _ = check(_fixture(pathlib.Path(t), **kw))
        ok = len(got) == want
        failures += 0 if ok else 1
        print(f"{'pass' if ok else 'FAIL'}  {label}: {len(got)} finding(s), want {want}" + ("" if ok else f" {got}"))
    print(f"\nself-test: {len(cases) - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", default=".")
    p.add_argument("--self-test", action="store_true")
    a = p.parse_args()
    if a.self_test:
        return self_test()
    findings, subjects = check(pathlib.Path(a.root))
    gatelib.subjects(subjects, "ledger switches derived from source x instances")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"{len(findings)} bank-only switch(es) live on a non-bank ledger instance (ADR-0337).")
        return 1
    print(f"OK: {subjects} derived switch(es) all off on {', '.join(INSTANCES)}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
