#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Demo-only switches that disable a regulatory control may be true only where rules.yaml allows.

WHY
---
Three switches turn a regulatory control into a no-op: OPENBANK_AML_AUTO_CLEAR (AML alerts clear
without an analyst), OPENBANK_KYC_AUTO_APPROVE (KYC approves without review) and
LENDING_RISK_ALLOW_DEMONSTRATION_MODEL (placeholder risk parameters). All three are `true` in the
only deployed environment (the sandbox) and every manifest comment says "MUST be false in
production". Nothing enforced it: a future production profile could copy the sandbox manifest and
the control would be off with no signal. Prose is not a control; this is one.

THE ENVIRONMENT MECHANISM
-------------------------
The gitops tree has no per-environment overlay: `openbank-infra/gitops/` IS the sandbox cluster
(its README: "ArgoCD app-of-apps for the sandbox EKS cluster"), and the kustomize overlays are
`k8s/overlays/{sandbox,local}`. So the environment of a manifest is derived from its PATH, using
the `environments:` map in `rules.yaml: demo_only_flags`. A file under a scanned root that matches
no declared environment is `unclassified` and is NOT permitted - a new `gitops-prod/` tree or
`overlays/production/` therefore fails until somebody declares it, which is the point.

WHAT IT CHECKS (one finding class per letter)
---------------------------------------------
  A  a registered flag is truthy (or has an unresolvable source: valueFrom / template) in a
     manifest whose environment is not on the flag's `permitted_environments`
  B  the flag's code default is not false: `application.yaml`, else the `@ConfigProperty`
     `defaultValue`; an absent default is also a finding (nothing proves fail-closed)
  C  the registered flag no longer exists in the owning service's `src/main` (stale entry), or
     its env var does not map to its config property
  D  a profile other than `permitted_profiles` (`%prod`, `application-prod.yaml`, ...) sets it true

Manifests are parsed with the YAML loader and walked structurally (`env:` lists of
`{name, value}`, ConfigMap `data:` maps, compose `environment:` maps and `NAME=value` lists,
kustomize patch values), so a comment that names the flag is not a hit and a nested Rollout is
not missed. A file the loader cannot parse (a template) falls back to a text scan.

