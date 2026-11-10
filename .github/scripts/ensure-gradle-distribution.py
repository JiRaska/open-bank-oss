#!/usr/bin/env python3
"""Install the Gradle wrapper distribution ONCE per job, from the ADR-0366 lock, with bounded retry.

    python3 -I .github/scripts/ensure-gradle-distribution.py [--lock PATH] [--fetcher PATH]
    python3 -I .github/scripts/ensure-gradle-distribution.py --check-lock
    python3 -I .github/scripts/ensure-gradle-distribution.py --self-test

WHY (Services CI runs 42461736478, 42570587622, 42570652696; 2026-11-08/09)
Every `./gradlew` call downloads `distributionUrl` itself when the distribution is not yet in
`$GRADLE_USER_HOME/wrapper/dists`, with ONE attempt and a 10 s network timeout. In the contract
job that happened twice per job: the advisory warm-up step failed on
`services.gradle.org ... 504` (or `timeout (10000ms)`), and then "Discover provider Pact task"
asked the same URL again a few seconds later and failed the job. 36+ contract jobs went red at
once, and auto-deploy - which waits for a successful Services CI - skipped every service.

WHAT THIS DOES
It puts the distribution ZIP exactly where the wrapper looks for it,
`<GRADLE_USER_HOME>/wrapper/dists/<name>/<hash>/<name>.zip`, where <hash> is the wrapper's own
PathAssembler hash (base-36 MD5 of the distribution URL). The wrapper then unpacks the local ZIP
and never opens a connection. Bytes come from `fetch-artifact.py` (cache -> mirror -> upstream,
every tier checked against the sha256 in `ci/artifacts.lock`), and this script retries that
fetch with backoff because fetch-artifact deliberately gives up at the first 504 or timeout.
A distribution already installed (the wrapper's `.ok` marker) or a ZIP already present with the
pinned sha256 is reused and nothing is fetched.

TRUST
The pin is `distributionSha256Sum` in gradle-wrapper.properties (`--check-lock` fails without
it; an older provider ref without it is skipped with a notice). The lock entry must carry the same URL and sha256 (`--check-lock`, gate
`gradle-distribution-pinned`). When they disagree - a historical provider ref built with an
older wrapper - this script installs nothing and says so; the wrapper then downloads as before.

EXIT CODES  0 installed or reused (or skipped with a notice); 75 unavailable after every
attempt (infrastructure; the line `UNVERIFIED (artifact unavailable): gradle-distribution` is
what spot-kill-retry.sh recognises); 65 integrity failure (never retried); 2 usage/config.
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
NAME = "gradle-distribution"
EXIT_UNAVAILABLE, EXIT_INTEGRITY, EXIT_USAGE = 75, 65, 2
PROPS = pathlib.Path("gradle/wrapper/gradle-wrapper.properties")


def _say(msg: str) -> None:
    print(f"ensure-gradle-distribution: {msg}", flush=True)


# Workflow commands must START the line to become annotations. spot-kill-retry.sh matches the
# rendered `##[error]ensure-gradle-distribution: could not obtain ...` line exactly.
def _err(msg: str) -> None:
    print(f"::error::ensure-gradle-distribution: {msg}", flush=True)


def _note(msg: str) -> None:
    print(f"::notice::ensure-gradle-distribution: {msg}", flush=True)


def read_props(path: pathlib.Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, val = line.split("=", 1)
        out[key.strip()] = val.strip().replace("\\:", ":").replace("\\=", "=")
    return out


def wrapper_hash(url: str) -> str:
    """org.gradle.wrapper.PathAssembler#getHash: new BigInteger(1, md5(uri)).toString(36)."""
    n = int(hashlib.md5(url.encode("utf-8")).hexdigest(), 16)
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    s = ""
    while n:
        n, r = divmod(n, 36)
        s = digits[r] + s
    return s or "0"


def zip_path(gradle_home: pathlib.Path, url: str) -> pathlib.Path:
    base = url.rstrip("/").rsplit("/", 1)[-1]  # gradle-9.8.2-bin.zip
    name = base[:-4] if base.endswith(".zip") else base
    return gradle_home / "wrapper" / "dists" / name / wrapper_hash(url) / base


