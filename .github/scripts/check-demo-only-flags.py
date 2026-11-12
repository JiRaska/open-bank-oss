#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Demo-only switches that disable a control may be true only where rules.yaml allows.

WHY
---
Some switches turn a control into a no-op: OPENBANK_AML_AUTO_CLEAR (AML alerts clear without an
analyst), OPENBANK_KYC_AUTO_APPROVE (KYC approves without review),
LENDING_RISK_ALLOW_DEMONSTRATION_MODEL (placeholder risk parameters) and the others in
`rules.yaml: demo_only_flags.flags`. The first three are `true` in the only deployed environment
(the sandbox) and every manifest comment says "MUST be false in production". Nothing enforced it.

THE REGISTRY IS CURATED AND PARTIAL
-----------------------------------
The flag list is a hand-kept set of switches somebody found and judged to weaken a control. The
gate does NOT discover demo/stub switches: one that is not listed is not checked. Adding a flag is
a review decision. (Judged and left out on purpose: QUARKUS_MAILER_MOCK, an environment fact whose
effect is recorded honestly as SUPPRESSED/mailer_mocked; the KYB single-member auto-confirm, a
designed-on rule with an audit trail, default true.)

THE ENVIRONMENT MECHANISM
-------------------------
The gitops tree has no per-environment overlay: `openbank-infra/gitops/` IS the sandbox cluster, and
the kustomize overlays are `k8s/overlays/{sandbox,local}`. The environment of a file is derived from
its PATH (`environments:` in the rules entry), in this order:
  1. a path with a non-sandbox TOKEN in any segment (prod, production, prd, live, staging, stage,
     uat, preprod; a segment is split at - _ .) is non-sandbox and permitted nowhere, even under a
     permitted root (`openbank-infra/gitops/environments/production/`, `values-prod.yaml`);
  2. else a declared environment's path prefix;
  3. else `unclassified`, which is not permitted either.
So a new tree fails until somebody declares it. This is a path heuristic, not knowledge of where a
file is deployed: a production environment whose path carries none of the tokens and that sits under
a permitted root is classified sandbox (see Known limits).

WHAT IT CHECKS (one finding class per letter)
---------------------------------------------
  A  a registered flag (under its manifest env var, the env var derived from the property, or the
     property itself) is truthy, or has an unresolvable source (valueFrom / template), in a file
     whose environment is not on the flag's `permitted_environments`
  B  the flag's code default is not false: `application.yaml`, else the `@ConfigProperty`
     `defaultValue`; an absent default is also a finding (nothing proves fail-closed)
  C  the registered flag no longer exists in the owning service's `src/main` (stale entry), or its
     env var neither maps to the property nor is routed into it by a `${ENV:...}` in the yaml
  D  a profile other than `permitted_profiles` (`%prod`, `application-prod.yaml`, ...) sets it true

WHAT IT SCANS
-------------
Every yaml/yml/properties/tf/tfvars file, `.env*`, `Dockerfile*` and bare `Kustomization` in the repo
outside `.git`, `node_modules`, build output and `src/test/` trees. Files are parsed with the YAML
loader and walked: `env:` lists of `{name, value}`, ConfigMap `data:` maps, compose `environment:`,
kustomize patch values (including inline `patchesStrategicMerge` entries), and option strings are
tokenised (`JAVA_OPTS: "-Xmx1g -Dopenbank.x=true"`). A string value under a `*.yaml` / `*.yml` /
`*.properties` / `*.env` / `*.conf` key (`application.yaml: |`, `override.properties: |`) is parsed as
that config. `envFrom: configMapRef` is resolved against ConfigMaps (and kustomize
`configMapGenerator` entries, their `literals`, `envs` and `files`) defined in the repo, and the
referenced content is judged in the environment of the referrer as well. A file that does not parse
falls back to a text scan.