Exit: 0 clean, 1 findings, 2 the check could not run (registry malformed, PyYAML missing).
Run:  python3 .github/scripts/check-demo-only-flags.py [--root .] [--self-test|--selftest]
"""
import argparse
import os
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

try:
    import yaml  # noqa: F401
except ImportError:  # pragma: no cover
    yaml = None

TRUTHY = {"true", "1", "yes", "y", "on"}
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", ".terraform", ".next", ".agent-work", "target"}
MANIFEST_EXT = (".yaml", ".yml")
TEXT_EXT = (".properties", ".tf", ".tfvars", ".env")
UNATTRIBUTED_PATCH = "<index-based env patch>"
TEMPLATE_RE = re.compile(r"\$\{|\{\{")


class RegistryError(Exception):
    pass


def truthy(v) -> bool:
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return v == 1
    return isinstance(v, str) and v.strip().strip("'\"").lower() in TRUTHY


def env_name(prop: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", prop).upper()


# ------------------------------------------------------------------ registry
def validate_registry(reg) -> dict:
    if not isinstance(reg, dict):
        raise RegistryError("rules.yaml: demo_only_flags missing or not a mapping")
    envs = reg.get("environments")
    if not isinstance(envs, dict) or not envs:
        raise RegistryError("demo_only_flags.environments must be a non-empty mapping")
    for name, cfg in envs.items():
        paths = (cfg or {}).get("paths")
        if not isinstance(paths, list) or not paths or not all(isinstance(p, str) and p for p in paths):
            raise RegistryError(f"environment {name!r} needs a non-empty paths: list of path prefixes")
    flags = reg.get("flags")
    if not isinstance(flags, list) or not flags:
        raise RegistryError("demo_only_flags.flags must be a non-empty list")
    seen = set()
    for f in flags:
        for key in ("env_var", "service", "config_property", "reason", "permitted_environments"):
            if not (isinstance(f, dict) and f.get(key)):
                raise RegistryError(f"flag entry {f!r} lacks required field {key!r}")
        if f["env_var"] in seen:
            raise RegistryError(f"duplicate flag {f['env_var']}")
        seen.add(f["env_var"])
        unknown = [e for e in f["permitted_environments"] if e not in envs]
        if unknown:
            raise RegistryError(f"{f['env_var']}: permitted_environments {unknown} not declared in environments")
    return reg


def classify(rel: str, envs: dict) -> str:
    for name, cfg in envs.items():
        if any(rel.startswith(p) for p in cfg["paths"]):
            return name
    return "unclassified"


# ------------------------------------------------------------------ manifest scan
def iter_scan_files(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        rel_dir = Path(dirpath).relative_to(root).as_posix()
        top = rel_dir.split("/")[0]
        for fn in filenames:
            rel = (Path(rel_dir) / fn).as_posix() if rel_dir != "." else fn
            low = fn.lower()
            infra = top == "openbank-infra"
            compose = "compose" in low and low.endswith(MANIFEST_EXT)
            envfile = low.startswith(".env") or low.startswith("dockerfile")
            if not (infra or compose or envfile):
                continue
            if low.endswith(MANIFEST_EXT) or low.endswith(TEXT_EXT) or envfile:
                yield rel


def walk(node, names, props, hits, path=""):
    """Collect (name, raw_value, kind) for every setting of a watched name inside a parsed doc."""
    if isinstance(node, dict):
        pth = node.get("path")
        if isinstance(pth, str) and re.search(r"/env/(\d+|-)/value$", pth) and "value" in node:
            # JSON6902 index-based env patch: the flag name is not in the patch, so it cannot be
            # attributed. Fail closed outside permitted environments (UNATTRIBUTED_PATCH).
            hits.append((UNATTRIBUTED_PATCH, node["value"], "patch"))
        nm = node.get("name")
        if isinstance(nm, str) and nm in names:
            if "valueFrom" in node and "value" not in node:
                hits.append((nm, None, "valueFrom"))
            elif "value" in node:
                hits.append((nm, node["value"], "env"))
        for k, v in node.items():
            if isinstance(k, str) and k in names | props and not isinstance(v, (dict, list)):
                hits.append((k, v, "map"))
            walk(v, names, props, hits, f"{path}/{k}")
    elif isinstance(node, list):
        for v in node:
            walk(v, names, props, hits, path)
    elif isinstance(node, str):
        if "\n" in node and (" op:" in node or "op:" in node or "env" in node):
            try:  # kustomize `patches: - patch: |-` carries a YAML/JSON document as a string
                for sub in gatelib.loads_all(node):
                    if isinstance(sub, (dict, list)):
                        walk(sub, names, props, hits, path)
            except Exception:
                pass
        for m in re.finditer(r"(?:^|[\s\-D])([A-Za-z0-9_.\-]+)=(\S*)", node):
            if m.group(1) in names | props:
                hits.append((m.group(1), m.group(2), "assign"))


def scan_file(path: Path, names: set, props: set):
    """Return (hits, parsed_ok). A hit is (name, value, kind)."""
    text = gatelib.read_text(path, errors="replace")
    hits: list = []
    parsed = False
    if path.suffix in MANIFEST_EXT:
        if not any(n in text for n in names | props) and "/env/" not in text:
            return hits, True
        try:
            for doc in gatelib.loads_all(text):
                walk(doc, names, props, hits)
            parsed = True
        except Exception:
            parsed = False
    if not parsed:
        for n in names | props:
            for m in re.finditer(rf"(?<![A-Za-z0-9_.\-]){re.escape(n)}\s*[=:]\s*[\"']?([^\s\"'#]*)", text):
                hits.append((n, m.group(1), "text"))
            for m in re.finditer(rf"name:\s*[\"']?{re.escape(n)}[\"']?\s*\n\s*value:\s*[\"']?([^\s\"'#]*)", text):
                hits.append((n, m.group(1), "text"))
    return hits, parsed


def bad_in_env(value, kind) -> str | None:
    """Why this setting is unacceptable in a NON-permitted environment, or None."""
    if kind == "valueFrom":
        return "sourced from valueFrom (cannot be shown false)"
    if truthy(value):
        return f"set to {value!r}"
    if isinstance(value, str) and TEMPLATE_RE.search(value):
        return f"unresolvable template {value!r}"
    return None


# ------------------------------------------------------------------ code side
def flatten(doc, prefix=""):
    out = {}
    if isinstance(doc, dict):
        for k, v in doc.items():
            out.update(flatten(v, f"{prefix}.{k}" if prefix else str(k)))
    else:
        out[prefix] = doc
    return out


def default_of(raw):
    """(has_default, value) for a yaml scalar that may be `${ENV:default}`."""
    if isinstance(raw, str):
        m = re.fullmatch(r"\s*\$\{[^:}]+(?::([^}]*))?\}\s*", raw)
        if m:
            return (m.group(1) is not None and m.group(1) != ""), m.group(1)
    return True, raw


def split_profile(key: str):
    """'%prod.a.b' -> (['prod'], 'a.b'); '%dev,test.a' -> (['dev','test'], 'a'); 'a.b' -> ([], 'a.b')."""
    m = re.match(r"%([A-Za-z0-9_,\-]+)\.(.*)", key)
    return ([x for x in m.group(1).split(",") if x], m.group(2)) if m else ([], key)


def yaml_settings(path: Path, prop: str):
    """Yield (profile or None, raw value) for every setting of `prop` in one application yaml."""
    for doc in gatelib.load_yaml_all(path):
        for key, val in flatten(doc).items():
            profiles, bare = split_profile(key)
            if bare == prop:
                yield profiles, val


def annotation_defaults(svc_dir: Path, prop: str):
    """Every @ConfigProperty(name = "<prop>") in src/main: a list of defaultValue strings
    ("" for a declaration with none). Empty list => the property is declared nowhere."""
    found = []
    pat = re.compile(r"ConfigProperty\s*\(([^)]*)\)", re.S)
    for kt in sorted(svc_dir.rglob("*.kt")):
        if "/src/main/" not in kt.as_posix():
            continue
        text = gatelib.read_text(kt, errors="replace")
        if prop not in text:
            continue
        for m in pat.finditer(text):
            args = m.group(1)
            if re.search(rf'name\s*=\s*"{re.escape(prop)}"', args):
                d = re.search(r'defaultValue\s*=\s*"([^"]*)"', args)
                found.append(d.group(1) if d else "")
    return found


def _mentions(svc_dir: Path, text_needle: str) -> bool:
    for kt in svc_dir.rglob("*.kt"):
        if "/src/main/" in kt.as_posix() and text_needle in gatelib.read_text(kt, errors="replace"):
            return True
    return False


def code_findings(root: Path, flag: dict, permitted_profiles: set):
    f = []
    name, svc, prop = flag["env_var"], flag["service"], flag["config_property"]
    svc_dir = root / svc
    res = svc_dir / "src/main/resources"
    if env_name(prop) != name:
        f.append(("C", f"{name}: env var does not map to config property {prop} (expected {env_name(prop)})"))
    yaml_files = sorted(res.glob("application*.yaml")) + sorted(res.glob("application*.yml")) if res.is_dir() else []
    base_has = False
    base_default = None
    exists = False
    for yf in yaml_files:
        m = re.match(r"application(?:-([A-Za-z0-9_\-]+))?\.ya?ml$", yf.name)
        file_profile = m.group(1) if m else None
        try:
            settings = list(yaml_settings(yf, prop))
        except Exception as e:  # unreadable config must not read as clean
            f.append(("C", f"{name}: cannot parse {yf.relative_to(root)}: {e}"))
            continue
        for profiles, raw in settings:
            exists = True
            profs = profiles or ([file_profile] if file_profile else [])
            has, val = default_of(raw)
            if not profs:
                base_has, base_default = has, val
                continue
            for prof in profs:  # `%dev,prod.key` carries several profiles: each one is checked
                if prof not in permitted_profiles and truthy(val):
                    f.append(("D", f"{name}: profile %{prof} in {yf.relative_to(root)} sets {prop}={val}"))
    for pf in sorted(res.glob("application*.properties")) if res.is_dir() else []:
        for line in gatelib.read_text(pf, errors="replace").splitlines():
            m = re.match(rf"\s*(?:%([A-Za-z0-9_,\-]+)\.)?{re.escape(prop)}\s*=\s*(.*)", line)
            if m:
                exists = True
                profs = [x for x in (m.group(1) or "").split(",") if x]
                if not profs:
                    base_has, base_default = True, m.group(2).strip()
                for prof in profs:
                    if prof not in permitted_profiles and truthy(m.group(2)):
                        f.append(("D", f"{name}: profile %{prof} in {pf.relative_to(root)} sets {prop}={m.group(2).strip()}"))
    anns = annotation_defaults(svc_dir, prop) if svc_dir.is_dir() else []
    if anns:
        exists = True
    if not svc_dir.is_dir():
        f.append(("C", f"{name}: owning service directory {svc} does not exist"))
        return f
    if not exists and not _mentions(svc_dir, name):
        f.append(("C", f"{name}: stale registry entry - {prop} / {name} not found in {svc}/src/main"))
        return f
    # Every declaration must be an explicit false and they must agree: Quarkus lets whichever
    # injection point is read first win, so one truthy or missing default is enough.
    for d in anns:
        if d == "":
            f.append(("B", f"{name}: a @ConfigProperty({prop}) in {svc} declares no default (must be an explicit false)"))
        elif truthy(d):
            f.append(("B", f"{name}: a @ConfigProperty({prop}) in {svc} defaults to {d!r}, must be false"))
    if len(set(anns)) > 1:
        f.append(("B", f"{name}: @ConfigProperty declarations of {prop} in {svc} disagree on the default: {sorted(set(anns))}"))
    if base_has:
        if base_default is None or base_default == "" or truthy(base_default):
            f.append(("B", f"{name}: application config default of {prop} in {svc} is {base_default!r}, must be false"))
    elif not anns:
        f.append(("B", f"{name}: {svc} declares no default for {prop} (must be an explicit false)"))
    return f


# ------------------------------------------------------------------ driver
def check(root: Path, reg: dict):
    """Return (findings, subjects). A finding is (class, message)."""
    reg = validate_registry(reg)
    envs = reg["environments"]
    permitted_profiles = set(reg.get("permitted_profiles") or ["dev", "test"])
    flags = reg["flags"]
    names = {f["env_var"] for f in flags}
    props = {f["config_property"] for f in flags}
    by_name = {}
    for f in flags:
        by_name[f["env_var"]] = f
        by_name[f["config_property"]] = f
    findings = []
    subjects = 0
    for rel in iter_scan_files(root):
        subjects += 1
        hits, _ = scan_file(root / rel, names, props)
        if not hits:
            continue
        env = classify(rel, envs)
        for nm, val, kind in hits:
            if nm == UNATTRIBUTED_PATCH:
                if truthy(val) or (isinstance(val, str) and TEMPLATE_RE.search(val)):
                    allowed = set.intersection(*(set(f["permitted_environments"]) for f in flags))
                    if env not in allowed:
                        findings.append(("A", f"{rel} (environment {env!r}) JSON6902 patch sets an env value to {val!r}; "
                                              "it cannot be attributed to a flag, so it is refused outside the "
                                              f"environments every registered flag permits {sorted(allowed)}"))
                continue
            flag = by_name[nm]
            if env in flag["permitted_environments"]:
                continue
            why = bad_in_env(val, kind)
            if why:
                findings.append(("A", f"{flag['env_var']}: {rel} (environment {env!r}) {why}; permitted only in "
                                      f"{flag['permitted_environments']}"))
    for flag in flags:
        subjects += 1
        findings.extend(code_findings(root, flag, permitted_profiles))
    return findings, subjects


# ------------------------------------------------------------------ self-test
def _w(root: Path, rel: str, text: str):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


REG = {
    "environments": {
        "sandbox": {"paths": ["openbank-infra/gitops/", "openbank-infra/k8s/overlays/sandbox/"]},
        "local": {"paths": ["openbank-infra/k8s/overlays/local/"]},
    },
    "permitted_profiles": ["dev", "test"],
    "flags": [{
        "env_var": "OPENBANK_X_AUTO_CLEAR", "service": "openbank-x-service",
        "config_property": "openbank.x.auto-clear", "reason": "fixture",
        "permitted_environments": ["sandbox"],
    }],
}
KT = 'class A(@ConfigProperty(name = "openbank.x.auto-clear", defaultValue = "false") val v: Boolean)\n'
ROLLOUT = ("kind: Rollout\nspec:\n  template:\n    spec:\n      containers:\n        - name: app\n          env:\n"
           "            - name: OPENBANK_X_AUTO_CLEAR\n              value: \"%s\"\n")


def _fixture(kt=KT, yaml_text="quarkus:\n  application:\n    name: x\n", manifests=None) -> Path:
    root = Path(tempfile.mkdtemp(prefix="demo-flags-"))
    _w(root, "openbank-x-service/src/main/kotlin/A.kt", kt)
    _w(root, "openbank-x-service/src/main/resources/application.yaml", yaml_text)
    for rel, txt in (manifests or {}).items():
        _w(root, rel, txt)
    return root


def _classes(root):
    return sorted({c for c, _ in check(root, REG)[0]})


def self_test() -> int:
    sand = "openbank-infra/gitops/components/x/x.yaml"
    cases = [  # (label, root, expected finding classes)
        ("clean: true in permitted sandbox, default false", _fixture(manifests={sand: ROLLOUT % "true"}), []),
        ("A+: true in unclassified prod tree", _fixture(manifests={"openbank-infra/gitops-prod/x.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: true in a declared non-permitted env (local)", _fixture(manifests={"openbank-infra/k8s/overlays/local/p.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: valueFrom outside permitted env",
         _fixture(manifests={"openbank-infra/gitops-prod/x.yaml":
                             "env:\n  - name: OPENBANK_X_AUTO_CLEAR\n    valueFrom:\n      configMapKeyRef: {name: c, key: k}\n"}), ["A"]),
        ("A+: compose list form", _fixture(manifests={"docker-compose.prod.yml":
                                                      "services:\n  s:\n    environment:\n      - OPENBANK_X_AUTO_CLEAR=true\n"}), ["A"]),
        ("A+: ConfigMap data map", _fixture(manifests={"openbank-infra/gitops-prod/cm.yaml":
                                                       "kind: ConfigMap\ndata:\n  OPENBANK_X_AUTO_CLEAR: \"true\"\n"}), ["A"]),
        ("A-: false outside permitted env", _fixture(manifests={"openbank-infra/gitops-prod/x.yaml": ROLLOUT % "false"}), []),
        ("A-: only a COMMENT names the flag", _fixture(manifests={"openbank-infra/gitops-prod/x.yaml":
                                                                  "# OPENBANK_X_AUTO_CLEAR=true is sandbox only\nkind: Deployment\n"}), []),
        ("B+: yaml default true", _fixture(yaml_text="openbank:\n  x:\n    auto-clear: ${OPENBANK_X_AUTO_CLEAR:true}\n"), ["B"]),
        ("B+: annotation default true", _fixture(kt=KT.replace('"false"', '"true"')), ["B"]),
        ("B+: no default anywhere", _fixture(kt='class A(@ConfigProperty(name = "openbank.x.auto-clear") val v: Boolean)\n'), ["B"]),
        ("B-: yaml default false", _fixture(yaml_text="openbank:\n  x:\n    auto-clear: ${OPENBANK_X_AUTO_CLEAR:false}\n"), []),
        ("C+: flag gone from code", _fixture(kt="class A\n"), ["C"]),
        ("C+: env var does not map to property",
         _fixture(kt=KT.replace("auto-clear", "auto-wipe")), ["C"]),
        ("C-: present via annotation only", _fixture(), []),
        ("D+: %prod nested sets true", _fixture(yaml_text="'%prod':\n  openbank:\n    x:\n      auto-clear: true\n"), ["D"]),
        ("D+: %prod dotted key sets true", _fixture(yaml_text="openbank:\n  x:\n    auto-clear: false\n'%prod.openbank.x.auto-clear': 'true'\n"), ["D"]),
        ("D+: application-prod.yaml sets true",
         (lambda r: (_w(r, "openbank-x-service/src/main/resources/application-prod.yaml",
                        "openbank:\n  x:\n    auto-clear: true\n"), r)[1])(_fixture()), ["D"]),
        ("B+: second @ConfigProperty declaration defaults true",
         _fixture(kt=KT + KT.replace("class A", "class B").replace('"false"', '"true"')), ["B"]),
        ("B+: two declarations disagree (one without default)",
         _fixture(kt=KT + 'class B(@ConfigProperty(name = "openbank.x.auto-clear") val v: Boolean)\n'), ["B"]),
        ("B-: two declarations both false", _fixture(kt=KT + KT.replace("class A", "class B")), []),
        ("D+: multi-profile key %dev,prod", _fixture(yaml_text="'%dev,prod.openbank.x.auto-clear': 'true'\n"), ["D"]),
        ("D-: multi-profile key %dev,test", _fixture(yaml_text="'%dev,test.openbank.x.auto-clear': 'true'\n"), []),
        ("A+: JSON6902 index patch sets env true outside permitted env",
         _fixture(manifests={"openbank-infra/k8s/overlays/local/p.yaml":
                             "- op: replace\n  path: /spec/template/spec/containers/0/env/3/value\n  value: \"true\"\n"}), ["A"]),
        ("A+: JSON6902 patch embedded as kustomize patch string",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml":
                             "patches:\n  - patch: |-\n      - op: replace\n        path: /spec/template/spec/containers/0/env/3/value\n        value: \"true\"\n"}), ["A"]),
        ("A-: JSON6902 index patch in the permitted environment",
         _fixture(manifests={"openbank-infra/gitops/p.yaml":
                             "- op: replace\n  path: /spec/template/spec/containers/0/env/3/value\n  value: \"true\"\n"}), []),
        ("D-: %dev sets true", _fixture(yaml_text="'%dev':\n  openbank:\n    x:\n      auto-clear: true\n"), []),
        ("D-: %prod sets false", _fixture(yaml_text="'%prod':\n  openbank:\n    x:\n      auto-clear: false\n"), []),
    ]
    bad = 0
    for label, root, want in cases:
        got = _classes(root)
        ok = got == want
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {label}: got {got} want {want}")
    # the registry itself must be refused when malformed (exit 2 path)
    try:
        validate_registry({"environments": {"sandbox": {"paths": ["a/"]}}, "flags": [
            {"env_var": "X", "service": "s", "config_property": "p", "reason": "r", "permitted_environments": ["prod"]}]})
        print("  FAIL registry naming an undeclared environment was accepted")
        bad += 1
    except RegistryError:
        print("  ok   registry naming an undeclared environment is refused")
    print("self-test:", "FAILED" if bad else "PASS")
    return 1 if bad else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", "--selftest", dest="self_test", action="store_true")
    a = ap.parse_args(argv)
    if yaml is None:
        print("PyYAML missing", file=sys.stderr)
        return 2
    if a.self_test:
        return self_test()
    root = Path(a.root).resolve()
    try:
        reg = gatelib.load_yaml(root / "openbank-libs/governance/rules.yaml").get("demo_only_flags")
        findings, subjects = check(root, reg)
    except (RegistryError, OSError) as e:
        print(f"::error::demo-only-flags cannot run: {e}")
        return 2
    gatelib.subjects(subjects, "files scanned + registered flags")
    for cls, msg in findings:
        print(f"::error::[{cls}] {msg}")
    if findings:
        print(f"demo-only-flags: {len(findings)} finding(s). A switch that disables a regulatory control may be "
              "true only in an environment listed in rules.yaml: demo_only_flags.")
        return 1
    print(f"demo-only-flags: OK ({len(reg['flags'])} flags, {subjects} subjects)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
