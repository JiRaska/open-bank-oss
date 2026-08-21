#!/usr/bin/env python3
"""Fail when a BUILD-TIME-resolved CDI bean selection is activated by a RUNTIME env var.

Quarkus resolves `@IfBuildProperty` / `@UnlessBuildProperty` / `@IfBuildProfile` /
`@UnlessBuildProfile` during **augmentation** and freezes the verdict into the image: ArC
removes the losing bean's class outright. A container env var supplied by a Deployment or
Rollout is read at RUNTIME and therefore cannot change a decision the image no longer
contains. When the loser is a `@Default` no-op that returns the winner's success value,
nothing anywhere disagrees.

Measured, not theorised (issue #6057, PR #6081): `openbank-lending-service` gated its real
GL-posting and credit adapters this way, `application.yaml` defaulted them to `none` (and one
property was absent from the yaml entirely), the Rollout set both as env vars, and the
deployed image contained only the no-ops. The loan book had never touched the general ledger.

WHAT THIS CHECKS, per annotation site:
  * the value the BUILD sees  -- the module's application.yaml, with `${VAR:default}`
    resolved to `default`, because the augmentation JVM has no such env var set;
  * the value the RUNTIME gets -- a literal `value:` for that env var in any gitops
    workload manifest, keyed both by the canonical Quarkus env mapping of the property and
    by whatever `${VAR...}` the yaml itself interpolates.
Disagreement between the two, or a property absent from every application.yaml while gitops
sets its env var, is the defect: the manifest is asking for a bean that is not in the image.

SCOPE IS DERIVED, NEVER HAND-KEPT: the subject set is every annotation site found in the
tree. A gate whose scope is a maintained list of the thing it checks reads as PASSING when
the list is short, never as UNCHECKED. Exclusions live in EXCLUSIONS below, each with a
reason, and go stale in BOTH directions -- an entry naming a site that no longer exists, or
that no longer has the defect, fails just as loudly as a new occurrence.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass

import gatelib

ANNOTATIONS = ("IfBuildProperty", "UnlessBuildProperty", "IfBuildProfile", "UnlessBuildProfile")
ANN_RE = re.compile(r"@(" + "|".join(ANNOTATIONS) + r")\s*\(([^)]*)\)")
INTERP_RE = re.compile(r"\$\{([A-Za-z0-9_.-]+)(?::([^}]*))?\}")
MIN_SITES = 20  # 29 sites on 2026-08-20; a collapse to near-zero means the scan broke
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "target"}

# site key -> reason. Keys are "<module>:<property>".
EXCLUSIONS: dict[str, str] = {
    "openbank-lending-service:lending.ledger.backend":
        "Confirmed live, fix in flight in PR #6081 (issue #6057): the deployed image contains "
        "NoOpLedgerPostingPort and not RestLedgerPostingAdapter. Drop this entry in the PR that "
        "lands the fix -- the staleness check below will demand it.",
    "openbank-lending-service:lending.borrower-credit.backend":
        "Confirmed live, fix in flight in PR #6081 (issue #6057): the deployed image contains "
        "neither BorrowerCreditClient nor AccountServiceClient. Drop this entry with the fix.",
    "openbank-lending-service:openbank.temporal.enabled":
        "Confirmed live by the same bytecode probe and filed separately as issue #6085: the image "
        "contains NoOpOriginationWorkflowPort and not TemporalOriginationWorkflowAdapter, so no "
        "origination durable timer has ever been armed. Drop this entry with that fix.",
}


def canonical_env(prop: str) -> str:
    """Quarkus/MicroProfile env-var mapping: uppercase, non-alphanumerics to underscore."""
    return re.sub(r"[^A-Z0-9]", "_", prop.upper())


@dataclass
class Site:
    module: str
    path: str
    line: int
    annotation: str
    prop: str
    string_value: str
    enable_if_missing: bool

    @property
    def key(self) -> str:
        return f"{self.module}:{self.prop}"


@dataclass
class Finding:
    site: Site
    build_value: str | None
    runtime_value: str
    env_var: str
    where: str
    kind: str

    def render(self) -> str:
        seen = "ABSENT from every application.yaml" if self.build_value is None else f"{self.build_value!r}"
        return (
            f"{self.site.path}:{self.site.line}: @{self.site.annotation}"
            f'(name = "{self.site.prop}", stringValue = "{self.site.string_value}")\n'
            f"    the BUILD sees {seen}\n"
            f"    the RUNTIME is handed {self.env_var}={self.runtime_value!r} by {self.where}\n"
            f"    -> {self.kind}: a runtime env var cannot change a decision augmentation "
            f"already froze into the image."
        )


def walk(root: str, exts: tuple[str, ...]):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(exts):
                yield os.path.join(dirpath, name)


def module_of(root: str, path: str) -> str:
    rel = os.path.relpath(path, root)
    return rel.split(os.sep)[0]


def collect_sites(root: str) -> list[Site]:
    sites: list[Site] = []
    for path in walk(root, (".kt", ".java")):
        if f"{os.sep}src{os.sep}main{os.sep}" not in path:
            continue
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for idx, raw in enumerate(text.splitlines(), start=1):
            stripped = raw.strip()
            if stripped.startswith(("*", "//", "/*")):
                continue  # a KDoc paragraph naming the annotation is not a site
            for match in ANN_RE.finditer(raw):
                args = match.group(2)
                name = re.search(r'name\s*=\s*"([^"]+)"', args)
                value = re.search(r'stringValue\s*=\s*"([^"]*)"', args)
                if not name:
                    continue
                sites.append(
                    Site(
                        module=module_of(root, path),
                        path=os.path.relpath(path, root),
                        line=idx,
                        annotation=match.group(1),
                        prop=name.group(1),
                        string_value=value.group(1) if value else "",
                        enable_if_missing="enableIfMissing = true" in args.replace(" =", " ="),
                    )
                )
    return sites


def flatten(node, prefix: str, out: dict[str, str]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            flatten(value, f"{prefix}{key}." if prefix or key else f"{key}.", out)
    elif node is not None:
        out[prefix.rstrip(".")] = str(node)


def load_yaml_configs(root: str) -> dict[str, dict[str, str]]:
    """module -> flattened dotted config from its application.yaml (profile blocks skipped)."""
    import yaml

    configs: dict[str, dict[str, str]] = {}
    for path in walk(root, (".yaml", ".yml", ".properties")):
        if f"{os.sep}src{os.sep}main{os.sep}resources{os.sep}" not in path:
            continue
        base = os.path.basename(path)
        if not base.startswith("application"):
            continue
        module = module_of(root, path)
        flat = configs.setdefault(module, {})
        if base.endswith(".properties"):
            for raw in open(path, encoding="utf-8", errors="replace"):
                if "=" in raw and not raw.strip().startswith("#"):
                    key, _, value = raw.partition("=")
                    flat.setdefault(key.strip(), value.strip())
            continue
        try:
            docs = list(yaml.safe_load_all(open(path, encoding="utf-8", errors="replace")))
        except Exception:
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            for top, sub in doc.items():
                if str(top).startswith("%"):
                    continue  # a profile block is not the default the build sees
                flatten(sub, f"{top}.", flat)
    return configs


BUILD_WORKFLOW = os.path.join(".github", "workflows", "auto-deploy.yml")


def collect_build_env(root: str) -> dict[str, str]:
    """Env vars the image BUILD itself exports, which augmentation therefore does see.

    This is the third source of build-time truth and the reason the analytics-sink and
    agent-service gates are correct today: `auto-deploy.yml`'s fast-jar build step sets
    ANALYTICS_SINK_TYPE and AGENT_SVID_NONCE_STORE in its own `env:`, having hit this exact
    trap twice before (#4728). A gate that read only application.yaml would call those two
    defects and be wrong -- and would then be argued down instead of fixing lending.
    """
    import yaml

    path = os.path.join(root, BUILD_WORKFLOW)
    if not os.path.exists(path):
        return {}
    try:
        doc = yaml.safe_load(open(path, encoding="utf-8", errors="replace"))
    except Exception:
        return {}
    out: dict[str, str] = {}

    def visit(node):
        if isinstance(node, dict):
            env = node.get("env")
            if isinstance(env, dict):
                for key, value in env.items():
                    if isinstance(key, str) and isinstance(value, (str, int, bool)):
                        out[key] = str(value)
            for child in node.values():
                visit(child)
        elif isinstance(node, list):
            for child in node:
                visit(child)

    visit(doc)
    return out


def build_time_value(
    configs: dict[str, dict[str, str]],
    module: str,
    prop: str,
    build_env: dict[str, str] | None = None,
) -> tuple[str | None, list[str]]:
    """(value the augmentation JVM resolves, env vars the yaml interpolates)."""
    raw = configs.get(module, {}).get(prop)
    if raw is None:
        # A libs-declared property is configured by whichever service consumes it.
        for other in configs.values():
            if prop in other:
                raw = other[prop]
                break
    build_env = build_env or {}
    canonical = canonical_env(prop)
    if canonical in build_env:
        # The build step exports it, so augmentation genuinely sees this value.
        return build_env[canonical], [canonical]
    if raw is None:
        return None, []
    envs = [m.group(1) for m in INTERP_RE.finditer(raw)]
    for name in envs:
        if name in build_env:
            return build_env[name], envs
    # env is unset during augmentation, so every interpolation collapses to its default
    resolved = INTERP_RE.sub(lambda m: m.group(2) if m.group(2) is not None else "", raw)
    return resolved.strip(), envs


def collect_gitops_env(root: str) -> dict[str, dict[str, list[tuple[str, str]]]]:
    """module -> env var name -> [(literal value, manifest path)].

    Attribution is by CONTAINER IMAGE, not by file name or workload name: a container whose
    image repository ends in `/<module>` is that module, wherever the manifest lives. Reading
    env vars file-wide would credit `openbank-lending-service` with a sibling Rollout's
    settings in a multi-workload manifest, and the whole point of this gate is to compare two
    values that genuinely apply to the same image.
    """
    import yaml

    found: dict[str, dict[str, list[tuple[str, str]]]] = {}

    def containers(node):
        if isinstance(node, dict):
            for key, child in node.items():
                if key in ("containers", "initContainers") and isinstance(child, list):
                    for container in child:
                        if isinstance(container, dict):
                            yield container
                yield from containers(child)
        elif isinstance(node, list):
            for child in node:
                yield from containers(child)

    for manifest in walk(os.path.join(root, "openbank-infra"), (".yaml", ".yml")):
        try:
            docs = list(yaml.safe_load_all(open(manifest, encoding="utf-8", errors="replace")))
        except Exception:
            continue
        rel = os.path.relpath(manifest, root)
        for doc in docs:
            for container in containers(doc):
                image = str(container.get("image", ""))
                match = re.search(r"/(openbank-[a-z0-9-]+)(?::|$)", image)
                if not match:
                    continue
                bucket = found.setdefault(match.group(1), {})
                for entry in container.get("env") or []:
                    if not isinstance(entry, dict):
                        continue
                    name, value = entry.get("name"), entry.get("value")
                    if isinstance(name, str) and isinstance(value, (str, int, bool)):
                        bucket.setdefault(name, []).append((str(value), rel))
    return found


def analyse(root: str) -> tuple[list[Site], list[Finding]]:
    sites = collect_sites(root)
    configs = load_yaml_configs(root)
    env_by_module = collect_gitops_env(root)
    build_env = collect_build_env(root)
    findings: list[Finding] = []
    for site in sites:
        build_value, interpolated = build_time_value(configs, site.module, site.prop, build_env)
        env = env_by_module.get(site.module, {})
        candidates = [canonical_env(site.prop), *interpolated]
        for name in dict.fromkeys(candidates):
            for value, where in env.get(name, []):
                if build_value is None:
                    kind = "property is in NO application.yaml, so the build gated on a MISSING value"
                elif value == build_value:
                    continue
                else:
                    kind = "the gitops value never reaches augmentation"
                findings.append(Finding(site, build_value, value, name, where, kind))
                break
            else:
                continue
            break
    return sites, findings


def report(root: str, verbose: bool) -> int:
    sites, findings = analyse(root)
    keyed = {f.site.key: f for f in findings}
    print(f"build-time bean selection: {len(sites)} annotation site(s) across "
          f"{len({s.module for s in sites})} module(s)")
    # Unconditional, including on the failure path (gatelib.subjects' own contract) — a gate
    # that found its 29 sites and then flagged a contradiction among them must not also read as
    # having lost its corpus.
    gatelib.subjects(len(sites), "@IfBuildProperty/@UnlessBuildProperty annotation sites")
    if verbose:
        for site in sorted(sites, key=lambda s: (s.module, s.prop)):
            build_value, _ = build_time_value(
                load_yaml_configs(root), site.module, site.prop, collect_build_env(root)
            )
            print(f"  {site.key:<62} build={build_value!r}")

    failed = False
    if len(sites) < MIN_SITES:
        print(f"::error::scope collapse: only {len(sites)} annotation site(s) found, "
              f"expected at least {MIN_SITES} (29 on 2026-08-20). A check that reaches none of "
              f"its subjects reports OK; that is the failure this floor exists to make loud.")
        failed = True
    live = [f for f in findings if f.site.key not in EXCLUSIONS]
    for finding in sorted(live, key=lambda f: f.site.path):
        print(f"::error file={finding.site.path},line={finding.site.line}::"
              f"build-time-gated bean selection activated by a runtime env var "
              f"({finding.site.prop})")
        print(finding.render())
        failed = True

    for key, reason in sorted(EXCLUSIONS.items()):
        if key not in {s.key for s in sites}:
            print(f"::error::stale exclusion {key!r}: no such annotation site remains "
                  f"(reason on file: {reason})")
            failed = True
        elif key not in keyed:
            print(f"::error::stale exclusion {key!r}: the site no longer has the defect, "
                  f"so the exclusion is now hiding nothing (reason on file: {reason})")
            failed = True

    if not failed:
        print(f"OK: no build-time-gated selection is contradicted by a gitops runtime env var "
              f"({len(sites)} site(s) checked, {len(EXCLUSIONS)} excluded).")
    return 1 if failed else 0


def self_test() -> int:
    """Prove the check by what it PREVENTS: the pre-#6081 lending shape must fail."""
    import shutil
    import tempfile

    ok = True
    tmp = tempfile.mkdtemp(prefix="btbs-selftest-")
    try:
        svc = os.path.join(tmp, "openbank-demo-service")
        src = os.path.join(svc, "src", "main", "kotlin")
        res = os.path.join(svc, "src", "main", "resources")
        gitops = os.path.join(tmp, "openbank-infra", "gitops")
        for d in (src, res, gitops):
            os.makedirs(d, exist_ok=True)
        with open(os.path.join(src, "RestAdapter.kt"), "w") as fh:
            fh.write('@IfBuildProperty(name = "demo.ledger.backend", stringValue = "rest")\n'
                     "class RestAdapter\n")
        with open(os.path.join(gitops, "demo.yaml"), "w") as fh:
            fh.write("spec:\n  containers:\n"
                     "    - image: registry.invalid/openbank-demo-service:tag\n"
                     "      env:\n"
                     "        - name: DEMO_LEDGER_BACKEND\n          value: rest\n"
                     "    - image: registry.invalid/openbank-other-service:tag\n"
                     "      env:\n"
                     "        - name: DEMO_LEDGER_BACKEND\n          value: none\n")

        # NEGATIVE CASE FIRST: the defect shape must be detected.
        with open(os.path.join(res, "application.yaml"), "w") as fh:
            fh.write("demo:\n  ledger:\n    backend: ${DEMO_LEDGER_BACKEND:none}\n")
        _, findings = analyse(tmp)
        if len(findings) != 1:
            print(f"SELF-TEST FAIL: the pre-fix lending shape produced {len(findings)} finding(s), expected 1")
            ok = False
        else:
            print("self-test: pre-fix shape (build sees 'none', runtime handed 'rest') -> DETECTED")

        # ... and with the property absent from the yaml entirely.
        os.remove(os.path.join(res, "application.yaml"))
        _, findings = analyse(tmp)
        if len(findings) != 1:
            print(f"SELF-TEST FAIL: absent-property shape produced {len(findings)} finding(s), expected 1")
            ok = False
        else:
            print("self-test: property absent from every application.yaml -> DETECTED")

        # POSITIVE CASE: the fixed shape must pass.
        with open(os.path.join(res, "application.yaml"), "w") as fh:
            fh.write("demo:\n  ledger:\n    backend: ${DEMO_LEDGER_BACKEND:rest}\n")
        _, findings = analyse(tmp)
        if findings:
            print(f"SELF-TEST FAIL: the fixed shape produced {len(findings)} finding(s), expected 0")
            ok = False
        else:
            print("self-test: fixed shape (build default agrees with the manifest) -> clean")

        # The scope must not be silently empty.
        sites, _ = analyse(tmp)
        if not sites:
            print("SELF-TEST FAIL: no annotation site was collected at all")
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("self-test: PASS" if ok else "self-test: FAIL")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return report(args.root, args.verbose)


if __name__ == "__main__":
    sys.exit(main())
