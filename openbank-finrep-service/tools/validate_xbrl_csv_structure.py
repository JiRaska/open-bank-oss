#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate FINREP9 or COREP OF report-package structure against pinned EBA 4.2 assets offline."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import BinaryIO
from zipfile import BadZipFile, ZipFile


ARELLE_VERSION = "2.47.4"
PACKAGE_HASH = "0bf9e33720de1472e809417999cfd29e4b5eadb31750c7770622e502590bbdd0"
FULL_TAXONOMY_HASH = "eaa7d9209816287c67c65948a67acb65072fe79dad6981bab9eef5554cad15a0"
TAXONOMY_PACKAGES = (
    "EBA_XBRL_4.2_Dictionary_4.2.0.0.zip",
    "EBA_XBRL_4.2_Reporting_Frameworks_4.2.0.0.zip",
    "EBA_XBRL_4.2_Severity_4.2.0.0.zip",
)
FORMULA_LINKBASES = "*/val/vr-*.xml|*/set/*-val-severity.xml|*/set/*-ignore-val.xml"
ENTRYPOINTS = {
    "FINREP9": "http://www.eba.europa.eu/eu/fr/xbrl/crr/fws/finrep/4.2/mod/finrep9.json",
    "COREP_OF": "http://www.eba.europa.eu/eu/fr/xbrl/crr/fws/corep/4.2/mod/corep_of.json",
}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_report(path: Path | BinaryIO, module: str) -> None:
    with ZipFile(path) as package:
        names = package.namelist()
        paths = [PurePosixPath(name) for name in names]
        if (
            len(set(paths)) != len(paths)
            or any(item.is_absolute() or ".." in item.parts or not item.parts for item in paths)
            or any("\\" in name for name in names)
        ):
            raise ValueError("Report contains duplicate or unsafe archive paths.")
        roots = {item.parts[0] for item in paths}
        if len(roots) != 1:
            raise ValueError("Report package requires one top-level directory.")
        root = roots.pop()
        report_dir = f"{root}/reports"
        metadata_path = f"{report_dir}/report.json"
        manifest_path = f"{root}/META-INF/reportPackage.json"
        permitted_metadata = {metadata_path, manifest_path}
        for entry in package.infolist():
            parent = PurePosixPath(entry.filename).parent.as_posix()
            if not entry.is_dir() and entry.filename not in permitted_metadata:
                if parent != report_dir or not entry.filename.endswith(".csv"):
                    raise ValueError("The EBA report package may contain only its metadata and report CSV files.")
        manifest = json.loads(package.read(manifest_path))
        if manifest["documentInfo"]["documentType"] != "https://xbrl.org/report-package/2023":
            raise ValueError("Expected an XBRL Report Package 1.0 manifest.")
        metadata = json.loads(package.read(metadata_path))
        expected = {"documentType": "https://xbrl.org/2021/xbrl-csv", "extends": [ENTRYPOINTS[module]]}
        if metadata != {"documentInfo": expected}:
            raise ValueError("The report does not use the pinned xBRL-CSV entry point for the requested module.")


def copy_archive_member(archive: ZipFile, member: str, destination: Path) -> None:
    with archive.open(member) as source, destination.open("wb") as target:
        shutil.copyfileobj(source, target)


def validate(report: Path, module: str, package: Path, full_taxonomy: Path) -> dict:
    if digest(package) != PACKAGE_HASH or digest(full_taxonomy) != FULL_TAXONOMY_HASH:
        raise ValueError("EBA asset digest does not match the pinned release.")
    if importlib.metadata.version("arelle-release") != ARELLE_VERSION:
        raise ValueError(f"Install arelle-release=={ARELLE_VERSION} in the validator environment.")

    with tempfile.TemporaryDirectory(prefix="openbank-xbrl-") as directory:
        scratch = Path(directory)
        report_copy = scratch / report.name
        shutil.copyfile(report, report_copy)
        check_report(report_copy, module)
        report_hash = digest(report_copy)
        with ZipFile(package) as taxonomy:
            for name in TAXONOMY_PACKAGES:
                copy_archive_member(taxonomy, name, scratch / name)
        mirror = scratch / "Full_taxonomy.7z"
        with ZipFile(full_taxonomy) as taxonomy:
            copy_archive_member(taxonomy, mirror.name, mirror)

        cache = scratch / "cache"
        subprocess.run(
            [
                "7z", "x", "-y", f"-o{cache / 'http'}", str(mirror),
                "www.eurofiling.info/*", "www.xbrl.org/*", "xbrl.org/*",
            ],
            check=True,
            capture_output=True,
            timeout=300,
        )
        log = scratch / "validation.json"
        subprocess.run(
            [
                sys.executable, "-m", "arelle.CntlrCmdLine",
                "--workOffline", "--reportPackage", "--validate", "--formula", "none",
                f"--skipLoading={FORMULA_LINKBASES}", "--logLevel=info",
                f"--xdgConfigHome={scratch}", f"--cacheDirectory={cache}",
                "--packages=" + "|".join(str(scratch / name) for name in TAXONOMY_PACKAGES),
                f"--file={report_copy}", f"--logFile={log}",
            ],
            check=True,
            capture_output=True,
            timeout=1800,
        )
        messages = json.loads(log.read_text(encoding="utf-8"))["log"]
        errors = [entry["code"] for entry in messages if entry["level"] in {"error", "critical"}]
        if errors:
            raise ValueError("Arelle structural validation failed: " + ", ".join(dict.fromkeys(errors)))
        if not any("validated" in entry.get("message", {}).get("text", "").lower() for entry in messages):
            raise ValueError("Arelle did not record a completed validation.")
    return {
        "module": module,
        "result": "STRUCTURE_VALIDATED",
        "reportSha256": report_hash,
        "taxonomyPackageSha256": PACKAGE_HASH,
        "fullTaxonomySha256": FULL_TAXONOMY_HASH,
        "validator": f"Arelle {ARELLE_VERSION}",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", choices=ENTRYPOINTS, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--taxonomy-package", type=Path, required=True)
    parser.add_argument("--full-taxonomy", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = validate(args.report, args.module, args.taxonomy_package, args.full_taxonomy)
    except (BadZipFile, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"Offline validation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
