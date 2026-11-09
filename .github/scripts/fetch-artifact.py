#!/usr/bin/env python3
"""Fetch a CI artifact pinned in `ci/artifacts.lock` (#15216).

    python3 -I .github/scripts/fetch-artifact.py <name> [--platform P] --out PATH
    python3 -I .github/scripts/fetch-artifact.py <name> --show version

The lock is the only trust anchor. Whichever tier serves the bytes, they are accepted only when
their sha256 equals the lock's digest; a mismatching tier is REJECTED and the next tier is tried.
Tiers, in fixed order:

  1. cache     `$OB_ARTIFACT_CACHE/<sha256>`; CI restores the directory with actions/cache.
  2. mirror    `$OB_ARTIFACT_MIRROR`, an OCI registry base such as `https://<registry>/<ns>`,
               read anonymously over the OCI distribution API. Unset means the tier is skipped
               (the mirror itself lands in a later step of the ADR rollout).
  3. upstream  the lock's URL: https:// directly, oci:// over the same OCI API.

Exit codes are part of the contract:
  0   delivered and verified
  75  UNVERIFIED (artifact unavailable): no tier could deliver bytes at all. Infrastructure, not
      a finding; callers must still fail, but must say which of the two it was.
  65  INTEGRITY: some tier delivered bytes, every delivery mismatched the lock. Never an outage.
  2   usage error, unknown name or platform, unreadable lock.

Stdlib only, and meant to run under `python3 -I`, so nothing in the working tree can shadow it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import platform as _platform
import re
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

EXIT_UNAVAILABLE = 75
EXIT_INTEGRITY = 65
EXIT_USAGE = 2

DEFAULT_LOCK = pathlib.Path(__file__).resolve().parents[2] / "ci" / "artifacts.lock"
OCI_ACCEPT = ", ".join([
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])
HELM_LAYER = "application/vnd.cncf.helm.chart.content.v1.tar+gzip"


class Unavailable(Exception):
    """The tier could not deliver bytes (network, 404, auth)."""


def _log(msg: str) -> None:
    print(f"fetch-artifact: {msg}", file=sys.stderr)


def _retries() -> int:
    return max(1, int(os.environ.get("OB_ARTIFACT_RETRIES", "3")))


def _timeout() -> float:
    # Per attempt. A healthy download takes seconds; during the 2026-11-08 frontend outage a
    # request hung ~60 s per try, which is what pushed helm-values-key-effect past its 420 s
    # hard timeout (#15220). 30 s bounds the cost of a dead route.
    return float(os.environ.get("OB_ARTIFACT_TIMEOUT", "30"))


class _DropAuthOnCrossHostRedirect(urllib.request.HTTPRedirectHandler):
    """Follow redirects, but never forward Authorization to a different host (curl -L does the
    same). The release-asset API redirects to a pre-signed object URL that must not get the token."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None and urllib.parse.urlsplit(newurl).netloc != urllib.parse.urlsplit(req.full_url).netloc:
            new.headers.pop("Authorization", None)
            new.unredirected_hdrs.pop("Authorization", None)
        return new


_OPENER = urllib.request.build_opener(_DropAuthOnCrossHostRedirect)


def _http_get(url: str, headers: dict[str, str] | None = None) -> tuple[bytes, dict[str, str]]:
    """GET with bounded retries. Raises Unavailable; returns (body, lower-cased headers)."""
    last = ""
    attempts = _retries()
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with _OPENER.open(req, timeout=_timeout()) as resp:
                return resp.read(), {k.lower(): v for k, v in resp.headers.items()}
        except urllib.error.HTTPError as exc:
            if exc.code == 401:
                raise _AuthRequired({k.lower(): v for k, v in exc.headers.items()}) from None
            last = f"HTTP {exc.code}"
            if exc.code == 504 or (exc.code < 500 and exc.code != 429):
                break  # a 404 will not heal, and a 504 is an outage, not a flake (#15220)
        except (TimeoutError, OSError) as exc:
            reason = getattr(exc, "reason", exc)
            last = str(reason)
            if isinstance(exc, TimeoutError) or isinstance(reason, TimeoutError):
                break  # a hung route will not recover within the caller's budget
        if attempt < attempts:
            time.sleep(float(os.environ.get("OB_ARTIFACT_BACKOFF", "2")) * attempt)
    raise Unavailable(f"{url}: {last}")


