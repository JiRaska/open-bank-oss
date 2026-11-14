#!/usr/bin/env python3
"""Check that every pinned apt `pkg=version` in a tracked Dockerfile still resolves (#15483).

WHY THIS EXISTS
  `.github/workflows/Dockerfile.deploy` (the runtime recipe of every service image) pins
  security fixes as `apt-get install --only-upgrade pkg=version`. Ubuntu's archive index
  carries only the NEWEST version of a package per pocket, so the day a newer fix is
  published, the pinned version can drop out of the index and every image build fails with
  `E: Version '<v>' for '<pkg>' was not found`. That arrives on a date nobody chose, with no
  warning, on the train and on every PR at once.

  The early warning is SUPERSESSION: once the archive offers a newer candidate than the pin,
  the pin survives only while some other pocket (release, -updates, -security) still
  carries the old version. That is the state in which to bump, before it vanishes. So:

    OK          the pinned version is the newest the archive offers
    SUPERSEDED  the pinned version still resolves, but a newer one exists -> bump soon
    MISSING     the pinned version is not in the archive -> image builds are broken now

  Ubuntu does not publish removal dates, so no checker can promise "N days ahead". What a
  daily run does buy: the window between "a newer version was published" and "the old one
  left every pocket" — usually days, because -security and -updates are refreshed apart.

A RUN THAT COULD NOT ASK IS NOT A CLEAN RUN
  The two CI failures that prompted this (2026-11-11) printed `Version ... was not found`
  for all four pins, which reads exactly like vanished pins. They were not: the log above it
  said `Could not resolve 'archive.ubuntu.com'` — apt never fetched an index, so every pin
  looked absent. This checker therefore refreshes the index with `--error-on=any` and treats
  a failed refresh as UNDETERMINABLE (exit 3), never as MISSING and never as OK.

Exit codes: 0 all OK; 1 usage/parse error; 2 a pin is SUPERSEDED or MISSING; 3 undeterminable.
Self-test: `--self-test` runs offline over fixtures and proves each verdict is reachable.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

PIN_RE = re.compile(r"^([a-z0-9][a-z0-9.+-]*(?::[a-z0-9]+)?)=([0-9][A-Za-z0-9.+~:-]*)$")


@dataclass(frozen=True)
class Pin:
    dockerfile: str
    image: str
    package: str
    version: str


def logical_lines(text: str) -> list[str]:
    """Join backslash continuations and drop comment lines."""
    out, buf = [], ""
    for raw in text.splitlines():
        line = raw.rstrip()
        if not buf and line.lstrip().startswith("#"):
            continue
        if line.endswith("\\"):
            buf += line[:-1] + " "
            continue
        buf += line
        out.append(buf)
        buf = ""
    if buf:
        out.append(buf)
    return out


def parse_pins(dockerfile: str, text: str) -> list[Pin]:
    """Every `pkg=version` passed to `apt-get install`, attributed to the image it runs in."""
    pins: list[Pin] = []
    stages: dict[str, str] = {}
    image = ""
    for line in logical_lines(text):
        words = line.split()
        if not words:
            continue
        if words[0].upper() == "FROM":
            args = [w for w in words[1:] if not w.startswith("--")]
            base = args[0]
            image = stages.get(base, base)  # FROM <earlier stage> runs in that stage's image
            if len(args) >= 3 and args[1].upper() == "AS":
                stages[args[2]] = image
            continue
        if words[0].upper() != "RUN" or "apt-get" not in line:
            continue
        for cmd in re.split(r"&&|;|\|\|", line):
            toks = cmd.split()
            if "apt-get" not in toks or "install" not in toks:
                continue
            for tok in toks[toks.index("install") + 1:]:
                m = PIN_RE.match(tok)
                if m:
                    pins.append(Pin(dockerfile, image, m.group(1), m.group(2)))
    return pins


def parse_madison(output: str) -> dict[str, list[str]]:
    """`apt-cache madison` lines: ` pkg | version | source` -> {pkg: [versions...]}."""
    found: dict[str, list[str]] = {}
    for line in output.splitlines():
        parts = [p.strip() for p in line.split("|")]
        if len(parts) >= 3 and parts[0] and parts[1]:
            found.setdefault(parts[0], []).append(parts[1])
    return found


def classify(pin: Pin, madison: dict[str, list[str]], newest: dict[str, str]) -> str:
    """newest[pkg] is apt's own candidate (`apt-cache policy`), so ordering is apt's, not ours."""
    if pin.version not in madison.get(pin.package, []):
        return "MISSING"
    cand = newest.get(pin.package)
    if cand and cand != pin.version:
        return "SUPERSEDED"
    return "OK"


PROBE = r"""set -eu
apt-get -o Acquire::Retries=3 update --error-on=any >/dev/null 2>/tmp/upd.err || { cat /tmp/upd.err >&2; exit 97; }
echo '@@MADISON'
apt-cache madison "$@"
echo '@@CANDIDATE'
for p in "$@"; do printf '%s %s\n' "$p" "$(apt-cache policy "$p" | awk '/Candidate:/{print $2}')"; done
"""


def probe_image(image: str, packages: list[str]) -> tuple[dict[str, list[str]], dict[str, str]]:
    """Ask the archive from INSIDE the pinned base image, so the sources list is the real one."""
    cmd = ["docker", "run", "--rm", "--user", "0", "--entrypoint", "sh", image, "-c", PROBE, "probe", *packages]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if res.returncode != 0:
        raise RuntimeError(f"could not query the archive from {image} (exit {res.returncode}): "
                           f"{res.stderr.strip()[-600:]}")
    mad, _, cand = res.stdout.partition("@@CANDIDATE")
    madison = parse_madison(mad.split("@@MADISON", 1)[-1])
    newest = {}
    for line in cand.splitlines():
        bits = line.split()
        if len(bits) == 2 and bits[1] != "(none)":
            newest[bits[0]] = bits[1]
    return madison, newest


def tracked_dockerfiles(root: Path) -> list[Path]:
    out = subprocess.run(["git", "-C", str(root), "ls-files"], capture_output=True, text=True, check=True)
    return [root / p for p in out.stdout.splitlines() if re.search(r"(^|/)Dockerfile[^/]*$", p)]


def run(root: Path, files: list[Path]) -> int:
    pins: list[Pin] = []
    for f in files:
        pins += parse_pins(str(f.relative_to(root)), f.read_text())
    if not pins:
        print("No apt version pins found in any tracked Dockerfile.")
        return 0
    worst = 0
    by_image: dict[str, list[Pin]] = {}
    for p in pins:
        by_image.setdefault(p.image, []).append(p)
    for image, group in by_image.items():
        try:
            madison, newest = probe_image(image, sorted({p.package for p in group}))
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            print(f"UNDETERMINABLE {image}: {e}")
            worst = max(worst, 3)
            continue
        for p in group:
            verdict = classify(p, madison, newest)
            extra = f" (archive now offers {newest.get(p.package, '?')})" if verdict != "OK" else ""
            print(f"{verdict:10} {p.dockerfile}: {p.package}={p.version}{extra}")
            if verdict != "OK":
                worst = max(worst, 2) if worst != 3 else 3
    print(f"{len(pins)} pin(s) checked across {len(by_image)} base image(s).")
    return worst


def self_test() -> int:
    failures = []

    def expect(name, got, want):
        if got != want:
            failures.append(f"{name}: got {got!r}, want {want!r}")

    df = (
        "# apt-get install -y ignored=1.0 in a comment\n"
        "FROM ubuntu:26.04@sha256:abc AS base\n"
        "RUN apt-get update \\\n"
        " && apt-get install -y --no-install-recommends --only-upgrade \\\n"
        "      apt=3.2.1ubuntu0.1 \\\n"
        "      zlib1g=1:1.3.dfsg+really1.3.1-1ubuntu3.2 \\\n"
        "      curl \\\n"
        " && rm -rf /var/lib/apt/lists/*\n"
        "FROM base AS runtime\n"
        "RUN apt-get install -y libbrotli1=1.2.0-3ubuntu0.1 && echo pkg=9.9\n"
    )
    pins = parse_pins("Dockerfile", df)
    expect("parse: pins found", [(p.package, p.version) for p in pins],
           [("apt", "3.2.1ubuntu0.1"), ("zlib1g", "1:1.3.dfsg+really1.3.1-1ubuntu3.2"),
            ("libbrotli1", "1.2.0-3ubuntu0.1")])
    expect("parse: stage alias resolves to its image", pins[-1].image, "ubuntu:26.04@sha256:abc")
    expect("parse: unpinned and non-install tokens ignored", any(p.package in ("curl", "pkg", "ignored") for p in pins), False)

    madison = parse_madison(
        "       apt | 3.2.1ubuntu0.2 | http://archive.ubuntu.com/ubuntu resolute-security/main amd64 Packages\n"
        "       apt | 3.2.1ubuntu0.1 | http://archive.ubuntu.com/ubuntu resolute-updates/main amd64 Packages\n"
        "    zlib1g | 1:1.3.dfsg+really1.3.1-1ubuntu3.2 | http://archive.ubuntu.com/ubuntu resolute-security/main amd64 Packages\n"
    )
    newest = {"apt": "3.2.1ubuntu0.2", "zlib1g": "1:1.3.dfsg+really1.3.1-1ubuntu3.2", "libbrotli1": "1.2.0-3ubuntu0.3"}
    verdicts = {p.package: classify(p, madison, newest) for p in pins}
    # known-negative: the current candidate is OK
    expect("classify: current pin", verdicts["zlib1g"], "OK")
    # known-positive #1: still resolvable but superseded -> the early warning
    expect("classify: superseded pin", verdicts["apt"], "SUPERSEDED")
    # known-positive #2: gone from the index
    expect("classify: missing pin", verdicts["libbrotli1"], "MISSING")
    # An empty index (the DNS-failure shape) must never be read as OK.
    expect("classify: empty index is not OK", classify(pins[0], {}, {}), "MISSING")

    if failures:
        print("SELF-TEST FAILED:\n  " + "\n  ".join(failures))
        return 1
    # One subject per verdict assertion (OK, SUPERSEDED, MISSING, empty index).
    print("SUBJECTS=4")
    print("self-test OK: parse, OK, SUPERSEDED and MISSING verdicts all reachable")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("files", nargs="*", help="Dockerfiles to scan (default: every tracked Dockerfile)")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    root = Path(a.root).resolve()
    files = [Path(f).resolve() for f in a.files] or tracked_dockerfiles(root)
    return run(root, files)


if __name__ == "__main__":
    sys.exit(main())
