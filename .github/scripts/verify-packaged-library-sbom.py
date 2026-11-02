#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify original packaged library JARs, not augmented classes or deployed images.

The runtime artifact list must come directly from Gradle's resolved runtimeClasspath
ProjectComponentIdentifier results in the same build. It is an input, not signed
provenance. This check neither authenticates its producer nor replaces image attestation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import parse_qsl, unquote, urlsplit
import zipfile


class EvidenceError(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise EvidenceError(reason)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def read_json(path):
    return json.loads(Path(path).read_text(), object_pairs_hook=unique_object)


def sha256(path):
    require(path.is_file() and not path.is_symlink(), "artifact is not a regular file")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def classes(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) <= 20000 and len(names) == len(set(names)), "invalid JAR inventory")
        return {name for name in names if name.startswith("com/openbank/libs/") and name.endswith(".class")}


def library_path(component):
    purl = component.get("purl", "")
    require(isinstance(purl, str), "invalid component purl")
    parsed = urlsplit(purl)
    query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
    require(len(query) == len(dict(query)), "duplicate purl qualifier")
    project = dict(query).get("project_path")
    if not project or not project.startswith(":openbank-libs"):
        return None
    require(re.fullmatch(r":openbank-libs(?:-[a-z0-9]+)*", project), "invalid library project path")
    require(parsed.scheme == "pkg" and not parsed.fragment, "invalid library purl")
    require(component.get("group") == "com.openbank" and component.get("name") == project[1:], "library coordinate mismatch")
    require(isinstance(component.get("version"), str) and component["version"], "missing library version")
    expected = "maven/com.openbank/" + project[1:] + "@" + component["version"]
    require(unquote(parsed.path) == expected, "library purl coordinate mismatch")
    return project


def verify(sbom, resolved, package):
    require(isinstance(sbom, dict) and sbom.get("bomFormat") == "CycloneDX", "not CycloneDX")
    require(isinstance(sbom.get("components"), list), "missing component inventory")
    require(isinstance(resolved, list), "invalid resolved project inventory")
    package = Path(package)
    require(package.is_dir() and not package.is_symlink(), "invalid package directory")
    require((package / "quarkus-run.jar").is_file(), "not a fast-jar package")
    files = list(package.rglob("*"))
    require(not any(p.is_symlink() for p in files), "symlink in package")
    jars = [p for p in (package / "lib").rglob("*.jar") if p.is_file()]
    require(jars, "missing packaged dependency JARs")
    digests = {p: sha256(p) for p in jars}
    jar_classes = {p: classes(p) for p in jars}
    expected = {}
    for row in resolved:
        require(isinstance(row, dict) and isinstance(row.get("projectPath"), str), "invalid resolved project")
        project = row["projectPath"]
        if not project.startswith(":openbank-libs"):
            continue
        require(re.fullmatch(r":openbank-libs(?:-[a-z0-9]+)*", project), "invalid resolved library path")
        require(project not in expected and isinstance(row.get("file"), str), "duplicate or missing resolved artifact")
        expected[project] = sha256(Path(row["file"]))
    require(expected, "no resolved runtime libraries")
    components = {}
    for component in sbom["components"]:
        require(isinstance(component, dict), "invalid component")
        project = library_path(component)
        if project is not None:
            require(project not in components, "duplicate library component")
            components[project] = component
    require(set(components) == set(expected), "SBOM/resolved library inventory mismatch")
    matched, rows, original_classes = set(), [], set()
    for project, digest in sorted(expected.items()):
        hashes = components[project].get("hashes")
        require(isinstance(hashes, list), "missing library hashes (resolution-only SBOM is incomplete)")
        require(all(isinstance(h, dict) for h in hashes), "invalid hash entry")
        declared = [h.get("content") for h in hashes if h.get("alg") == "SHA-256"]
        require(len(declared) == 1 and isinstance(declared[0], str) and re.fullmatch(r"[0-9a-f]{64}", declared[0]), "invalid or duplicate library SHA-256")
        require(declared[0] == digest, "SBOM/resolved artifact hash mismatch")
        candidates = [p for p, actual in digests.items() if actual == digest]
        require(len(candidates) == 1, "missing or ambiguous packaged artifact bytes")
        packed = candidates[0]
        require(packed not in matched, "artifact assigned to multiple projects")
        matched.add(packed)
        original_classes.update(jar_classes[packed])
        rows.append({"projectPath": project, "sha256": digest, "packagedPath": packed.relative_to(package).as_posix()})
    require(not any(names for p, names in jar_classes.items() if p not in matched), "unexpected internal library bytecode in dependency JAR")
    augmentation = []
    for path in sorted((package / "quarkus").rglob("*.jar")):
        names = classes(path)
        augmentation.append({"artifact": path.relative_to(package).as_posix(), "sha256": sha256(path),
                             "overlappingLibraryClasses": len(names & original_classes)})
    return {"schemaVersion": 1, "outcome": "verified", "claim": "packaged-original-library-artifacts",
            "libraries": rows, "augmentation": augmentation,
            "effectiveRuntimeIdentityVerified": False, "deploymentVerified": False,
            "requires": "independent full augmented package/image provenance and live replica binding"}


def verify_receipt(sbom, receipt, package):
    """Recheck producer-attested mapping; caller must authenticate its full bundle first."""
    package = Path(package)
    require(isinstance(receipt, dict) and isinstance(receipt.get("libraries"), list), "missing library receipt")
    resolved = []
    for row in receipt["libraries"]:
        require(isinstance(row, dict) and set(row) == {"projectPath", "sha256", "packagedPath"}, "invalid receipt row")
        relative = row["packagedPath"]
        require(isinstance(relative, str), "invalid packaged path")
        path = Path(relative)
        require(not path.is_absolute() and ".." not in path.parts and relative == path.as_posix()
                and path.parts[:1] == ("lib",) and path.suffix == ".jar", "unsafe packaged path")
        require(sha256(package / path) == row["sha256"], "receipt packaged hash mismatch")
        resolved.append({"projectPath": row["projectPath"], "file": str(package / path)})
    actual = verify(sbom, resolved, package)
    require(json.dumps(receipt, sort_keys=True) == json.dumps(actual, sort_keys=True), "receipt schema, claims or artifact mapping mismatch")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--runtime-artifacts")
    source.add_argument("--receipt", help="Recheck mapping from an already authenticated producer bundle")
    parser.add_argument("--quarkus-app", required=True)
    args = parser.parse_args()
    try:
        result = (verify_receipt(read_json(args.sbom), read_json(args.receipt), args.quarkus_app) if args.receipt
                  else verify(read_json(args.sbom), read_json(args.runtime_artifacts), args.quarkus_app))
    except (EvidenceError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(json.dumps({"outcome": "incomplete", "claim": "packaged-original-library-artifacts",
                          "reason": str(error)}))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