class _AuthRequired(Exception):
    def __init__(self, headers: dict[str, str]):
        super().__init__("401")
        self.headers = headers


def _anonymous_token(challenge: str) -> str:
    """Resolve a `Bearer realm=...,service=...,scope=...` challenge into an anonymous token."""
    if not challenge.lower().startswith("bearer "):
        raise Unavailable(f"unsupported auth challenge: {challenge[:80]}")
    params = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
    realm = params.pop("realm", "")
    if not realm.startswith(("https://", "http://")):
        raise Unavailable("auth challenge without a usable realm")
    body, _ = _http_get(realm + "?" + urllib.parse.urlencode(params))
    data = json.loads(body)
    return data.get("token") or data.get("access_token") or ""


def _oci_get(url: str, accept: str, token: list[str]) -> bytes:
    headers = {"Accept": accept}
    if token[0]:
        headers["Authorization"] = f"Bearer {token[0]}"
    try:
        return _http_get(url, headers)[0]
    except _AuthRequired as exc:
        token[0] = _anonymous_token(exc.headers.get("www-authenticate", ""))
        headers["Authorization"] = f"Bearer {token[0]}"
        try:
            return _http_get(url, headers)[0]
        except _AuthRequired:
            raise Unavailable(f"{url}: anonymous pull refused") from None


def oci_pull(registry: str, repository: str, tag: str) -> bytes:
    """Pull the single content layer of an OCI artifact anonymously.

    `registry` is scheme+host (`https://host`). Helm charts carry a layer of the Helm chart
    media type; an artifact pushed with `oras push` carries exactly one layer. Either way the
    layer bytes are what the lock pins.
    """
    token = [""]
    manifest = json.loads(_oci_get(f"{registry}/v2/{repository}/manifests/{tag}", OCI_ACCEPT, token))
    layers = manifest.get("layers") or []
    chosen = [layer for layer in layers if layer.get("mediaType") == HELM_LAYER] or layers
    if len(chosen) != 1:
        raise Unavailable(f"{repository}:{tag}: expected one content layer, found {len(layers)}")
    digest = chosen[0]["digest"]
    return _oci_get(f"{registry}/v2/{repository}/blobs/{digest}", "*/*", token)


def _split_oci(ref: str) -> tuple[str, str, str]:
    """`oci://host/path/chart` + version tag -> (https://registry, repository, '')."""
    rest = ref.removeprefix("oci://")
    host, _, repo = rest.partition("/")
    if host == "docker.io":
        host = "registry-1.docker.io"
    return f"https://{host}", repo, ""


# --------------------------------------------------------------------------- lock


def load_lock(path: pathlib.Path) -> dict:
    def no_dupes(pairs):
        seen: dict = {}
        for key, value in pairs:
            if key in seen:
                raise ValueError(f"duplicate key {key!r}")
            seen[key] = value
        return seen

    return json.loads(path.read_text(), object_pairs_hook=no_dupes)


