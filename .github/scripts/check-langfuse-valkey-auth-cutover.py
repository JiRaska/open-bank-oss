"""Block an automatic restart when Langfuse Valkey first gains AUTH (#11980)."""

import argparse
import subprocess
from pathlib import Path

import yaml

VALKEY = "openbank-infra/gitops/components/ai-platform/langfuse-valkey.yaml"
APP = "openbank-infra/gitops/apps/litellm.yaml"


def documents(source: str) -> list[dict]:
    return [doc for doc in yaml.safe_load_all(source) if isinstance(doc, dict)]


def valkey_deployment(source: str) -> dict:
    return next(
        doc for doc in documents(source)
        if doc.get("kind") == "Deployment"
        and doc.get("metadata", {}).get("name") == "langfuse-valkey"
    )


def requires_password(source: str) -> bool:
    deployment = valkey_deployment(source)
    container = next(
        item for item in deployment["spec"]["template"]["spec"]["containers"]
        if item["name"] == "valkey"
    )
    return "--requirepass" in container.get("args", [])


def authenticated_probe(source: str) -> bool:
    deployment = valkey_deployment(source)
    container = next(
        item for item in deployment["spec"]["template"]["spec"]["containers"]
        if item["name"] == "valkey"
    )
    command = " ".join(container.get("readinessProbe", {}).get("exec", {}).get("command", []))
    return "VALKEYCLI_AUTH" in command and "valkey-cli PING" in command


def automatic_sync(source: str) -> bool:
    app = next(doc for doc in documents(source) if doc.get("kind") == "Application")
    return "automated" in app.get("spec", {}).get("syncPolicy", {})


def cutover_error(old_valkey: str, new_valkey: str, old_app: str, new_app: str) -> str | None:
    if requires_password(old_valkey) or not requires_password(new_valkey):
        return None
    if not authenticated_probe(new_valkey):
        return "Valkey readiness must prove authenticated PING"
    if automatic_sync(old_app):
        return "a separately merged and verified pause of litellm auto-sync is required"
    if automatic_sync(new_app):
        return "litellm auto-sync must remain paused during the AUTH cutover"
    return None


def read_base(base: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{base}:{path}"], check=True, capture_output=True, text=True
    )
    return result.stdout


def self_test() -> None:
    valkey = """kind: Deployment
metadata: {name: langfuse-valkey}
spec:
  template:
    spec:
      containers:
        - name: valkey
          args: [--requirepass, $(VALKEY_PASSWORD)]
          readinessProbe:
            exec:
              command: [sh, -ec, 'VALKEYCLI_AUTH="$VALKEY_PASSWORD" valkey-cli PING']
"""
    app_auto = "kind: Application\nspec: {syncPolicy: {automated: {selfHeal: true}}}\n"
    app_manual = "kind: Application\nspec: {syncPolicy: {retry: {limit: 10}}}\n"
    assert requires_password(valkey)
    assert authenticated_probe(valkey)
    assert not authenticated_probe(valkey.replace("VALKEYCLI_AUTH", "NO_AUTH"))
    assert not requires_password(valkey.replace("--requirepass", "--maxmemory"))
    assert automatic_sync(app_auto)
    assert not automatic_sync(app_manual)
    old_valkey = valkey.replace("--requirepass", "--maxmemory")
    assert cutover_error(old_valkey, valkey, app_auto, app_auto)
    assert cutover_error(old_valkey, valkey.replace("VALKEYCLI_AUTH", "NO_AUTH"), app_manual, app_manual)
    assert cutover_error(old_valkey, valkey, app_auto, app_manual)
    assert cutover_error(old_valkey, valkey, app_manual, app_auto)
    assert cutover_error(old_valkey, valkey, app_manual, app_manual) is None
    assert cutover_error(old_valkey, old_valkey, app_auto, app_auto) is None
    print("Langfuse Valkey cutover self-test: clean")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.base:
        parser.error("--base is required")

    old_valkey = read_base(args.base, VALKEY)
    new_valkey = Path(VALKEY).read_text()
    old_app = read_base(args.base, APP)
    new_app = Path(APP).read_text()
    # The old app state matters: changing this Application in the *same* PR could
    # race the root app's reconciliation and restart the emptyDir queue unannounced.
    error = cutover_error(old_valkey, new_valkey, old_app, new_app)
    if error:
        print(
            f"::error::Langfuse Valkey AUTH cutover: {error}. "
            "See docs/runbooks/0029-langfuse-valkey-auth-cutover.md (#11980)."
        )
        return 1

    print("Langfuse Valkey cutover: source guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
