#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify every checksum a Dependabot metadata refresh ADDS, by downloading the artifact.

check-verification-metadata-additive.py proves the regenerated
gradle/verification-metadata.xml only adds entries. That is not enough on its own: the
file is produced by an unprivileged build that ran the bump's own Gradle plugins, so an
added entry could pin ANY coordinate to an attacker-chosen sha256 (or add a <pgp> key),
and the privileged push would commit it as trusted. A later build resolving that
coordinate would then accept the malicious artifact.

So this script, run by the privileged job from the trusted default-branch checkout and
never executing PR code, recomputes every added sha256 itself:

- every added artifact must carry exactly one <sha256> (64 lowercase hex) and nothing
  else: <pgp>, <also-trust>, <sha1>/<md5>/<sha512> additions are refused;
- its URL is derived from group/name/version/artifact-name in Maven layout on the public
  repositories settings.gradle.kts declares (REPOSITORIES below), tried in order; a
  coordinate that is not a plain token is refused before any URL is built;
- the artifact is downloaded over HTTPS with no credentials, no proxy and no automatic
  redirects; a redirect is followed only to an allowlisted host over HTTPS;
- the first repository that serves it decides; its sha256 must equal the added value;
- anything that cannot be fetched from any allowlisted repository REFUSES the whole
  refresh. Nothing is ever skipped;
- the work is bounded: at most MAX_ARTIFACTS additions, MAX_ARTIFACT_BYTES per artifact,
  MAX_TOTAL_BYTES overall; above a cap the refresh needs human review.

Usage:
    verify-added-checksums.py BASE.xml CANDIDATE.xml
    verify-added-checksums.py --self-test
