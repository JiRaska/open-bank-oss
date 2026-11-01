# SPDX-License-Identifier: Apache-2.0
"""Check prerequisites when an automated Argo Application acquires a service workload.

The subject is an Application's literal source.path, not its name or namespace. A PR
adding/promoting an Application is checked, as is a PR changing the source directory
of an already automated Application. Staged Applications have no live-workload claim.
"""
from __future__ import annotations

import argparse
import fnmatch
import importlib.util
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath

import yaml

ROOT = Path(__file__).resolve().parents[2]
APPS = PurePosixPath("openbank-infra/gitops/apps")
COMPONENTS = PurePosixPath("openbank-infra/gitops/components")
OPA_IMAGE = re.compile(r"(^|/)opa(?::|@)")
CHECKSUM = "openbank.tech/policy-checksum"


def git_env() -> dict[str, str]:
    # run-gates.py gives each gate a private GIT_INDEX_FILE. Git worktrees need
    # their own index, and fixture repositories must not inherit the parent's.
    env = dict(os.environ)
    env.pop("GIT_INDEX_FILE", None)
    return env


def under(path: PurePosixPath, parent: PurePosixPath) -> bool:
    """Path-component containment; wealth-extra is never inside wealth."""
    return path == parent or parent in path.parents


def source_path(root: Path, app: dict) -> PurePosixPath:
    raw = ((app.get("spec") or {}).get("source") or {}).get("path")
    if not isinstance(raw, str) or not raw:
        raise ValueError("Application has no literal spec.source.path")
    p = PurePosixPath(raw)
    if p.is_absolute() or ".." in p.parts or not under(p, COMPONENTS):
        raise ValueError(f"unsafe/non-component Application source.path: {raw}")
    cursor = root
    for part in p.parts:
        cursor /= part
        if cursor.is_symlink():
            raise ValueError(f"Application source.path contains symlink: {raw}")
    resolved = (root / p).resolve()
    if not resolved.is_relative_to(root.resolve()) or not resolved.is_dir():
        raise ValueError(f"Application source.path escapes repository or is absent: {raw}")
    return p


def documents(path: Path) -> list[dict]:
    try:
        return [d for d in yaml.safe_load_all(path.read_text()) if isinstance(d, dict)]
    except (OSError, yaml.YAMLError) as exc:
        raise ValueError(f"cannot read YAML {path}: {exc}") from exc


def applications(root: Path) -> dict[tuple[str, str], tuple[PurePosixPath, dict]]:
    found = {}
    for file in sorted((root / APPS).glob("*.yaml")):
        for doc in documents(file):
            if doc.get("apiVersion") != "argoproj.io/v1alpha1" or doc.get("kind") != "Application":
                continue
            meta = doc.get("metadata") or {}
            key = (str(meta.get("namespace", "")), str(meta.get("name", "")))
            if not all(key) or key in found:
                raise ValueError(f"missing or duplicate Application identity in {file}")
            found[key] = (PurePosixPath(file.relative_to(root).as_posix()), doc)
    return found


def automated(app: dict) -> bool:
    return "automated" in ((app.get("spec") or {}).get("syncPolicy") or {})


def changed_paths(root: Path, base: str) -> set[PurePosixPath]:
    result = subprocess.run(
        ["git", "-C", str(root), "diff", "--name-only", "-z", f"{base}...HEAD"],
        capture_output=True, check=True, env=git_env(),
    )
    return {PurePosixPath(x.decode()) for x in result.stdout.split(b"\0") if x}


def base_app(root: Path, base: str, file: PurePosixPath, key: tuple[str, str]) -> dict | None:
    old = subprocess.run(
        ["git", "-C", str(root), "show", f"{base}:{file}"], capture_output=True, env=git_env(), check=False,
    )
    if old.returncode:
        return None
    try:
        docs = yaml.safe_load_all(old.stdout.decode())
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "Application":
                continue
            meta = doc.get("metadata") or {}
            if (str(meta.get("namespace", "")), str(meta.get("name", ""))) == key:
                return doc
    except yaml.YAMLError as exc:
        raise ValueError(f"base Application YAML is invalid: {file}: {exc}") from exc
    return None


