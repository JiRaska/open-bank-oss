# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Return success only when a metadata diff adds Maven Central artifacts with verified SHA-256s.

Exit 0 means safe to omit service builds, 1 means ineligible (caller must use its
ordinary full-fleet fallback), and 2 means the verifier itself could not decide.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import re
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PATH = "gradle/verification-metadata.xml"
NS = "https://schema.gradle.org/dependency-verification"
Q = f"{{{NS}}}"
CENTRAL = "https://repo.maven.apache.org/maven2/"
MAX_ARTIFACT_BYTES = 256 * 1024 * 1024
DOWNLOAD_CHUNK_BYTES = 1024 * 1024


class Ineligible(Exception):
    pass


def source(ref: str) -> bytes:
    if ref == "HEAD":
        try:
            return Path(PATH).read_bytes()
        except OSError as exc:
            raise Ineligible(f"cannot read head metadata: {exc}") from exc
    result = subprocess.run(["git", "show", f"{ref}:{PATH}"], capture_output=True, check=False)
    if result.returncode:
        raise Ineligible(f"cannot read base metadata at {ref}")
    return result.stdout


def parse(raw: bytes):
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise Ineligible(f"invalid XML: {exc}") from exc
    if root.tag != Q + "verification-metadata":
        raise Ineligible("unsupported metadata root")
    config = root.find(Q + "configuration")
    components = root.find(Q + "components")
    if config is None or components is None or len(root) != 2:
        raise Ineligible("unsupported metadata structure")
    entries = {}
    for component in components:
        if component.tag != Q + "component" or set(component.attrib) != {"group", "name", "version"}:
            raise Ineligible("unsupported component entry")
        key = tuple(component.attrib[k] for k in ("group", "name", "version"))
        if key in entries:
            raise Ineligible(f"duplicate component {key}")
        artifacts = {}
        for artifact in component:
            if artifact.tag != Q + "artifact" or set(artifact.attrib) != {"name"}:
                raise Ineligible(f"unsupported artifact entry in {key}")
            name = artifact.attrib["name"]
            if name in artifacts:
                raise Ineligible(f"duplicate artifact {key}/{name}")
            checksums = []
            for checksum in artifact:
                if checksum.tag != Q + "sha256" or set(checksum.attrib) != {"value", "origin"} or len(checksum):
                    raise Ineligible(f"unsupported checksum in {key}/{name}")
                if any(origin == checksum.attrib["origin"] for origin, _ in checksums):
                    raise Ineligible(f"duplicate checksum origin in {key}/{name}")
                checksums.append((checksum.attrib["origin"], checksum.attrib["value"]))
            if not checksums:
                raise Ineligible(f"missing sha256 for {key}/{name}")
            artifacts[name] = (artifact, checksums)
        entries[key] = (component, artifacts)
    return root, config, entries


def digest_matches(group: str, name: str, version: str, artifact: str, expected: set[str]) -> bool:
    segment = re.compile(r"^[A-Za-z0-9_.+-]+$")
    if (not group or any(not segment.fullmatch(part) for part in group.split("."))
            or not segment.fullmatch(name) or ".." in name
            or not segment.fullmatch(version) or ".." in version
            or not segment.fullmatch(artifact) or ".." in artifact):
        raise Ineligible(f"unsafe Maven artifact path {artifact!r}")
    rel = "/".join((group.replace(".", "/"), name, version, artifact))
    request = urllib.request.Request(CENTRAL + rel, headers={"User-Agent": "openbank-metadata-verifier"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if response.geturl().split("/", 3)[:3] != ["https:", "", "repo.maven.apache.org"]:
                raise Ineligible("Maven Central redirected outside the approved host")
            content_length = response.headers.get("Content-Length")
            if content_length is not None:
                if not content_length.isdigit():
                    raise Ineligible(f"invalid Content-Length while fetching {rel}")
                if int(content_length) > MAX_ARTIFACT_BYTES:
                    raise Ineligible(f"artifact exceeds {MAX_ARTIFACT_BYTES} byte verification limit: {rel}")
            hasher = hashlib.sha256()
            total = 0
            while True:
                chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_ARTIFACT_BYTES:
                    raise Ineligible(f"artifact exceeds {MAX_ARTIFACT_BYTES} byte verification limit: {rel}")
                hasher.update(chunk)
            digest = hasher.hexdigest()
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as exc:
        raise Ineligible(f"could not fetch {rel} from Maven Central: {exc}") from exc
    return digest in expected


def check(base_ref: str) -> list[str]:
    base_root, base_config, base = parse(source(base_ref))
    head_root, head_config, head = parse(source("HEAD"))
    if ET.tostring(base_config) != ET.tostring(head_config):
        raise Ineligible("configuration changed")
    if base_root.attrib != head_root.attrib:
        raise Ineligible("root metadata attributes changed")
    if [node.tag for node in base_root] != [node.tag for node in head_root]:
        raise Ineligible("metadata section order changed")
    if [key for key in base if key in head] != list(base):
        raise Ineligible("existing component order changed")
    additions = []
    for key, (old_component, old_artifacts) in base.items():
        if key not in head:
            raise Ineligible(f"component removed: {key}")
        new_component, new_artifacts = head[key]
        if old_component.attrib != new_component.attrib or old_component.text != new_component.text:
            raise Ineligible(f"component changed: {key}")
        if [name for name in old_artifacts if name in new_artifacts] != list(old_artifacts):
            raise Ineligible(f"existing artifact order changed: {key}")
        for name, (old_node, old_checksums) in old_artifacts.items():
            if name not in new_artifacts:
                raise Ineligible(f"artifact removed: {key}/{name}")
            new_node, new_checksums = new_artifacts[name]
            if old_node.attrib != new_node.attrib or old_checksums != new_checksums:
                raise Ineligible(f"existing artifact/checksum changed: {key}/{name}")
    for key, (_, artifacts) in head.items():
        for artifact, (_, checksums) in artifacts.items():
            if key in base and artifact in base[key][1]:
                continue
            values = {value for _, value in checksums}
            if len(checksums) != 1:
                raise Ineligible(f"new artifact must have exactly one sha256: {key}/{artifact}")
            if any(len(value) != 64 or any(c not in "0123456789abcdef" for c in value) for value in values):
                raise Ineligible(f"invalid SHA-256 for {key}/{artifact}")
            if not digest_matches(*key, artifact, values):
                raise Ineligible(f"SHA-256 does not match Maven Central bytes: {key}/{artifact}")
            additions.append(f"{':'.join(key)}:{artifact}")
    if not additions:
        raise Ineligible("metadata change contains no new artifacts")
    return additions


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="merge-base commit, or HEAD~1 for a main push")
    args = parser.parse_args()
    try:
        additions = check(args.base)
    except Ineligible as exc:
        print(f"metadata-only change is ineligible: {exc}")
        return 1
    except Exception as exc:  # noqa: BLE001 - unexpected faults must fail closed.
        print(f"::error::metadata verifier failed unexpectedly: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2
    print(f"metadata-only change verified: {len(additions)} Maven Central artifact(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
