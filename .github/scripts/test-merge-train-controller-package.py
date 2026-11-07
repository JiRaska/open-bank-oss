#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the privileged workflow's actual staging commands and isolated admission."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
HELPERS = ("merge-train.py", "check-security-checklist.py", "merge_train_carrier.py")
INVOKE = '''import importlib.util,json,pathlib,sys
spec=importlib.util.spec_from_file_location("staged_controller",sys.argv[1])
module=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=module
spec.loader.exec_module(module)
request=json.loads(pathlib.Path(sys.argv[2]).read_text())
print(json.dumps(module.admission_refusal(module.Git(request["root"]),request["base"],request["view"])))
'''


def run(args, cwd, env):
    result = subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True, check=False)
    if result.returncode:
        raise AssertionError(f"{args[0]} failed ({result.returncode}): {result.stderr}")
    return result.stdout.strip()


def main():
    workflow = yaml.safe_load((ROOT / ".github/workflows/merge-train.yml").read_text())
    template = (ROOT / ".github/PULL_REQUEST_TEMPLATE.md").read_text()
    with tempfile.TemporaryDirectory(prefix="controller-package-") as temporary:
        root = Path(temporary)
        repo = root / "repository"
        repo.mkdir()
        env = {"PATH": os.environ["PATH"], "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0"}

        def git(*args):
            return run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                        "-c", "commit.gpgsign=false", *args], repo, env)

        git("init", "-q", "-b", "main")
        for helper in HELPERS:
            target = repo / ".github/scripts" / helper
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / ".github/scripts" / helper).read_bytes())
        rules = repo / "openbank-libs/governance/rules.yaml"
        rules.parent.mkdir(parents=True)
        rules.write_text("money_path_services:\n  - paysvc\nsecurity_sensitive_paths: {}\n")
        git("add", "--", ".github/scripts", str(rules.relative_to(repo)))
        git("commit", "-qm", "trusted fixture")
        base = git("rev-parse", "HEAD")
        git("update-ref", "refs/remotes/origin/main", base)
        docs = repo / "docs/fixture.md"
        docs.parent.mkdir()
        docs.write_text("fixture\n")
        git("add", "--", "docs/fixture.md")
        git("commit", "-qm", "documentation fixture")
        docs_head = git("rev-parse", "HEAD")
        money = repo / "paysvc/src/main/Fixture.kt"
        money.parent.mkdir(parents=True)
        money.write_text("class Fixture\n")
        marker = root / "untrusted-executed"
        payload = f"from pathlib import Path\nPath({str(marker)!r}).touch()\nraise SystemExit(99)\n"
        (repo / ".github/scripts/check-security-checklist.py").write_text(payload)
        (repo / "re.py").write_text(payload)
        git("add", "--", "paysvc/src/main/Fixture.kt", ".github/scripts/check-security-checklist.py", "re.py")
        git("commit", "-qm", "untrusted money-path fixture")
        money_head = git("rev-parse", "HEAD")

        def admit(stage, head, body):
            request = root / "request.json"
            request.write_text(json.dumps({"root": str(repo), "base": base,
                                           "view": {"head_sha": head, "body": body}}))
            return json.loads(run([sys.executable, "-I", "-S", "-c", INVOKE,
                                   str(stage / "merge-train.py"), str(request)], repo, env))

        broken = root / "controller-only"
        broken.mkdir()
        (broken / "merge-train.py").write_bytes((ROOT / ".github/scripts/merge-train.py").read_bytes())
        assert admit(broken, docs_head, "fixture") == "admission check exited 2 — fail closed"
        for job, step_name in [("settle", "Judge carriers, merge or halve, post the fast lane"),
                               ("publish", "Rebuild with merge-tree, compare, push own commits, open the carrier")]:
            step = next(step for step in workflow["jobs"][job]["steps"] if step.get("name") == step_name)
            staging = step["run"].split('python3 -I -S "$RUNNER_TEMP/merge-train.py"', 1)[0]
            stage = root / job
            stage.mkdir()
            run(["bash", "-c", staging], repo, {**env, "RUNNER_TEMP": str(stage)})
            assert admit(stage, docs_head, "fixture") is None, job
            assert "security-checklist-money-path fails" in admit(stage, money_head, template), job
            assert admit(stage, money_head, template.replace("- [ ]", "- [x]")) is None, job
            assert not marker.exists(), "PR helper or working-directory module executed"
            print(f"PASS {job}: relocated controller admits docs; money checklist enforced; PR code never executed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
