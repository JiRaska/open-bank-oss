# SPDX-License-Identifier: Apache-2.0
"""Keep provider diagnostics off public CI channels without suppressing failures.

Raw output stays in a mode-0600 runner temporary file. It is never uploaded as a
public artifact. Operators must review a plan privately before dispatching apply;
the fixed public result below is not a substitute for that review.
"""
import argparse
import os
import subprocess
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True,
                        choices=("init", "preview", "plan", "evidence-guard", "apply"))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required")
    # Provider subprocesses have no reason to write directly to GitHub's public
    # summaries or control files. Preserve all other provider/credential inputs.
    child_env = dict(os.environ)
    for name in ("GITHUB_STEP_SUMMARY", "GITHUB_OUTPUT", "GITHUB_ENV", "GITHUB_PATH"):
        child_env.pop(name, None)
    try:
        fd, _ = tempfile.mkstemp(prefix=f"private-platform-{args.stage}-",
                                 suffix=".log", dir=os.environ.get("RUNNER_TEMP"))
        with os.fdopen(fd, "wb") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    env=child_env, check=False)
        code = result.returncode if result.returncode >= 0 else 128 - result.returncode
    except (OSError, ValueError):
        # Exception text can contain private paths, identifiers or provider data.
        print("::error::Private platform command could not execute or capture output.")
        return 71
    success = code == 0 or (args.stage == "preview" and code == 2)
    if success:
        outcome = "Changes pending." if code == 2 else "Command succeeded."
    else:
        outcome = f"Command failed (exit {code})."
    message = f"Platform {args.stage}: {outcome} Detailed output is private."
    print(message)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        try:
            with Path(summary).open("a", encoding="utf-8") as stream:
                stream.write(message + "\n")
        except OSError:
            print("::error::Could not write the fixed platform result summary.")
            return 72
    return 0 if success else code


if __name__ == "__main__":
    raise SystemExit(main())