Exit 0 every addition verified, 1 refused, 2 usage/unreadable.
"""

import contextlib
import hashlib
import http.client
import importlib.util
import io
import re
import ssl
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urljoin, urlsplit

NS = "{https://schema.gradle.org/dependency-verification}"

# The public repositories the build resolves from (settings.gradle.kts pluginManagement,
# build-logic/settings.gradle.kts, every module's `repositories {}`): the Maven Central GCS
# mirror, Maven Central and the Gradle Plugin Portal. The in-cluster Reposilite mirror is
# only a cache of these and is not reachable from a GitHub-hosted runner, so it is not
# listed. Order matches the build: the GCS mirror is declared first everywhere.
REPOSITORIES = (
    "https://maven-central.storage-download.googleapis.com/maven2/",
    "https://plugins.gradle.org/m2/",
    "https://repo.maven.apache.org/maven2/",
)
# Hosts a response may come from. plugins.gradle.org answers 303 to its artifact CDN or to
# Maven Central; nothing else is followed.
ALLOWED_HOSTS = frozenset({
    "maven-central.storage-download.googleapis.com",
    "repo.maven.apache.org",
    "plugins.gradle.org",
    "plugins-artifacts.gradle.org",
})

MAX_ARTIFACTS = 500
MAX_ARTIFACT_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
MAX_REDIRECTS = 5
ATTEMPTS = 3
TIMEOUT_SECONDS = 30
CHUNK = 1024 * 1024

# Maven coordinates as Gradle writes them. No '/', no '%', no leading '.', so a value can
# never escape its path segment or change the host. '..' is refused separately.
TOKEN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.+-]*")
SHA256 = re.compile(r"[0-9a-f]{64}")


class Refused(Exception):
    """The refresh must not be pushed."""


class Transient(Exception):
    """A retryable network failure."""


class Response:
    def __init__(self, status: int, location: str | None, chunks):
        self.status, self.location, self.chunks = status, location, chunks


def https_transport(url: str) -> Response:
    """One GET, no redirects, no proxy, no credentials, certificate verified."""
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.hostname not in ALLOWED_HOSTS or parts.username or parts.password:
        raise Refused(f"refusing to contact {url}")
    connection = http.client.HTTPSConnection(parts.hostname, parts.port or 443, timeout=TIMEOUT_SECONDS,
                                             context=ssl.create_default_context())
    path = parts.path + (f"?{parts.query}" if parts.query else "")
    try:
        connection.request("GET", path, headers={"User-Agent": "openbank-verify-added-checksums",
                                                 "Accept-Encoding": "identity"})
        response = connection.getresponse()
    except (OSError, http.client.HTTPException) as error:
        connection.close()
        raise Transient(f"{url}: {error}") from error

    def chunks():
        try:
            while block := response.read(CHUNK):
                yield block
        except (OSError, http.client.HTTPException) as error:
            raise Transient(f"{url}: {error}") from error
        finally:
            connection.close()

    if response.status != 200:
        location = response.getheader("Location")
        connection.close()
        return Response(response.status, location, iter(()))
    return Response(200, None, chunks())


def artifact_path(group: str, name: str, version: str, file: str) -> str:
    for value in (group, name, version, file):
        if not TOKEN.fullmatch(value) or ".." in value or value.endswith((".", "-", "+")):
            raise Refused("added artifact has a coordinate that is not a plain Maven token")
    if any(not TOKEN.fullmatch(segment) for segment in group.split(".")):
        raise Refused("added artifact has a coordinate that is not a plain Maven token")
    return "/".join([*group.split("."), name, version, file])


class Verifier:
    def __init__(self, transport=https_transport, sleep=time.sleep):
        self.transport, self.sleep, self.total = transport, sleep, 0

    def digest(self, url: str) -> str | None:
        """sha256 of the body at url, following allowlisted redirects; None on 404/410."""
        for attempt in range(ATTEMPTS):
            try:
                return self._digest_once(url)
            except Transient as error:
                if attempt == ATTEMPTS - 1:
                    raise Refused(f"could not fetch after {ATTEMPTS} attempts: {error}") from error
                self.sleep(2 ** attempt)
        raise AssertionError("unreachable")

    def _digest_once(self, url: str) -> str | None:
        for _ in range(MAX_REDIRECTS + 1):
            parts = urlsplit(url)
            if parts.scheme != "https" or parts.hostname not in ALLOWED_HOSTS or parts.username or parts.password:
                raise Refused(f"redirected off the allowlist to {parts.scheme}://{parts.hostname}")
            response = self.transport(url)
            if response.status in (301, 302, 303, 307, 308):
                if not response.location:
                    raise Refused(f"{url}: redirect without a Location")
                url = urljoin(url, response.location)
                continue
            if response.status in (404, 410):
                return None
            if response.status == 429 or response.status >= 500:
                raise Transient(f"{url}: HTTP {response.status}")
            if response.status != 200:
                raise Refused(f"{url}: HTTP {response.status}")
            sha, size = hashlib.sha256(), 0
            for block in response.chunks:
                size += len(block)
                if size > MAX_ARTIFACT_BYTES or self.total + size > MAX_TOTAL_BYTES:
                    raise Refused("download exceeds the size cap; this refresh needs human review")
                sha.update(block)
            self.total += size
            return sha.hexdigest()
        raise Refused(f"more than {MAX_REDIRECTS} redirects")

    def verify(self, path: str, expected: str) -> None:
        for repository in REPOSITORIES:
            actual = self.digest(repository + path)
            if actual is None:
                continue
            if actual != expected:
                raise Refused(f"{path}: sha256 {expected} added, {repository} serves {actual}")
            return
        raise Refused(f"{path}: not found in any allowlisted repository")


def additions(base: ET.Element, candidate: ET.Element, checksums) -> list[tuple[str, str]]:
    """(path, sha256) for every artifact the candidate adds; refuses any non-sha256 addition."""
    pinned = checksums(base).keys()
    added = []
    components = candidate.find(NS + "components")
    for component in components if components is not None else ():
        coordinate = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        for artifact in component:
            key = (*coordinate, artifact.get("name", ""))
            if key in pinned:
                continue
            path = artifact_path(*key)
            digests = list(artifact)
            if len(digests) != 1 or digests[0].tag != NS + "sha256":
                raise Refused(f"{path}: an added artifact may carry exactly one <sha256> and nothing else, got "
                              + (", ".join(sorted({d.tag.removeprefix(NS) for d in digests})) or "none"))
            if len(digests[0]) or set(digests[0].attrib) - {"value", "origin", "reason"}:
                raise Refused(f"{path}: added <sha256> carries <also-trust> or unexpected attributes")
            value = digests[0].get("value", "")
            if not SHA256.fullmatch(value):
                raise Refused(f"{path}: added sha256 is not 64 lowercase hex digits")
            added.append((path, value))
    if len(added) > MAX_ARTIFACTS:
        raise Refused(f"{len(added)} artifacts added, more than {MAX_ARTIFACTS}; this refresh needs human review")
    return added


def additive_module():
    # The structural checks (one <configuration>, no unknown elements, no duplicates, strict
    # UTF-8, no DTD) live in the sibling validator; reuse its parser rather than fork it.
    spec = importlib.util.spec_from_file_location(
        "additive", Path(__file__).with_name("check-verification-metadata-additive.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check(base_path: Path, candidate_path: Path, verifier: Verifier) -> int:
    additive = additive_module()
    try:
        base, candidate = additive.load(base_path), additive.load(candidate_path)
        problems = additive.refusals(base, candidate)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"::error::verification metadata could not be checked: {error}")
        return 2
    if problems:
        print("::error::the candidate is not purely additive; run check-verification-metadata-additive.py")
        return 1
    try:
        added = additions(base, candidate, additive.checksums)
        for path, value in added:
            verifier.verify(path, value)
    except Refused as error:
        print(f"::error::refusing the metadata refresh: {error}")
        return 1
    print(f"SUBJECTS={len(added)}  # added checksums verified against an allowlisted repository")
    print(f"every added checksum matches the upstream artifact ({verifier.total} bytes downloaded)")
    return 0


def selftest() -> int:
    good = b"artifact bytes"
    good_sha = hashlib.sha256(good).hexdigest()
    other_sha = hashlib.sha256(b"something else").hexdigest()
    gcs, portal, central = REPOSITORIES
    path = "g/b/1/b-1.jar"

    def transport(routes):
        def fetch(url):
            status, payload = routes.get(url, (404, None))
            if status == "error":
                raise Transient(url)
            if status == 200:
                return Response(200, None, iter([payload]))
            return Response(status, payload, iter(()))
        return fetch

    head = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">\n'
            '<configuration><verify-metadata>true</verify-metadata>'
            '<verify-signatures>false</verify-signatures></configuration>\n<components>\n')
    tail = "</components>\n</verification-metadata>\n"
    pinned = ('<component group="g" name="a" version="1"><artifact name="a-1.jar">'
              f'<sha256 value="{other_sha}" origin="Generated by Gradle"/></artifact></component>\n')

    def doc(*added: str) -> str:
        return head + pinned + "".join(added) + tail

    def entry(digests: str, group: str = "g", name: str = "b", file: str = "b-1.jar") -> str:
        return (f'<component group="{group}" name="{name}" version="1"><artifact name="{file}">'
                f"{digests}</artifact></component>\n")

    sha = f'<sha256 value="{good_sha}" origin="Generated by Gradle"/>'
    served = {gcs + path: (200, good)}
    many = "".join(entry(sha, name=f"n{i}", file=f"n{i}-1.jar") for i in range(MAX_ARTIFACTS + 1))
    cases = [
        ("nothing added", doc(), {}, 0),
        ("correct hash passes", doc(entry(sha)), served, 0),
        ("found on the second repository", doc(entry(sha)), {central + path: (200, good)}, 0),
        ("portal redirect to an allowlisted host", doc(entry(sha)), {
            portal + path: (303, "https://plugins-artifacts.gradle.org/g/b/1/x/b-1.jar"),
            "https://plugins-artifacts.gradle.org/g/b/1/x/b-1.jar": (200, good)}, 0),
        ("transient error then success", doc(entry(sha)), "flaky", 0),
        ("wrong hash refused", doc(entry(f'<sha256 value="{other_sha}"/>')), served, "serves"),
        ("first repository serves different bytes", doc(entry(sha)), {
            gcs + path: (200, b"evil"), central + path: (200, good)}, "serves"),
        ("unfetchable artifact refused", doc(entry(sha)), {}, "not found in any"),
        ("persistent network error refused", doc(entry(sha)), {gcs + path: ("error", None)}, "after 3 attempts"),
        ("HTTP 403 refused", doc(entry(sha)), {gcs + path: (403, None)}, "HTTP 403"),
        ("pgp addition refused", doc(entry('<pgp value="0123456789ABCDEF0123456789ABCDEF01234567"/>')), served, "exactly one"),
        ("pgp next to sha256 refused", doc(entry(sha + '<pgp value="0123456789ABCDEF"/>')), served, "exactly one"),
        ("also-trust addition refused", doc(entry(
            f'<sha256 value="{good_sha}"><also-trust value="{other_sha}"/></sha256>')), served, "also-trust"),
        ("sha1-only addition refused", doc(entry('<sha1 value="0123456789abcdef0123456789abcdef01234567"/>')), served, "exactly one"),
        ("md5-only addition refused", doc(entry('<md5 value="0123456789abcdef0123456789abcdef"/>')), served, "exactly one"),
        ("uppercase sha256 refused", doc(entry(f'<sha256 value="{good_sha.upper()}"/>')), served, "lowercase hex"),
        ("redirect to a foreign host refused", doc(entry(sha)), {
            gcs + path: (302, "https://evil.example/b-1.jar"), "https://evil.example/b-1.jar": (200, good)}, "off the allowlist"),
        ("redirect to plain http refused", doc(entry(sha)), {
            gcs + path: (302, "http://repo.maven.apache.org/maven2/" + path),
            "http://repo.maven.apache.org/maven2/" + path: (200, good)}, "off the allowlist"),
        ("redirect loop refused", doc(entry(sha)), {gcs + path: (302, gcs + path)}, "redirects"),
        ("path traversal in a coordinate refused", doc(entry(sha, file="..")), "all", "plain Maven token"),
        ("empty group segment refused", doc(entry(sha, group="g.")), "all", "plain Maven token"),
        ("host injection in a group refused", doc(entry(sha, group="@evil.example")), "all", "plain Maven token"),
        ("over the artifact cap refused", doc(many), "all", "needs human review"),
        ("over the byte cap refused", doc(entry(sha)), "huge", "size cap"),
        ("non-additive candidate refused", head + pinned.replace(other_sha, good_sha) + tail, served, "not purely additive"),
        ("not metadata", "<project/>", {}, 2),
    ]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        base_path = Path(tmp, "base.xml")
        base_path.write_text(doc(), encoding="utf-8")
        for name, text, routes, expected in cases:
            if routes == "flaky":
                calls = []

                def fetch(url, calls=calls):
                    calls.append(url)
                    if len(calls) == 1:
                        raise Transient(url)
                    return Response(200, None, iter([good]))
            elif routes == "all":
                def fetch(url):
                    return Response(200, None, iter([good]))
            elif routes == "huge":
                def fetch(url):
                    return Response(200, None, iter([b"\0" * CHUNK] * (MAX_ARTIFACT_BYTES // CHUNK + 1)))
            else:
                fetch = transport(routes)
            candidate = Path(tmp, "candidate.xml")
            candidate.write_text(text, encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                got = check(base_path, candidate, Verifier(fetch, sleep=lambda _: None))
            # A refusal must be refused for the reason the case exists for, or a different rule
            # (an unfetchable path, a hash mismatch) would mask the one under test.
            if isinstance(expected, str):
                ok = got == 1 and expected in output.getvalue()
            else:
                ok = got == expected
            if not ok:
                failed += 1
                print(f"SELFTEST FAIL: {name}: expected {expected}, got {got}", file=sys.stderr)
    if failed:
        return 1
    print(f"self-test ok: {len(cases)} cases, including a wrong hash, a foreign redirect and a pgp addition")
    return 0


def main(argv: list[str]) -> int:
    if argv in (["--selftest"], ["--self-test"]):
        return selftest()
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    return check(Path(argv[0]), Path(argv[1]), Verifier())


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
