#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Real Quarkus schema-output cache regression; requires Git, JDK 25 and PyYAML.

Run explicitly (this compiles one service and its required libraries, not tests):
  python3 .github/scripts/test-generated-openapi-cache.py --work-dir /tmp/schema-proof

The new work directory retains evidence. HEAD supplies the fixture source; the current
convention plugin is copied over it so an uncommitted fix or mutation can be tested.
Only fixture-owned build output is removed. Dependency verification stays enabled.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PLUGIN = Path("build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts")
SERVICE = "openbank-sdd-service"
SCHEMA_PROPERTY = "quarkus.smallrye-openapi.store-schema-directory"


def run(command, cwd, env, log):
    with log.open("w") as output:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if result.returncode:
        raise AssertionError(f"command failed ({result.returncode}); see {log}\n{text[-6000:]}")
    return text


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path, help="new, disposable directory for clone/cache/logs")
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    # This fixture must neither read nor write an ambient remote build cache.
    for key in tuple(env):
        if key.startswith("GRADLE_REMOTE_CACHE_"):
            del env[key]
    env["SOURCE_COMMIT"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    repo = work / "repo"
    run(["git", "clone", "--shared", "--no-checkout", str(ROOT), str(repo)], ROOT, env, work / "clone.log")
    run(["git", "checkout", "--detach", env["SOURCE_COMMIT"]], repo, env, work / "checkout.log")
    shutil.copy2(ROOT / PLUGIN, repo / PLUGIN)
    init = work / "schema-cache.init.gradle"
    init.write_text('''settingsEvaluated { s ->
    s.buildCache {
        local { directory = new File(System.getProperty('schemaRegression.cacheDir')); enabled = true }
        if (remote != null) { remote.enabled = false }
    }
}
gradle.beforeProject { p ->
    if (p.path == ':openbank-sdd-service') {
        p.tasks.register('schemaRegressionOutputs') {
            def producer = p.tasks.named('quarkusAppPartsBuild')
            doLast {
                def paths = producer.get().outputs.files.files.collect { it.absolutePath }.sort()
                new File(System.getProperty('schemaRegression.outputs')).text = groovy.json.JsonOutput.toJson(paths)
            }
        }
    }
}
''')
    base = [str(repo / "gradlew"), "--configure-on-demand", "--build-cache", "--console=plain",
            "-I", str(init), f"-DschemaRegression.cacheDir={work / 'cache'}"]
    outputs = work / "no-export-outputs.json"
    run(base + [f":{SERVICE}:schemaRegressionOutputs", f"-DschemaRegression.outputs={outputs}"],
        repo, env, work / "no-export.log")
    require(json.loads(outputs.read_text()) == [str(repo / SERVICE / "build/quarkus-build/app")],
            "no-export producer output contract changed")
    command = base + [f":{SERVICE}:quarkusBuild", f"-D{SCHEMA_PROPERTY}=build/openapi-generated"]
    schema = repo / SERVICE / "build/openapi-generated/openapi.yaml"
    runner = repo / SERVICE / "build/quarkus-app/quarkus-run.jar"
    checker = [sys.executable, str(repo / ".github/scripts/check-openapi-request-schema-conformance.py"),
               "--service", SERVICE, "--generated", str(schema), "--enforce"]
    cold = run(command, repo, env, work / "cold-build.log")
    require(re.search(r"> Task :openbank-sdd-service:quarkusAppPartsBuild\s*\n", cold),
            "fresh private cache did not execute the real producer")
    require(schema.is_file() and runner.is_file(), "cold build did not produce schema and fast-jar")
    digest = hashlib.sha256(schema.read_bytes()).hexdigest()
    run(checker, repo, env, work / "cold-conformance.log")
    # The clone and this build directory were created above, never supplied by the caller.
    shutil.rmtree(repo / SERVICE / "build")
    restored = run(command, repo, env, work / "restore-build.log")
    require(f"> Task :{SERVICE}:quarkusAppPartsBuild FROM-CACHE" in restored,
            "normal second build did not restore the actual producer from cache")
    require(runner.is_file(), "cache hit did not restore fast-jar")
    run(checker, repo, env, work / "restore-conformance.log")
    require(schema.is_file(), "cache hit did not restore schema")
    require(hashlib.sha256(schema.read_bytes()).hexdigest() == digest, "restored schema bytes changed")
    require(not re.search(r"> Task [^\n]*:(?:test|providerPactTest)(?:\s|$)", cold + restored),
            "producer regression unexpectedly executed service tests")
    print(f"PASS: no-export outputs preserved; cold schema and normal cache restore match ({digest}); "
          f"both enforced conformance checks passed. Evidence: {work}")


if __name__ == "__main__":
    main()