def host_platform() -> str:
    system = _platform.system().lower()
    machine = _platform.machine().lower()
    arch = {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(machine, machine)
    return f"{system}-{arch}"


def select(lock: dict, name: str, platform: str | None) -> tuple[dict, str, dict]:
    entries = [a for a in lock.get("artifacts", []) if a.get("name") == name]
    if len(entries) != 1:
        raise LookupError(f"{name!r} is not in the lock" if not entries else f"{name!r} is ambiguous")
    entry = entries[0]
    plats = entry.get("platforms") or {}
    if platform is None:
        platform = "any" if "any" in plats else host_platform()
    if platform not in plats:
        raise LookupError(f"{name!r} has no platform {platform!r} (lock has {sorted(plats)})")
    return entry, platform, plats[platform]


def mirror_ref(entry: dict, platform: str) -> tuple[str, str]:
    """(repository, tag) of an entry in the mirror: explicit `mirror` wins, else derived."""
    explicit = entry.get("mirror")
    if explicit:
        repo, _, tag = explicit.rpartition(":")
        return repo, tag
    repo = re.sub(r"[^a-z0-9._/-]", "-", entry["name"].lower())
    tag = entry["version"] if platform == "any" else f"{entry['version']}-{platform}"
    return repo, re.sub(r"[^A-Za-z0-9._-]", "-", tag)


# --------------------------------------------------------------------------- tiers


def tier_cache(entry: dict, plat: dict) -> bytes | None:
    root = os.environ.get("OB_ARTIFACT_CACHE")
    if not root:
        return None
    path = pathlib.Path(root) / plat["sha256"]
    if not path.is_file():
        raise Unavailable(f"cache miss {path.name[:12]}")
    return path.read_bytes()


def tier_mirror(entry: dict, plat: dict, platform: str) -> bytes | None:
    base = os.environ.get("OB_ARTIFACT_MIRROR", "").rstrip("/")
    if not base:
        return None
    parsed = urllib.parse.urlsplit(base if "://" in base else "https://" + base)
    registry = f"{parsed.scheme}://{parsed.netloc}"
    repo, tag = mirror_ref(entry, platform)
    prefix = parsed.path.strip("/")
    return oci_pull(registry, f"{prefix}/{repo}" if prefix else repo, tag)


GITHUB_RELEASE = re.compile(r"^https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/?#]+)$")


def _github_web() -> str:
    return os.environ.get("OB_GITHUB_WEB", "https://github.com").rstrip("/")


def _github_api() -> str:
    return os.environ.get("OB_GITHUB_API", "https://api.github.com").rstrip("/")


def github_release_asset_via_api(owner: str, repo: str, tag: str, asset: str) -> bytes:
    """Download a release asset through the REST API instead of the github.com web frontend.

    Measured 2026-11-08: `github.com/.../releases/download/...` answered 504 from the web
    frontend while `api.github.com/.../releases/assets/<id>` (Accept: octet-stream) answered
    at once, redirecting to the asset store. Authenticated when GITHUB_TOKEN (or GH_TOKEN) is set, because
    anonymous API use is limited to 60 requests per hour per IP.
    """
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    if token:
        headers["Authorization"] = f"Bearer {token}"
    tag_q = urllib.parse.quote(tag, safe="")
    body, _ = _http_get(f"{_github_api()}/repos/{owner}/{repo}/releases/tags/{tag_q}", headers)
    ids = [a.get("id") for a in json.loads(body).get("assets", []) if a.get("name") == asset]
    if len(ids) != 1:
        raise Unavailable(f"release {owner}/{repo}@{tag} has no single asset named {asset}")
    headers["Accept"] = "application/octet-stream"
    return _http_get(f"{_github_api()}/repos/{owner}/{repo}/releases/assets/{ids[0]}", headers)[0]


def tier_upstream(entry: dict, plat: dict) -> bytes:
    """The lock URL. For a GitHub release download, the web URL is tried first and the REST API
    asset route second: the web route costs no API quota and needs no token, so it stays the
    default; the API route exists for the outage where only the web frontend is down."""
    url = plat["url"]
    if url.startswith("oci://"):
        registry, repo, _ = _split_oci(url)
        return oci_pull(registry, repo, entry["version"])
    gh = GITHUB_RELEASE.match(url)
    if gh:
        direct = _github_web() + url[len("https://github.com"):]
        if os.environ.get("OB_GITHUB_PREFER_API"):
            # The caller already saw the web route fail and the API route work in this run;
            # go to the API first instead of paying the dead route's timeout per artifact.
            try:
                return github_release_asset_via_api(*gh.groups())
            except (Unavailable, _AuthRequired) as exc:
                _log(f"upstream: REST API asset route unavailable ({exc}); trying the web URL")
            return _http_get(direct)[0]
        try:
            return _http_get(direct)[0]
        except (Unavailable, _AuthRequired) as exc:
            _log(f"upstream: web release URL unavailable ({exc}); trying the REST API asset route")
        data = github_release_asset_via_api(*gh.groups())
        _log("upstream: served by the REST API asset route (OB_GITHUB_PREFER_API=1 skips the web URL)")
        return data
    if not url.startswith("https://") and not os.environ.get("OB_ARTIFACT_ALLOW_HTTP"):
        raise Unavailable(f"refusing non-https upstream {url}")
    return _http_get(url)[0]


def fetch(entry: dict, platform: str, plat: dict) -> tuple[int, bytes | None, str]:
    """Run the tiers. Returns (exit code, verified bytes or None, tier that served them)."""
    want = plat["sha256"]
    mismatched = False
    tiers = [
        ("cache", lambda: tier_cache(entry, plat)),
        ("mirror", lambda: tier_mirror(entry, plat, platform)),
        ("upstream", lambda: tier_upstream(entry, plat)),
    ]
    for tier, run in tiers:
        try:
            data = run()
        except Unavailable as exc:
            _log(f"{tier}: unavailable ({exc})")
            continue
        except Exception as exc:  # malformed manifest, bad JSON: the tier did not deliver
            _log(f"{tier}: unavailable ({type(exc).__name__}: {exc})")
            continue
        if data is None:
            _log(f"{tier}: not configured, skipped")
            continue
        got = hashlib.sha256(data).hexdigest()
        if got != want:
            mismatched = True
            _log(f"{tier}: REJECTED sha256 {got} != lock {want}")
            continue
        _log(f"{tier}: verified {entry['name']} {entry['version']} ({platform})")
        return 0, data, tier
    return (EXIT_INTEGRITY if mismatched else EXIT_UNAVAILABLE), None, ""


def _store_in_cache(sha: str, data: bytes) -> None:
    root = os.environ.get("OB_ARTIFACT_CACHE")
    if not root:
        return
    try:
        directory = pathlib.Path(root)
        directory.mkdir(parents=True, exist_ok=True)
        tmp = directory / f".{sha}.tmp"
        tmp.write_bytes(data)
        tmp.replace(directory / sha)
    except OSError as exc:
        _log(f"cache: could not store ({exc}); continuing")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("name")
    ap.add_argument("--platform")
    ap.add_argument("--out")
    ap.add_argument("--show", choices=["version", "sha256", "url"])
    ap.add_argument("--lock", default=os.environ.get("OB_ARTIFACT_LOCK", str(DEFAULT_LOCK)))
    args = ap.parse_args(argv)

    try:
        lock = load_lock(pathlib.Path(args.lock))
        entry, platform, plat = select(lock, args.name, args.platform)
    except (OSError, ValueError, LookupError) as exc:
        print(f"fetch-artifact: {exc}", file=sys.stderr)
        return EXIT_USAGE

    if args.show:
        print({"version": entry["version"], "sha256": plat["sha256"], "url": plat["url"]}[args.show])
        return 0
    if not args.out:
        print("fetch-artifact: --out is required", file=sys.stderr)
        return EXIT_USAGE

    code, data, tier = fetch(entry, platform, plat)
    if code == EXIT_UNAVAILABLE:
        print(f"UNVERIFIED (artifact unavailable): {args.name}")
        return code
    if code == EXIT_INTEGRITY:
        print(f"INTEGRITY FAILURE (sha256 mismatch from every tier that answered): {args.name}")
        return code

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=out.parent, delete=False) as fh:
        fh.write(data)
    shutil.move(fh.name, out)
    if tier != "cache":
        _store_in_cache(plat["sha256"], data)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