KNOWN LIMITS (not closed; each is a way to miss a flag)
-------------------------------------------------------
  - The registry is curated and partial (above).
  - Environment is a path heuristic. A production tree under a permitted root with no non-sandbox
    token in its path is read as sandbox.
  - Helm charts are not rendered: `{{ }}` templates are unresolved, and a value injected from a
    values file the chart does not name in a way this scan can see is not followed.
  - `kustomize build` is not run: patches that need rendering, remote bases, `replacements`,
    `vars`, components and `images`-style transformers are not applied.
  - `envFrom` / `configMapKeyRef` / `secretRef` naming an object that is not in this repo (or a
    Secret, or a name built by a generator suffix) cannot be resolved and is not guessed; the run
    prints a NOTE with the count of ConfigMap names it could not follow.
  - A flag set at runtime (kubectl, operator, ArgoCD parameter override, Terraform variable fed
    from outside the repo, a secret store) is invisible.
  - `src/test/` trees are not scanned (not deployed); a manifest placed there is not checked.
  - A kustomization whose patch is JSON6902 touching `env`/`envFrom` is refused outside permitted
    environments because the flag cannot be attributed, not because it was shown to be true.
  - Base64 `binaryData`, and config embedded in a string under a key without a config-file suffix,
    are not decoded or parsed.