def sha256_of(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def lock_entry(lock: pathlib.Path) -> dict | None:
    data = json.loads(lock.read_text(encoding="utf-8"))
    for art in data.get("artifacts", []):
        if art.get("name") == NAME:
            return art.get("platforms", {}).get("any")
    return None


def pin(props: dict[str, str]) -> tuple[str, str]:
    url = props.get("distributionUrl", "")
    sha = props.get("distributionSha256Sum", "")
    if not url.startswith("https://"):
        raise ValueError(f"distributionUrl must be https://, got {url!r}")
    if not re.fullmatch(r"[0-9a-f]{64}", sha):
        raise ValueError("distributionSha256Sum is missing or not a sha256 - the wrapper download is unpinned")
    return url, sha


def ensure(gradle_home: pathlib.Path, props_path: pathlib.Path, lock: pathlib.Path,
           fetcher: pathlib.Path, attempts: int, backoff: float) -> int:
    try:
        url, sha = pin(read_props(props_path))
    except (OSError, ValueError) as exc:
        # A provider ref older than the pin (added 2026-07) must still build: nothing to
        # verify against, so install nothing and let the wrapper behave as it always did.
        # The CURRENT tree is held to the pin by --check-lock (gate gradle-distribution-pinned).
        _note(f"{exc} - leaving the download to the wrapper")
        return 0
    target = zip_path(gradle_home, url)
    if (target.parent / (target.name + ".ok")).is_file():
        _say(f"reusing installed distribution under {target.parent} (no download)")
        return 0
    if target.is_file() and sha256_of(target) == sha:
        _say(f"reusing pinned zip {target.name} already in the wrapper cache (no download)")
        return 0
    entry = lock_entry(lock) if lock.is_file() else None
    if not entry or entry.get("url") != url or entry.get("sha256") != sha:
        _note(f"{NAME} in {lock.name} does not pin {url} at the wrapper's sha256 "
             "(an older provider ref?) - leaving the download to the wrapper")
        return 0
    rc = EXIT_UNAVAILABLE
    for attempt in range(1, attempts + 1):
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp) / target.name
            rc = subprocess.run([sys.executable, "-I", str(fetcher), NAME, "--lock", str(lock),
                                 "--out", str(out)], check=False).returncode
            if rc == 0:
                if sha256_of(out) != sha:  # defence in depth: the fetcher already checked
                    _err(f"INTEGRITY FAILURE: fetched {target.name} does not match the pin")
                    return EXIT_INTEGRITY
                target.parent.mkdir(parents=True, exist_ok=True)
                # Unique temp name: two jobs for one service may share a persistent home.
                fd, part = tempfile.mkstemp(dir=target.parent, prefix=target.name + ".", suffix=".part")
                os.close(fd)
                shutil.copyfile(out, part)
                os.replace(part, target)  # atomic: a concurrent wrapper never sees half a zip
                _say(f"installed {target.name} (attempt {attempt}/{attempts}) at {target}")
                return 0
        if rc == EXIT_INTEGRITY:
            _err(f"INTEGRITY FAILURE for {NAME}; not retrying")
            return EXIT_INTEGRITY
        if rc != EXIT_UNAVAILABLE:
            _err(f"fetch-artifact exited {rc} (usage/config); not retrying")
            return rc
        if attempt < attempts:
            wait = backoff * (3 ** (attempt - 1))
            _say(f"attempt {attempt}/{attempts} unavailable; retrying in {wait:.0f}s")
            time.sleep(wait)
    print(f"UNVERIFIED (artifact unavailable): {NAME}", flush=True)
    _err(f"could not obtain {url} after {attempts} attempts (infrastructure, not a build failure)")
    return EXIT_UNAVAILABLE