def selected_applications(root: Path, base: str) -> list[tuple[PurePosixPath, dict]]:
    changed = changed_paths(root, base)
    selected = []
    for key, (file, app) in applications(root).items():
        if not automated(app):
            continue
        source = (app.get("spec") or {}).get("source") or {}
        # Third-party Helm Applications have a chart/repoURL, no repo component
        # source.path. Their rendered workload is not in this checkout; this
        # gate makes no readiness claim about them.
        if source.get("chart") and "path" not in source:
            continue
        component = source_path(root, app)
        prior = base_app(root, base, file, key) if file in changed else app
        activated = file in changed and (prior is None or not automated(prior)
                                         or (prior.get("spec") or {}).get("source") != (app.get("spec") or {}).get("source"))
        source_changed = any(under(path, component) for path in changed)
        if activated or source_changed:
            selected.append((component, app))
    return selected


def argo_pattern(pattern: str, name: str) -> bool:
    """The brace lists used by Argo Directory in this repository."""
    if pattern.startswith("{") and pattern.endswith("}"):
        return any(fnmatch.fnmatchcase(name, item) for item in pattern[1:-1].split(","))
    if "{" in pattern or "}" in pattern:
        raise ValueError(f"unsupported Argo Directory pattern: {pattern}")
    return fnmatch.fnmatchcase(name, pattern)


def effective_resources(root: Path, component: PurePosixPath, app: dict) -> list[tuple[Path, dict]]:
    """Render the Application's selected local resources, excluding staged files."""
    directory = root / component
    kustomization = next((directory / n for n in ("kustomization.yaml", "kustomization.yml", "Kustomization")
                            if (directory / n).exists()), None)
    source = (app.get("spec") or {}).get("source") or {}
    if kustomization:
        if source.get("directory"):
            raise ValueError(f"Application mixes Kustomize and Directory settings: {component}")
        try:
            proc = subprocess.run(["kubectl", "kustomize", str(directory)], capture_output=True, text=True, check=False)
        except FileNotFoundError as exc:
            raise ValueError("kubectl kustomize is required to inspect this Application source") from exc
        if proc.returncode:
            raise ValueError(f"Kustomize render failed for {component}: {proc.stderr[:300]}")
        return [(kustomization, doc) for doc in yaml.safe_load_all(proc.stdout) if isinstance(doc, dict)]
    settings = source.get("directory") or {}
    if not isinstance(settings, dict):
        raise TypeError(f"invalid Argo Directory settings: {component}")
    recurse = settings.get("recurse", False)
    files = directory.rglob("*") if recurse else directory.glob("*")
    result = []
    for file in sorted(files):
        if not file.is_file() or file.suffix not in (".yaml", ".yml", ".json", ".jsonnet"):
            continue
        if file.is_symlink() or not file.resolve().is_relative_to(directory.resolve()):
            raise ValueError(f"symlink in Application source: {file}")
        name = file.relative_to(directory).as_posix()
        if settings.get("include") and not argo_pattern(str(settings["include"]), name):
            continue
        if settings.get("exclude") and argo_pattern(str(settings["exclude"]), name):
            continue
        if file.suffix == ".jsonnet":
            raise ValueError(f"selected Jsonnet source requires a supported renderer: {file}")
        result.extend((file, doc) for doc in documents(file))
    return result


def pod_template(doc: dict) -> dict | None:
    kind = doc.get("kind")
    spec = doc.get("spec") or {}
    if kind in ("Deployment", "Rollout", "StatefulSet", "DaemonSet", "Job"):
        template = spec.get("template")
    elif kind == "CronJob":
        template = ((spec.get("jobTemplate") or {}).get("spec") or {}).get("template")
    elif kind == "Pod":
        template = {"metadata": doc.get("metadata") or {}, "spec": spec}
    else:
        return None
    if not isinstance(template, dict) or not isinstance(template.get("spec"), dict):
        raise TypeError(f"{kind} has no pod spec")
    return template