Exit: 0 clean, 1 findings, 2 the check could not run (registry malformed, PyYAML missing).
Run:  python3 .github/scripts/check-demo-only-flags.py [--root .] [--self-test|--selftest]
"""
import argparse
import os
import re
import shlex
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
ENV_PATH_RE = re.compile(r"/env(From)?(/(\d+|-))?(/|$)")
OP_TEXT_RE = re.compile(r"""["']?\bop\b["']?\s*:""")
TEMPLATE_RE = re.compile(r"\$\{|\{\{")
# Path TOKENS (a path segment split on - _ .) that name a non-sandbox environment. A path carrying
# one is never sandbox and never permitted, wherever it sits (rules.yaml: demo_only_flags.non_sandbox_path_tokens
# overrides this default).
DEFAULT_NON_SANDBOX_TOKENS = ("prod", "production", "prd", "live", "staging", "stage", "uat", "preprod")
KUSTOMIZATION_NAMES = ("kustomization.yaml", "kustomization.yml", "kustomization")
CONFIG_PAYLOAD_KEY_RE = re.compile(r"\.(ya?ml|properties|env|conf)$", re.I)
ENVFROM_REF = "<envFrom/configMapKeyRef>"


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
    tokens = reg.get("non_sandbox_path_tokens", list(DEFAULT_NON_SANDBOX_TOKENS))
    if not isinstance(tokens, list) or not tokens or not all(isinstance(t, str) and t for t in tokens):
        raise RegistryError("demo_only_flags.non_sandbox_path_tokens must be a non-empty list of strings")
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


def path_tokens(rel: str) -> set:
    return {t for seg in rel.lower().split("/") for t in re.split(r"[-_.]", seg) if t}


def classify(rel: str, envs: dict, non_sandbox=DEFAULT_NON_SANDBOX_TOKENS) -> str:
    """Environment of a path. A path with a non-sandbox token (prod, uat, ...) in ANY segment is
    `non-sandbox:<token>` and permitted nowhere, even inside a declared environment's root."""
    hit = sorted(path_tokens(rel) & set(non_sandbox))
    if hit:
        return f"non-sandbox:{hit[0]}"
    for name, cfg in envs.items():
        if any(rel.startswith(p) for p in cfg["paths"]):
            return name
    return "unclassified"


# ------------------------------------------------------------------ manifest scan
def iter_scan_files(root: Path):
    """Every manifest-shaped file in the repo (yaml/yml/json/properties/tf/tfvars, `.env*`,
    `Dockerfile*`, a bare `Kustomization`), outside SKIP_DIRS and `src/test/` trees. The environment
    of each is decided by classify(); nothing outside a declared environment is permitted."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        rel_dir = Path(dirpath).relative_to(root).as_posix()
        if "/src/test/" in f"/{rel_dir}/" or rel_dir.endswith("/src/test"):
            dirnames[:] = []
            continue
        for fn in filenames:
            rel = (Path(rel_dir) / fn).as_posix() if rel_dir != "." else fn
            low = fn.lower()
            envfile = low.startswith(".env") or low.startswith("dockerfile") or low in KUSTOMIZATION_NAMES
            if low.endswith(MANIFEST_EXT) or low.endswith(TEXT_EXT) or envfile:
                yield rel


def embedded(text, names, props, hits, path, force):
    """Parse a string scalar that may carry a kustomize patch (YAML or JSON). A string that looks
    like a patch but does not parse is refused outside permitted environments (fail closed)."""
    looks = force or ("/env/" in text and OP_TEXT_RE.search(text))
    try:
        subs = gatelib.loads_all(text)
    except Exception:
        if looks:
            hits.append((UNATTRIBUTED_PATCH, None, "unparsable"))
        return
    for sub in subs:
        if isinstance(sub, (dict, list)):
            walk(sub, names, props, hits, path)
        elif force and sub is not None:
            hits.append((UNATTRIBUTED_PATCH, None, "unparsable"))


def option_tokens(text: str):
    """Split an option/command string ("-Xmx1g -Dopenbank.x=true", `export A=b`, a shell line) into
    tokens, tolerating unbalanced quotes. A -D / -- prefix is stripped from each token."""
    try:
        toks = shlex.split(text, posix=True)
    except ValueError:
        toks = text.split()
    for t in toks:
        yield re.sub(r"^(-D|--)", "", t)


def properties_hits(text, names, props, hits, kind):
    """`key=value` / `key: value` lines of a properties-style payload; a `%profile.` prefix is
    stripped (a manifest that sets `%prod.x=true` sets it for that profile, which D/A both refuse)."""
    for line in text.splitlines():
        m = re.match(r"\s*(?:export\s+)?(?:%[A-Za-z0-9_,\-]+\.)?([A-Za-z0-9_.\-]+)\s*[=:]\s*(.*?)\s*$", line)
        if m and not line.lstrip().startswith("#") and m.group(1) in names | props:
            hits.append((m.group(1), m.group(2).strip("\"'"), kind))


def embedded_config(key, text, names, props, hits, path):
    """A string value that is itself an application config (`application.yaml: |`,
    `override.properties: |`, a `.env` payload)."""
    if key.lower().endswith((".yaml", ".yml")):
        try:
            docs = gatelib.loads_all(text)
        except Exception:
            properties_hits(text, names, props, hits, "embedded")  # unparsable: still read as lines
            return
        for doc in docs:
            if isinstance(doc, dict):
                for fk, fv in flatten(doc).items():
                    bare = split_profile(fk)[1]
                    if bare in names | props and not isinstance(fv, (dict, list)):
                        hits.append((bare, fv, "embedded"))
                walk(doc, names, props, hits, path)
    else:
        properties_hits(text, names, props, hits, "embedded")


def kustomization_refs(root: Path, rel: str):
    """What a kustomization points at: ({path: mode}, [generated ConfigMap names]). Mode `patch`
    (patches / patchesJson6902 / patchesStrategicMerge file entries) or `env` (configMapGenerator /
    secretGenerator `envs:` and `files:`), any extension, inside or outside the scanned set."""
    out: dict = {}
    gens: list = []
    try:
        docs = gatelib.loads_all(gatelib.read_text(root / rel, errors="replace"))
    except Exception:
        return out, gens
    base = (root / rel).parent
    root_r = root.resolve()

    def add(ref, mode):
        if isinstance(ref, str) and "\n" not in ref and ref.strip():
            f = (base / ref.strip().split("=", 1)[-1]).resolve()  # `files:` may be `key=path`
            try:
                r = f.relative_to(root_r).as_posix()
            except ValueError:
                return
            if f.is_file():
                out[r] = mode

    for doc in docs:
        if not isinstance(doc, dict):
            continue
        for key in ("patches", "patchesJson6902", "patchesStrategicMerge"):
            for ent in doc.get(key) or []:
                add(ent.get("path") if isinstance(ent, dict) else ent, "patch")
        for gkey in ("configMapGenerator", "secretGenerator"):
            for g in doc.get(gkey) or []:
                if not isinstance(g, dict):
                    continue
                if gkey == "configMapGenerator" and isinstance(g.get("name"), str):
                    gens.append(g["name"])
                for ekey in ("envs", "env", "files"):
                    val = g.get(ekey)
                    for ref in ([val] if isinstance(val, str) else val or []):
                        add(ref, "env")
    return out, gens


def walk(node, names, props, hits, path=""):
    """Collect (name, raw_value, kind) for every setting of a watched name inside a parsed doc."""
    if isinstance(node, dict):
        for ref_key in ("path", "from"):
            ref = node.get(ref_key)
            if isinstance(ref, str) and ENV_PATH_RE.search(ref):
                # JSON6902 op of ANY kind touching env / envFrom: the flag name is not in the patch,
                # so it cannot be attributed. Refused outside permitted environments whatever its
                # value (UNATTRIBUTED_PATCH).
                hits.append((UNATTRIBUTED_PATCH, node.get("value"), "patch"))
        nm = node.get("name")
        if isinstance(nm, str) and nm in names:
            if "valueFrom" in node and "value" not in node:
                hits.append((nm, None, "valueFrom"))
            elif "value" in node:
                hits.append((nm, node["value"], "env"))
        for k, v in node.items():
            if isinstance(k, str) and k in names | props and not isinstance(v, (dict, list)):
                hits.append((k, v, "map"))
            if k == "patch" and isinstance(v, str):
                embedded(v, names, props, hits, path, force=True)
            if k == "patchesStrategicMerge" and isinstance(v, list):
                for ent in v:  # an inline entry is patch CONTENT, a path entry is one line
                    if isinstance(ent, str) and "\n" in ent:
                        embedded(ent, names, props, hits, path, force=False)
            if isinstance(k, str) and isinstance(v, str) and CONFIG_PAYLOAD_KEY_RE.search(k):
                embedded_config(k, v, names, props, hits, path)
            if k == "envFrom" and isinstance(v, list):
                for ent in v:
                    ref = ent.get("configMapRef") if isinstance(ent, dict) else None
                    if isinstance(ref, dict) and isinstance(ref.get("name"), str):
                        hits.append((ENVFROM_REF, ref["name"], "envFrom"))
            walk(v, names, props, hits, f"{path}/{k}")
    elif isinstance(node, list):
        for v in node:
            walk(v, names, props, hits, path)
    elif isinstance(node, str):
        if "/env/" in node or OP_TEXT_RE.search(node):
            embedded(node, names, props, hits, path, force=False)
        for tok in option_tokens(node):
            m = re.fullmatch(r"([A-Za-z0-9_.\-]+)=(.*)", tok, re.S)
            if m and m.group(1) in names | props:
                hits.append((m.group(1), m.group(2), "assign"))


def scan_file(path: Path, names: set, props: set, mode: str = ""):
    """Return (hits, docs or None when not parsed). A hit is (name, value, kind). A file reached through a
    kustomization `patch` reference is always parsed and walked, and failing to parse is a hit; an
    `env` reference (configMapGenerator envs/files) is read as NAME=value lines."""
    text = gatelib.read_text(path, errors="replace")
    hits: list = []
    docs = None
    suffix = path.suffix.lower()
    if mode == "env":
        properties_hits(text, names, props, hits, "envfile")
        return hits, None
    if mode == "patch" or suffix in MANIFEST_EXT or path.name.lower() == "kustomization":
        # No raw-text prefilter: escaped text ("\\u0043LEAR") defeats a substring check.
        try:
            docs = gatelib.loads_all(text)
            for doc in docs:
                walk(doc, names, props, hits)
        except Exception:
            docs = None
            if mode == "patch":
                hits.append((UNATTRIBUTED_PATCH, None, "unparsable"))
    if docs is None:
        dflag = r"(?:(?<![A-Za-z0-9_.\-])|(?<=-D))"
        for n in names | props:
            for m in re.finditer(rf"{dflag}{re.escape(n)}\s*[=:]\s*[\"']?([^\s\"'#]*)", text):
                hits.append((n, m.group(1), "text"))
            for m in re.finditer(rf"name:\s*[\"']?{re.escape(n)}[\"']?\s*\n\s*value:\s*[\"']?([^\s\"'#]*)", text):
                hits.append((n, m.group(1), "text"))
            for m in re.finditer(rf"^\s*ENV\s+{re.escape(n)}\s+[\"']?([^\s\"'#]*)", text, re.M):
                hits.append((n, m.group(1), "text"))
    return hits, docs


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
    alias_ok = env_name(prop) == name
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
            if isinstance(raw, str) and re.search(r"\$\{" + re.escape(name) + r"[:}]", raw):
                alias_ok = True  # the yaml routes this env var into the property
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
    if not alias_ok:
        f.append(("C", f"{name}: env var does not map to config property {prop} (expected {env_name(prop)} "
                       f"or a `${{{name}:...}}` reference in {svc}'s application yaml)"))
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
    # A flag is reachable under its manifest env var AND under the env var Quarkus derives from
    # the property name, so both are watched.
    names = {f["env_var"] for f in flags} | {env_name(f["config_property"]) for f in flags}
    props = {f["config_property"] for f in flags}
    by_name = {}
    for f in flags:
        by_name[f["env_var"]] = f
        by_name[env_name(f["config_property"])] = f
        by_name[f["config_property"]] = f
    findings = []
    subjects = 0
    non_sandbox = tuple(reg.get("non_sandbox_path_tokens") or DEFAULT_NON_SANDBOX_TOKENS)
    # contexts[rel] = every path whose environment must permit what the file does: the file
    # itself and, for a referenced patch / generator input / ConfigMap, whatever applies or
    # mounts it. A sandbox ConfigMap pulled in by a non-sandbox workload is judged in BOTH.
    contexts: dict = {}
    for rel in iter_scan_files(root):
        contexts.setdefault(rel, {rel})
    modes: dict = {}
    cm_files: dict = {}  # ConfigMap name -> files that define its content
    for rel in [r for r in contexts if Path(r).name.lower() in KUSTOMIZATION_NAMES]:
        refs, gens = kustomization_refs(root, rel)
        for ref, mode in refs.items():
            contexts.setdefault(ref, {ref}).add(rel)
            modes[ref] = mode
            for g in gens:
                if mode == "env":
                    cm_files.setdefault(g, set()).add(ref)
        for g in gens:
            cm_files.setdefault(g, set()).add(rel)  # literals live in the kustomization itself
    unresolved_cm = set()
    file_hits: dict = {}
    for rel in sorted(contexts):
        subjects += 1
        file_hits[rel], docs = scan_file(root / rel, names, props, mode=modes.get(rel, ""))
        for doc in docs or []:
            if isinstance(doc, dict) and doc.get("kind") == "ConfigMap":
                n = (doc.get("metadata") or {}).get("name")
                if isinstance(n, str):
                    cm_files.setdefault(n, set()).add(rel)
    for rel, hits in file_hits.items():
        for nm, val, kind in hits:
            if nm == ENVFROM_REF:
                if val in cm_files:
                    for cm_rel in cm_files[val]:
                        contexts[cm_rel].add(rel)
                else:
                    unresolved_cm.add(val)
    for rel, ctx in sorted(contexts.items()):
        hits = [h for h in file_hits[rel] if h[0] != ENVFROM_REF]
        if not hits:
            continue
        env_of = {c: classify(c, envs, non_sandbox) for c in sorted(ctx)}
        label = ", ".join(f"{c} -> {e!r}" for c, e in env_of.items())
        for nm, val, kind in hits:
            if nm == UNATTRIBUTED_PATCH:
                allowed = set.intersection(*(set(f["permitted_environments"]) for f in flags))
                if any(e not in allowed for e in env_of.values()):
                    findings.append(("A", f"{rel} ({label}): patch touches env/envFrom or cannot be parsed ({kind}); "
                                          "it cannot be attributed to a flag, so it is refused outside the "
                                          f"environments every registered flag permits {sorted(allowed)}"))
                continue
            flag = by_name[nm]
            if all(e in flag["permitted_environments"] for e in env_of.values()):
                continue
            why = bad_in_env(val, kind)
            if why:
                findings.append(("A", f"{flag['env_var']}: {rel} ({label}) {why}; permitted only in "
                                      f"{flag['permitted_environments']}"))
    if unresolved_cm:
        # Not a finding: the object is not in this repo, so what it sets cannot be shown either way.
        print(f"NOTE demo-only-flags: {len(unresolved_cm)} envFrom ConfigMap name(s) not defined in the "
              "repo were not followed (known limit)")
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


def _classes(root, reg=None):
    return sorted({c for c, _ in check(root, reg or REG)[0]})


REG_ALIAS = dict(REG, flags=[dict(REG["flags"][0], env_var="X_ALIAS_FLAG")])
ALIAS_YAML = "openbank:\n  x:\n    auto-clear: ${X_ALIAS_FLAG:false}\n"


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
        ("A+: embedded patch string without newline (JSON)",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml":
                             "patches:\n  - patch: '[{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/3/value\",\"value\":\"true\"}]'\n"}), ["A"]),
        ("A+: unparsable embedded patch string fails closed",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml": "patches:\n  - patch: '[{op: replace, path: /env/0/value'\n"}), ["A"]),
        ("A-: embedded patch string touching something else",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml":
                             "patches:\n  - patch: '[{\"op\":\"replace\",\"path\":\"/spec/replicas\",\"value\":3}]'\n"}), []),
        ("A+: JSON6902 op move/copy via `from` on an env entry",
         _fixture(manifests={"openbank-infra/gitops-prod/p.yaml": "- op: copy\n  from: /spec/template/spec/containers/0/env/2/valueFrom\n  path: /x\n"}), ["A"]),
        ("A+: referenced .json patch file is scanned",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml": "patches:\n  - path: p.json\n",
                             "openbank-infra/gitops-prod/p.json":
                             '[{"op":"add","path":"/spec/template/spec/containers/0/env/-","value":{"name":"OPENBANK_X_AUTO_CLEAR","value":"true"}}]'}), ["A"]),
        ("A-: unreferenced .json file is not scanned",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml": "resources: []\n",
                             "openbank-infra/gitops-prod/p.json":
                             '[{"op":"replace","path":"/spec/template/spec/containers/0/env/3/value","value":"true"}]'}), []),
        ("A+: item1 escaped/uppercase .JSON referenced patch, no flag text prefilter",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml": "patches:\n  - path: P.JSON\n",
                             "openbank-infra/gitops-prod/P.JSON":
                             '[{"op":"add","path":"/spec/template/spec/containers/0/env/-","value":{"name":"OPENBANK_X_AUTO_\\u0043LEAR","value":"true"}}]'}), ["A"]),
        ("A+: item2 op replace env value false is still refused",
         _fixture(manifests={"openbank-infra/gitops-prod/p.yaml":
                             "- op: replace\n  path: /spec/template/spec/containers/0/env/3/value\n  value: \"false\"\n"}), ["A"]),
        ("A+: item2 envFrom op refused",
         _fixture(manifests={"openbank-infra/gitops-prod/p.yaml": "- op: add\n  path: /spec/template/spec/containers/0/envFrom/-\n  value: {}\n"}), ["A"]),
        ("A+: item3 sandbox patch file applied by a non-permitted (local) kustomization",
         _fixture(manifests={"openbank-infra/k8s/overlays/local/kustomization.yaml": "patchesStrategicMerge:\n  - ../../../gitops/p.yaml\n",
                             "openbank-infra/gitops/p.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: item3 patch file outside the scanned roots, applied from a non-permitted kustomization",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml": "patchesStrategicMerge:\n  - ../../elsewhere/p.txt\n",
                             "elsewhere/p.txt": ROLLOUT % "true"}), ["A"]),
        ("A-: item3 same sandbox patch applied from a sandbox kustomization",
         _fixture(manifests={"openbank-infra/gitops/kustomization.yaml": "patchesStrategicMerge:\n  - p.yaml\n",
                             "openbank-infra/gitops/p.yaml": ROLLOUT % "true"}), []),
        ("A+: item4 referenced file that does not parse",
         _fixture(manifests={"openbank-infra/gitops-prod/kustomization.yaml": "patches:\n  - path: bad.json\n",
                             "openbank-infra/gitops-prod/bad.json": "[{ not: valid: json"}), ["A"]),
        # ---- review round 2026-11-12: each case below must FAIL against the previous gate ----
        ("A+: R1 true under a future permitted-root production tree",
         _fixture(manifests={"openbank-infra/gitops/environments/production/x.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: R1 true under a segment-named uat dir inside a permitted root",
         _fixture(manifests={"openbank-infra/k8s/overlays/sandbox/uat/x.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: R1 values-prod.yaml inside the sandbox tree",
         _fixture(manifests={"openbank-infra/gitops/components/x/values-prod.yaml": ROLLOUT % "true"}), ["A"]),
        ("A-: R1 products/ is not the segment prod",
         _fixture(manifests={"openbank-infra/gitops/components/products/x.yaml": ROLLOUT % "true"}), []),
        ("A+: R1 a directory merely named overlays/sandbox outside the permitted root",
         _fixture(manifests={"deploy/overlays/sandbox/x.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: R2 deploy/ manifest outside openbank-infra",
         _fixture(manifests={"deploy/x.yaml": ROLLOUT % "true"}), ["A"]),
        ("A+: R2 Helm values file in charts/",
         _fixture(manifests={"charts/app/values.yaml": "env:\n  OPENBANK_X_AUTO_CLEAR: \"true\"\n"}), ["A"]),
        ("A+: R2 workflow env block",
         _fixture(manifests={".github/workflows/e2e.yml":
                             "jobs:\n  j:\n    env:\n      OPENBANK_X_AUTO_CLEAR: true\n"}), ["A"]),
        ("A-: R2 src/test fixture is not a deployment",
         _fixture(manifests={"openbank-x-service/src/test/resources/c.yaml": ROLLOUT % "true"}), []),
        ("A+: R3 -D inside JAVA_OPTS string",
         _fixture(manifests={"deploy/x.yaml": "env:\n  - name: JAVA_OPTS\n    value: \"-Xmx1g -Dopenbank.x.auto-clear=true\"\n"}), ["A"]),
        ("A-: R3 -D inside JAVA_OPTS string set false",
         _fixture(manifests={"deploy/x.yaml": "env:\n  - name: JAVA_OPTS\n    value: \"-Xmx1g -Dopenbank.x.auto-clear=false\"\n"}), []),
        ("A+: R3 Dockerfile ENV with -D",
         _fixture(manifests={"openbank-infra/docker/Dockerfile.x": 'FROM scratch\nENV JAVA_OPTS="-Xmx1g -Dopenbank.x.auto-clear=true"\n'}), ["A"]),
        ("A+: R4 ConfigMap application.yaml payload",
         _fixture(manifests={"deploy/cm.yaml": "kind: ConfigMap\ndata:\n  application.yaml: |\n    openbank:\n      x:\n        auto-clear: true\n"}), ["A"]),
        ("A+: R4 ConfigMap override.properties payload",
         _fixture(manifests={"deploy/cm.yaml": "kind: ConfigMap\ndata:\n  override.properties: |\n    config_ordinal=500\n    openbank.x.auto-clear=true\n"}), ["A"]),
        ("A+: R4 ConfigMap application.properties payload with %prod prefix",
         _fixture(manifests={"deploy/cm.yaml": "kind: ConfigMap\ndata:\n  application.properties: |\n    %prod.openbank.x.auto-clear=true\n"}), ["A"]),
        ("A-: R4 ConfigMap payload sets false",
         _fixture(manifests={"deploy/cm.yaml": "kind: ConfigMap\ndata:\n  override.properties: |\n    openbank.x.auto-clear=false\n"}), []),
        ("A+: R4 envFrom a sandbox ConfigMap that carries the flag, from a deploy/ workload",
         _fixture(manifests={"openbank-infra/gitops/cm.yaml": "kind: ConfigMap\nmetadata: {name: c}\ndata:\n  OPENBANK_X_AUTO_CLEAR: \"true\"\n",
                             "deploy/app.yaml": "kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n        - name: a\n          envFrom:\n            - configMapRef: {name: c}\n"}), ["A"]),
        ("A-: R4 envFrom the same ConfigMap from a sandbox workload",
         _fixture(manifests={"openbank-infra/gitops/cm.yaml": "kind: ConfigMap\nmetadata: {name: c}\ndata:\n  OPENBANK_X_AUTO_CLEAR: \"true\"\n",
                             "openbank-infra/gitops/app.yaml": "kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n        - name: a\n          envFrom:\n            - configMapRef: {name: c}\n"}), []),
        ("A-: R4 envFrom a ConfigMap that is not in the repo is left alone (known limit, not guessed)",
         _fixture(manifests={"deploy/app.yaml": "kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n        - name: a\n          envFrom:\n            - configMapRef: {name: not-here}\n"}), []),
        ("A+: R4 configMapGenerator envs file",
         _fixture(manifests={"deploy/kustomization.yaml": "configMapGenerator:\n  - name: g\n    envs: [flags.env]\n",
                             "deploy/flags.env": "OPENBANK_X_AUTO_CLEAR=true\n"}), ["A"]),
        ("A+: R4 configMapGenerator literals",
         _fixture(manifests={"deploy/kustomization.yaml": "configMapGenerator:\n  - name: g\n    literals:\n      - OPENBANK_X_AUTO_CLEAR=true\n"}), ["A"]),
        ("A-: R4 configMapGenerator envs file in the sandbox tree",
         _fixture(manifests={"openbank-infra/gitops/kustomization.yaml": "configMapGenerator:\n  - name: g\n    envs: [flags.env]\n",
                             "openbank-infra/gitops/flags.env": "OPENBANK_X_AUTO_CLEAR=true\n"}), []),
        ("A+: R4 inline patchesStrategicMerge entry",
         _fixture(manifests={"deploy/kustomization.yaml": "patchesStrategicMerge:\n  - |-\n    kind: Rollout\n    spec:\n      template:\n        spec:\n          containers:\n            - name: a\n              env:\n                - name: OPENBANK_X_AUTO_CLEAR\n                  value: \"true\"\n"}), ["A"]),
        ("A+: R4 kustomization file named `Kustomization` (no extension)",
         _fixture(manifests={"deploy/Kustomization": "patchesStrategicMerge:\n  - p.txt\n", "deploy/p.txt": ROLLOUT % "true"}), ["A"]),
        ("D-: %dev sets true", _fixture(yaml_text="'%dev':\n  openbank:\n    x:\n      auto-clear: true\n"), []),
        ("D-: %prod sets false", _fixture(yaml_text="'%prod':\n  openbank:\n    x:\n      auto-clear: false\n"), []),
    ]
    bad = 0
    alias_ro = ROLLOUT.replace("OPENBANK_X_AUTO_CLEAR", "%s")
    cases += [  # (label, root, want, registry): a flag whose manifest env var is an alias of the property
        ("A+: R5 alias env var true outside the sandbox", _fixture(yaml_text=ALIAS_YAML, manifests={"deploy/x.yaml": alias_ro % ("X_ALIAS_FLAG", "true")}), ["A"], REG_ALIAS),
        ("A+: R5 canonical env var of an aliased flag true outside the sandbox",
         _fixture(yaml_text=ALIAS_YAML, manifests={"deploy/x.yaml": ROLLOUT % "true"}), ["A"], REG_ALIAS),
        ("A-: R5 alias env var true in the sandbox", _fixture(yaml_text=ALIAS_YAML, manifests={sand: alias_ro % ("X_ALIAS_FLAG", "true")}), [], REG_ALIAS),
        ("C+: R5 alias env var that the yaml never reads", _fixture(), ["C"], REG_ALIAS),
    ]
    for case in cases:
        label, root, want = case[:3]
        got = _classes(root, case[3] if len(case) > 3 else None)
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