def check_lock(props_path: pathlib.Path, lock: pathlib.Path) -> int:
    try:
        url, sha = pin(read_props(props_path))
    except (OSError, ValueError) as exc:
        print(f"::error::{exc}")
        return 1
    entry = lock_entry(lock)
    if not entry or entry.get("url") != url or entry.get("sha256") != sha:
        print(f"::error::{lock}: entry {NAME!r} must pin url={url} sha256={sha} (gradle-wrapper.properties). "
              "After a wrapper bump, update the lock entry in the same PR.")
        return 1
    print(f"OK {NAME} pinned in the lock at the wrapper's sha256")
    return 0


# ── SELF-TEST ────────────────────────────────────────────────────────────────────────────────
def self_test() -> int:
    passed = failed = 0
    url = "https://services.gradle.org/distributions/gradle-9.8.2-bin.zip"

    def check(name: str, ok: bool, detail: str = "") -> None:
        nonlocal passed, failed
        if ok:
            passed += 1
            print(f"PASS  {name}")
        else:
            failed += 1
            print(f"FAIL  {name} {detail}")

    # Known vector, measured: a real wrapper installed this URL under this directory name.
    check("hash matches the wrapper's real directory name",
          wrapper_hash(url) == "8wepis63pzuhrlfaiy04bagwu", wrapper_hash(url))

    with tempfile.TemporaryDirectory() as tmpd:
        tmp = pathlib.Path(tmpd)
        payload = b"PK-fake-gradle-distribution"
        good = hashlib.sha256(payload).hexdigest()
        src = tmp / "payload.zip"
        src.write_bytes(payload)
        fetcher = tmp / "fake_fetch.py"
        # Scripted fetcher: FAKE_PLAN is a comma list of exit codes, one per call.
        fetcher.write_text(
            "import os,sys,shutil\n"
            "log=os.environ['FAKE_LOG']\n"
            "n=sum(1 for _ in open(log)) if os.path.exists(log) else 0\n"
            "open(log,'a').write('call\\n')\n"
            "plan=os.environ['FAKE_PLAN'].split(',')\n"
            "rc=int(plan[min(n,len(plan)-1)])\n"
            "if rc==0: shutil.copyfile(os.environ['FAKE_SRC'], sys.argv[sys.argv.index('--out')+1])\n"
            "sys.exit(rc)\n", encoding="utf-8")

        def setup(sha: str | None = good, lock_sha: str | None = good) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
            case = pathlib.Path(tempfile.mkdtemp(dir=tmp))
            props = case / "gradle-wrapper.properties"
            lines = [f"distributionUrl={url.replace(':', chr(92) + ':')}"]
            if sha:
                lines.append(f"distributionSha256Sum={sha}")
            props.write_text("\n".join(lines) + "\n", encoding="utf-8")
            lock = case / "artifacts.lock"
            arts = [] if lock_sha is None else [{"name": NAME, "kind": "binary-archive", "version": "9.8.2",
                                                 "platforms": {"any": {"url": url, "sha256": lock_sha}}}]
            lock.write_text(json.dumps({"schema": 1, "artifacts": arts}), encoding="utf-8")
            return case / "home", props, lock

        def run(plan: str, home: pathlib.Path, props: pathlib.Path, lock: pathlib.Path) -> tuple[int, int]:
            log = home.parent / "calls.log"
            if log.exists():
                log.unlink()
            os.environ.update(FAKE_PLAN=plan, FAKE_LOG=str(log), FAKE_SRC=str(src))
            rc = ensure(home, props, lock, fetcher, attempts=3, backoff=0)
            calls = len(log.read_text().splitlines()) if log.exists() else 0
            return rc, calls

        home, props, lock = setup()
        rc, calls = run("0", home, props, lock)
        target = zip_path(home, url)
        check("cold home: fetched once and placed where the wrapper looks",
              rc == 0 and calls == 1 and target.is_file() and sha256_of(target) == good, f"rc={rc} calls={calls}")
        rc, calls = run("0", home, props, lock)
        check("second call in the same job reuses the zip: NO second fetch", rc == 0 and calls == 0,
              f"rc={rc} calls={calls}")

        home, props, lock = setup()
        marker = zip_path(home, url)
        marker.parent.mkdir(parents=True)
        (marker.parent / (marker.name + ".ok")).write_text("")
        rc, calls = run("0", home, props, lock)
        check("installed distribution (.ok marker) is reused, nothing fetched", rc == 0 and calls == 0,
              f"rc={rc} calls={calls}")

        home, props, lock = setup()
        bad = zip_path(home, url)
        bad.parent.mkdir(parents=True)
        bad.write_bytes(b"truncated")
        rc, calls = run("0", home, props, lock)
        check("a corrupt cached zip is replaced, not trusted", rc == 0 and calls == 1 and sha256_of(bad) == good,
              f"rc={rc} calls={calls}")

        home, props, lock = setup()
        rc, calls = run("75,75,0", home, props, lock)
        check("504/timeout twice then success: bounded retry recovers", rc == 0 and calls == 3,
              f"rc={rc} calls={calls}")

        home, props, lock = setup()
        rc, calls = run("75", home, props, lock)
        check("unavailable on every attempt: exit 75 after exactly 3 attempts", rc == 75 and calls == 3
              and not zip_path(home, url).exists(), f"rc={rc} calls={calls}")

        home, props, lock = setup()
        rc, calls = run("65", home, props, lock)
        check("integrity failure is never retried", rc == 65 and calls == 1, f"rc={rc} calls={calls}")

        home, props, lock = setup()
        os.environ["FAKE_SRC"] = str(src)
        other = tmp / "other.zip"
        other.write_bytes(b"different bytes")
        os.environ.update(FAKE_PLAN="0", FAKE_LOG=str(home.parent / "c.log"), FAKE_SRC=str(other))
        rc = ensure(home, props, lock, fetcher, attempts=3, backoff=0)
        check("bytes not matching the wrapper pin are rejected (65), never installed",
              rc == 65 and not zip_path(home, url).exists(), f"rc={rc}")

        home, props, lock = setup(sha=None)
        rc, calls = run("0", home, props, lock)
        check("ref without distributionSha256Sum: skip with notice, no fetch", rc == 0 and calls == 0
              and not zip_path(home, url).exists(), f"rc={rc}")
        check("--check-lock fails when distributionSha256Sum is missing", check_lock(props, lock) == 1)

        home, props, lock = setup(lock_sha="0" * 64)
        rc, calls = run("0", home, props, lock)
        check("lock disagreeing with the wrapper (older ref): skip, no fetch", rc == 0 and calls == 0
              and not zip_path(home, url).exists(), f"rc={rc} calls={calls}")

        home, props, lock = setup(lock_sha="0" * 64)
        check("--check-lock fails on a lock that does not pin the wrapper sha", check_lock(props, lock) == 1)
        home, props, lock = setup()
        check("--check-lock passes on a matching lock", check_lock(props, lock) == 0)

    print(f"SUBJECTS={passed + failed}")
    print(f"ensure-gradle-distribution self-test: {passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


def main(argv: list[str]) -> int:
    if argv[:1] == ["--self-test"]:
        return self_test()
    lock = pathlib.Path(os.environ.get("OB_ARTIFACT_LOCK", REPO / "ci" / "artifacts.lock"))
    fetcher = HERE / "fetch-artifact.py"
    it = iter(argv)
    mode = "ensure"
    for arg in it:
        if arg == "--lock":
            lock = pathlib.Path(next(it))
        elif arg == "--fetcher":
            fetcher = pathlib.Path(next(it))
        elif arg == "--check-lock":
            mode = "check"
        else:
            print(f"usage: unknown argument {arg!r}", file=sys.stderr)
            return EXIT_USAGE
    props = pathlib.Path.cwd() / PROPS
    if mode == "check":
        print("SUBJECTS=1")
        return check_lock(REPO / PROPS, lock)
    home = os.environ.get("GRADLE_USER_HOME") or str(pathlib.Path.home() / ".gradle")
    return ensure(pathlib.Path(home), props, lock, fetcher,
                  attempts=int(os.environ.get("OB_GRADLE_DIST_ATTEMPTS", "3")),
                  backoff=float(os.environ.get("OB_GRADLE_DIST_BACKOFF", "10")))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