def workloads(resources: list[tuple[Path, dict]]) -> list[tuple[Path, dict, dict, set[str], list[dict]]]:
    result = []
    for file, doc in resources:
        template = pod_template(doc)
        if template is None:
            continue
        spec = template["spec"]
        containers = spec.get("containers") or []
        initial = spec.get("initContainers") or []
        if not isinstance(containers, list) or not isinstance(initial, list):
            raise TypeError(f"{file}: invalid pod container list")
        names = set()
        for container in (*containers, *initial):
            if not isinstance(container, dict):
                raise TypeError(f"{file}: invalid pod container")
            image = str(container.get("image", ""))
            name = image.rsplit("/", 1)[-1].split(":", 1)[0].split("@", 1)[0]
            # The owned image identity is shared by regular and init containers.
            if re.fullmatch(r"openbank-[a-z0-9-]+", name):
                names.add(name)
            elif name.startswith("openbank-"):
                raise ValueError(f"{file}: malformed openbank image identity {name}")
        opa = [container for container in containers if OPA_IMAGE.search(str(container.get("image", "")))]
        result.append((file, doc, template, names, opa))
    return result


def load_checker(name: str, root: Path):
    path = root / "openbank-infra/scripts" / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_component(root: Path, component: PurePosixPath, app: dict | None = None) -> tuple[int, dict[str, str]]:
    errors = 0
    bundle_generators: dict[str, str] = {}
    if app is None:
        app = {"spec": {"source": {"path": str(component)}}}
    resources = effective_resources(root, component, app)
    default_namespace = ((app.get("spec") or {}).get("destination") or {}).get("namespace", "default")

    def identity(doc: dict) -> tuple[str, str]:
        metadata = doc.get("metadata") or {}
        return (metadata.get("namespace") or default_namespace, metadata.get("name"))

    configmaps = {}
    for file, doc in resources:
        if doc.get("kind") == "ConfigMap":
            key = identity(doc)
            if key in configmaps:
                raise ValueError(f"duplicate ConfigMap {key} in {component}")
            configmaps[key] = (file, doc)
    threat = load_checker("check-threat-models.py", root)
    backup = load_checker("check-cnpg-backup-declared.py", root)
    for file, _doc, template, modules, opa in workloads(resources):
        for name in sorted(modules):
            state, detail = threat.evaluate(name, root / "docs/threat-models")
            # The existing deployed-workload gate owns this shrink-only debt list.
            # Re-evaluating a changed shared component must not invent a stricter
            # rule for its already declared historical gaps.
            if state != "ok" and name not in threat.DEPLOYED_BASELINE:
                print(f"::error file={file}::{name}: {detail}")
                errors += 1
        if modules and opa:
            annotation = ((template.get("metadata") or {}).get("annotations") or {}).get(CHECKSUM)
            if not isinstance(annotation, str) or not annotation:
                print(f"::error file={file}::OPA sidecar lacks pod-template {CHECKSUM}")
                errors += 1
            volumes = {v.get("name"): v for v in (template.get("spec") or {}).get("volumes", [])}
            mounted = {m.get("name") for sidecar in opa for m in sidecar.get("volumeMounts", [])}
            bundles = {volumes[m].get("configMap", {}).get("name") for m in mounted
                       if m in volumes and volumes[m].get("configMap")}
            if len(bundles) != 1:
                print(f"::error file={file}::OPA sidecar must mount exactly one declared bundle ConfigMap")
                errors += 1
            for bundle in bundles:
                entry = configmaps.get((identity(_doc)[0], bundle))
                if not entry:
                    print(f"::error file={file}::mounted OPA bundle {bundle} absent from Application resources")
                    errors += 1
                    continue
                cm_file, cm = entry
                checksum = ((cm.get("metadata") or {}).get("annotations") or {}).get(CHECKSUM)
                if checksum != annotation:
                    print(f"::error file={file}::pod and mounted OPA bundle checksums differ")
                    errors += 1
                matching = []
                for candidate in sorted((root / component).rglob("*")):
                    if not candidate.is_file() or candidate.suffix not in (".yaml", ".yml", ".json"):
                        continue
                    if candidate.is_symlink():
                        raise ValueError(f"symlink in OPA source: {candidate}")
                    if any(d.get("kind") == "ConfigMap" and identity(d) == (identity(_doc)[0], bundle)
                           for d in documents(candidate)):
                        matching.append(candidate)
                if len(matching) != 1:
                    print(f"::error file={file}::OPA bundle source identity is ambiguous")
                    errors += 1
                    continue
                header = matching[0].read_text().splitlines()[0]
                marker = "# GENERATED by "
                generator_name = header[len(marker):].split(" ", 1)[0] if header.startswith(marker) else ""
                generator = matching[0].parent / generator_name
                if (not re.fullmatch(r"gen-[A-Za-z0-9_.-]+\.sh", generator_name)
                        or generator.is_symlink() or not generator.is_file()
                        or not generator.resolve().is_relative_to((root / component).resolve())
                        or not os.access(generator, os.X_OK)):
                    print(f"::error file={cm_file}::mounted OPA bundle has no matching generator")
                    errors += 1
                else:
                    bundle = matching[0].relative_to(root / component).as_posix()
                    bundle_generators[bundle] = generator.relative_to(root / component).as_posix()
    # Existing CNPG checker, scoped to this Application's effective resources.
    with tempfile.TemporaryDirectory(prefix="service-readiness-cnpg-") as temp:
        rendered = Path(temp) / "resources.yaml"
        rendered.write_text(yaml.safe_dump_all([doc for _, doc in resources], sort_keys=False))
        _, backup_findings = backup.check(Path(temp))
    errors += backup_findings
    return errors, bundle_generators


