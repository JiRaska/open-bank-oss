#!/usr/bin/env python3
"""Experimental full-fleet strict resolver. Not connected to CI.

Resolves all module configurations and root/included build-logic configurations.
Does not execute original testClasses/Quarkus tasks, so it cannot yet replace the
production metadata gate. Metadata is verified by Gradle, never regenerated.
"""
import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import time


def check_inventory(directory, expected):
    actual = {path.stem for path in directory.glob("*.json")}
    if actual != set(expected):
        raise ValueError("Incomplete or unexpected model receipt inventory")
    artifacts = set()
    configurations = 0
    for name in sorted(expected):
        model = json.loads((directory / (name + ".json")).read_text())
        if not isinstance(model, dict) or not isinstance(model.get("receipts"), dict):
            raise ValueError("Invalid model receipts")
        inventory = model.get("configurations")
        if (not isinstance(inventory, list)
                or not all(isinstance(item, str) and item for item in inventory)
                or len(inventory) != len(set(inventory))
                or set(inventory) != set(model["receipts"])):
            raise ValueError("Configuration inventory does not match resolved receipts")
        configurations += len(model["receipts"])
        for rows in model["receipts"].values():
            if not isinstance(rows, list):
                raise ValueError("Invalid configuration receipts")
            for row in rows:
                if (not isinstance(row, dict)
                        or not all(isinstance(row.get(key), str) and row[key]
                                   for key in ("component", "artifact", "sha256"))
                        or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"])):
                    raise ValueError("Invalid artifact receipt")
                artifacts.add((row["component"], row["artifact"], row["sha256"]))
    return configurations, artifacts


def module_batches(modules, size):
    if size < 1:
        raise ValueError("Batch size must be positive")
    return [modules[offset:offset + size] for offset in range(0, len(modules), size)]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch-size", type=int, default=16,
                        help="Maximum selected modules per project JVM (default: 16)")
    args = parser.parse_args(argv)
    if args.batch_size < 1:
        raise ValueError("Batch size must be positive")
    root = Path.cwd()
    modules = sorted(path.name for path in root.iterdir()
                     if path.is_dir() and path.name.startswith("openbank-")
                     and (path / "build.gradle.kts").is_file())
    if not modules:
        raise ValueError("No Gradle modules discovered")
    common = ["--dependency-verification", "strict", "--refresh-dependencies",
              "--no-configuration-cache", "--no-daemon", "--no-parallel",
              "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx8g", "--console=plain"]
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="global-metadata-") as directory:
        outputs = Path(directory)
        phases = [
            ("plugins", ".github/scripts/global-metadata-plugin-model.init.gradle",
             [":exportPluginMetadataModel", ":build-logic:exportPluginMetadataModel"],
             {"OB_PLUGIN_MODEL_DIR": str(outputs / "plugins")}, ["openbank", "build-logic"]),

        ]
        batches = module_batches(modules, args.batch_size)
        covered_modules = [name for batch in batches for name in batch]
        if covered_modules != modules or len(set(covered_modules)) != len(modules):
            raise ValueError("Incomplete or duplicated batch module inventory")
        phases.extend(
            (f"projects-{index + 1}", ".github/scripts/global-metadata-project-model.init.gradle",
             ["exportMetadataScopeModel"],
             {"OB_METADATA_MODEL_DIR": str(outputs / f"projects-{index + 1}"),
              "OB_METADATA_PROJECTS": ",".join(":" + name for name in batch)}, batch)
            for index, batch in enumerate(batches)
        )
        union = set()
        for label, init, targets, overrides, expected in phases:
            phase = time.monotonic()
            env = dict(os.environ, GRADLE_OPTS="-Xmx1g", **overrides)
            result = subprocess.run(["./gradlew", "-I", init, *targets, *common], env=env)
            if result.returncode:
                print("Global resolver failed; no completeness verdict", flush=True)
                return 2
            count, artifacts = check_inventory(outputs / label, expected)
            union.update(artifacts)
            print(f"{label}: {count} configurations, {len(artifacts)} artifacts, "
                  f"{time.monotonic() - phase:.2f}s", flush=True)
        print(f"Strict resolution passed: {len(modules)} modules, {len(union)} artifacts, "
              f"{time.monotonic() - start:.2f}s; original task equivalence remains unproven")
    return 0


if __name__ == "__main__":
    try:
        code = main()
    except (OSError, ValueError) as error:
        print(f"Global resolver could not establish completeness: {error}")
        code = 2
    raise SystemExit(code)
