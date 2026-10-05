# SPDX-License-Identifier: Apache-2.0
"""Check service readiness when a PR adds or enables an auto-synced Argo Application.

Existing Applications are outside this PR-time gate. The fleet-wide threat-model,
OPA-bundle and CNPG backup gates remain the source of truth for their own details.
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import re
import subprocess
import sys
import tempfile

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
APPS = pathlib.Path("openbank-infra/gitops/apps")
COMPONENTS = pathlib.Path("openbank-infra/gitops/components")
IMAGE = re.compile(r"(?:^|/)openbank-[a-z0-9-]+(?=[:@]|$)")


def sibling(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), pathlib.Path(__file__).with_name(name))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


THREAT_MODELS = sibling("check-threat-models.py")
BACKUPS = sibling("check-cnpg-backup-declared.py")


def docs(text: str) -> list[dict]:
    return [doc for doc in yaml.safe_load_all(text) if isinstance(doc, dict)]


def application(text: str) -> dict:
    apps = [doc for doc in docs(text) if doc.get("kind") == "Application" and
            str(doc.get("apiVersion", "")).startswith("argoproj.io/")]
    if len(apps) != 1:
        raise ValueError("expected exactly one Argo Application")
    return apps[0]


def automated(app: dict | None) -> bool:
    if not app:
        return False
    policy = (app.get("spec") or {}).get("syncPolicy") or {}
    value = policy.get("automated")
    return isinstance(value, dict) and value.get("enabled") is not False


def check_app(root: pathlib.Path, app: dict) -> list[str]:
    source = (app.get("spec") or {}).get("source") or {}
    raw_path = source.get("path")
    if not isinstance(raw_path, str):
        return []  # chart/infra Application, not a component service
    rel = pathlib.PurePosixPath(raw_path)
    if rel.is_absolute() or ".." in rel.parts or not rel.is_relative_to(COMPONENTS):
        return ["Application source.path must be within gitops/components"]
    component = root / rel
    if not component.is_dir():
        return [f"component directory is missing: {rel}"]
    objects: list[dict] = []
    try:
        for path in sorted(component.rglob("*.yaml")):
            objects.extend(docs(path.read_text(encoding="utf-8")))
    except (OSError, yaml.YAMLError) as exc:
        return [f"component YAML is unreadable: {type(exc).__name__}"]

    workloads = [obj for obj in objects if obj.get("kind") in {"Deployment", "Rollout", "StatefulSet"}]
    service_images: set[str] = set()
    bundle_refs: set[str] = set()
    for workload in workloads:
        pod = (((workload.get("spec") or {}).get("template") or {}).get("spec") or {})
        containers = pod.get("containers") or []
        for container in containers:
            match = IMAGE.search(str(container.get("image", "")))
            if match:
                service_images.add(match.group().rsplit("/", 1)[-1])
        if any(container.get("name") == "opa" for container in containers):
            mounted = {
                volume["configMap"]["name"] for volume in pod.get("volumes") or []
                if isinstance(volume.get("configMap"), dict) and
                isinstance(volume["configMap"].get("name"), str) and
                "opa-bundle" in volume["configMap"]["name"]
            }
            if not mounted:
                return ["OPA sidecar has no mounted OPA bundle ConfigMap"]
            bundle_refs.update(mounted)

    findings: list[str] = []
    for service in sorted(service_images):
        status, detail = THREAT_MODELS.evaluate(service, root / "docs/threat-models")
        if status != "ok":
            findings.append(f"{service} threat model {status}: {detail}")

    bundles = {((obj.get("metadata") or {}).get("name")) for obj in objects
               if obj.get("kind") == "ConfigMap" and "rest.rego" in (obj.get("data") or {})}
    for ref in sorted(bundle_refs - bundles):
        findings.append(f"OPA bundle {ref} is referenced but not included in the component")

    cluster_rows = list(BACKUPS.cnpg_clusters(component))
    backup_rows = list(BACKUPS.scheduled_backups(component))
    for name, namespace, _, destination, exempt in cluster_rows:
        if not destination:
            if not exempt:
                findings.append(f"CNPG {namespace}/{name} has no declared backup or exemption")
            continue
        if not any(ns == namespace and cluster == name and immediate
                   for ns, cluster, _, immediate in backup_rows):
            findings.append(f"CNPG {namespace}/{name} lacks a matching immediate ScheduledBackup")
    return findings


def changed_apps(root: pathlib.Path, base: str):
    # The gate runner isolates its self-test with Git index/object overrides.
    # Explicit -C must address this checkout, not that synthetic index.
    git_env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    proc = subprocess.run(["git", "-C", str(root), "diff", "--name-only", base, "HEAD", "--", str(APPS)],
                          capture_output=True, text=True, check=True, env=git_env)
    for name in proc.stdout.splitlines():
        path = pathlib.Path(name)
        if path.parent != APPS or path.suffix != ".yaml" or not (root / path).is_file():
            continue
        current = application((root / path).read_text(encoding="utf-8"))
        old = subprocess.run(["git", "-C", str(root), "show", f"{base}:{name}"],
                             capture_output=True, text=True, check=False, env=git_env)
        previous = application(old.stdout) if old.returncode == 0 else None
        if automated(current) and not automated(previous):
            yield name, current


def self_test() -> int:
    git_env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        comp = root / COMPONENTS / "example"
        comp.mkdir(parents=True)
        (root / "docs/threat-models").mkdir(parents=True)
        (root / "docs/threat-models/openbank-example-service.md").write_text("STRIDE " + "evidence " * 80)
        workload = {"kind": "Deployment", "spec": {"template": {"spec": {
            "containers": [{"name": "app", "image": "example/openbank-example-service:sandbox-test"},
                           {"name": "opa", "image": "openpolicyagent/opa:test"}],
            "volumes": [{"name": "opa-bundle", "configMap": {"name": "example-opa-bundle"}}],
        }}}}
        bundle = {"kind": "ConfigMap", "metadata": {"name": "example-opa-bundle"},
                  "data": {"rest.rego": "package openbank.rest"}}
        cluster = {"apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
                   "metadata": {"name": "example-db", "namespace": "example"},
                   "spec": {"backup": {"barmanObjectStore": {"destinationPath": "example://backup"}}}}
        backup = {"apiVersion": "postgresql.cnpg.io/v1", "kind": "ScheduledBackup",
                  "metadata": {"name": "example-daily", "namespace": "example"},
                  "spec": {"immediate": True, "cluster": {"name": "example-db"}}}
        app = {"kind": "Application", "spec": {"source": {"path": str(COMPONENTS / "example")},
                                                "syncPolicy": {"automated": {}}}}

        def write(*items):
            (comp / "all.yaml").write_text("---\n".join(yaml.safe_dump(item) for item in items))

        write(workload, bundle, cluster, backup)
        assert automated(app) and not automated({"spec": {"syncPolicy": {}}})
        assert not automated({"spec": {"syncPolicy": {"automated": {"enabled": False}}}})
        assert not check_app(root, app)
        app_file = root / APPS / "example.yaml"
        app_file.parent.mkdir(parents=True)
        staged = {"apiVersion": "argoproj.io/v1alpha1", **app}
        staged["spec"] = {**app["spec"], "syncPolicy": {}}
        app_file.write_text(yaml.safe_dump(staged))
        subprocess.run(["git", "init", "-q", str(root)], check=True, env=git_env)
        subprocess.run(["git", "-C", str(root), "-c", "user.name=fixture",
                        "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
                        "commit", "--allow-empty", "-qm", "before app"], check=True, env=git_env)
        initial = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"],
                                          text=True, env=git_env).strip()
        subprocess.run(["git", "-C", str(root), "add", str(APPS / "example.yaml")], check=True, env=git_env)
        subprocess.run(["git", "-C", str(root), "-c", "user.name=fixture",
                        "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
                        "commit", "-qm", "staged"], check=True, env=git_env)
        base = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"],
                                       text=True, env=git_env).strip()
        assert list(changed_apps(root, initial)) == []  # newly added but staged
        app_file.write_text(yaml.safe_dump({"apiVersion": "argoproj.io/v1alpha1", **app}))
        subprocess.run(["git", "-C", str(root), "add", str(APPS / "example.yaml")], check=True, env=git_env)
        subprocess.run(["git", "-C", str(root), "-c", "user.name=fixture",
                        "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
                        "commit", "-qm", "auto"], check=True, env=git_env)
        assert [name for name, _ in changed_apps(root, base)] == [str(APPS / "example.yaml")]
        write(workload, cluster, backup)
        assert any("OPA bundle" in item for item in check_app(root, app))
        write(workload, bundle, cluster)
        assert any("immediate ScheduledBackup" in item for item in check_app(root, app))
        backup["spec"]["immediate"] = False
        write(workload, bundle, cluster, backup)
        assert any("immediate ScheduledBackup" in item for item in check_app(root, app))
        backup["spec"]["immediate"] = True
        write(workload, bundle, cluster, backup)
        (root / "docs/threat-models/openbank-example-service.md").unlink()
        assert any("threat model" in item for item in check_app(root, app))
    print("self-test ok: staged, complete and three incomplete auto-sync fixtures")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.base:
        parser.error("--base is required for PR-time checking")
    failed = False
    count = 0
    try:
        # A PR with no new Application is normal, but an empty/unparseable apps
        # directory is not. Count the parsed fleet corpus for the gate's floor.
        subjects = sum(len([doc for doc in docs(path.read_text(encoding="utf-8"))
                            if doc.get("kind") == "Application"])
                       for path in (REPO / APPS).glob("*.yaml"))
        print(f"SUBJECTS={subjects}")
        for name, app in changed_apps(REPO, args.base):
            count += 1
            for finding in check_app(REPO, app):
                print(f"::error file={name}::{finding}")
                failed = True
    except (OSError, ValueError, yaml.YAMLError, subprocess.CalledProcessError) as exc:
        print(f"::error::new auto-sync Application audit failed closed: {type(exc).__name__}: {exc}")
        return 1
    print(f"new-auto-sync-app: {count} newly automated Application(s) checked")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