def verify_generated(root: Path, components: dict[PurePosixPath, dict[str, str]]) -> int:
    """Recreate each declared bundle alone in a disposable exact-HEAD worktree."""
    if not components:
        return 0
    with tempfile.TemporaryDirectory(prefix="service-readiness-opa-") as temp:
        scratch = Path(temp) / "checkout"
        subprocess.run(["git", "-C", str(root), "worktree", "add", "--detach", "--quiet",
                        str(scratch), "HEAD"], check=True, env=git_env())
        try:
            errors = 0
            for component, bundles in sorted(components.items()):
                for bundle_rel, generator_rel in sorted(bundles.items()):
                    subprocess.run(["git", "-C", str(scratch), "reset", "--hard", "HEAD"],
                                   check=True, capture_output=True, env=git_env())
                    bundle = scratch / component / bundle_rel
                    generator = scratch / component / generator_rel
                    if (bundle.is_symlink() or not bundle.is_file()
                            or not bundle.resolve().is_relative_to((scratch / component).resolve())
                            or generator.is_symlink() or not generator.is_file()
                            or not generator.resolve().is_relative_to((scratch / component).resolve())
                            or not generator.stat().st_mode & 0o111):
                        print(f"::error file={bundle}::OPA bundle/generator missing, escaped or not executable")
                        errors += 1
                        continue
                    bundle.unlink()
                    result = subprocess.run([str(generator)], cwd=scratch, capture_output=True,
                                            text=True, env=git_env(), check=False)
                    if result.returncode:
                        print(f"::error file={generator}::OPA generator failed ({result.returncode})")
                        errors += 1
                    changed = subprocess.run(["git", "-C", str(scratch), "status", "--porcelain", "--untracked-files=all"],
                                             capture_output=True, text=True, check=True, env=git_env())
                    if not bundle.is_file() or changed.stdout:
                        print(f"::error::{component}: declared generator did not recreate {bundle_rel} byte-identically")
                        errors += 1
            return errors
        finally:
            subprocess.run(["git", "-C", str(root), "worktree", "remove", "--force", str(scratch)],
                           check=True, capture_output=True, env=git_env())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--root", type=Path, default=ROOT)
    args = ap.parse_args()
    root = args.root.resolve()
    try:
        subjects = selected_applications(root, args.base)
        errors = 0
        generated: dict[PurePosixPath, dict[str, str]] = {}
        for component, app in subjects:
            count, bundles = check_component(root, component, app)
            errors += count
            if bundles:
                generated[component] = bundles
            print(f"service-application-readiness: {app['metadata']['name']} source={component}, findings={count}")
        errors += verify_generated(root, generated)
        print(f"SUBJECTS={len(subjects)}; FINDINGS={errors}")
        return 1 if errors else 0
    except (TypeError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"::error::service-application-readiness: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
