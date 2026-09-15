#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Real Gradle regression fixtures for complete, artifact-verified resolution.

CI uses setup-gradle's installed init script. Local runs may set
OPENBANK_TEST_GRAPH_PLUGIN_INIT to an equivalent pinned plugin bootstrap.
"""

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = Path(__file__).resolve().parents[3]
SCRIPTS = REPOSITORY / '.github/scripts'
SPEC = importlib.util.spec_from_file_location('resolver', SCRIPTS / 'resolve-fleet-dependencies.py')
resolver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(resolver)


class GradleResolutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='openbank-resolution-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'settings.gradle').write_text("rootProject.name = 'probe'\ninclude 'child'\n")
        guard = ("tasks.withType(JavaCompile).configureEach { doFirst { "
                 "throw new GradleException('Service compilation must not run') } }\n")
        (self.root / 'build.gradle').write_text(
            "plugins { id 'java' }\nrepositories { maven { url = uri('repository') } }\n"
            "dependencies { implementation 'org.example.probe:runtime:1.0'; "
            "testImplementation 'org.example.probe:test-only:1.0'; implementation project(':child') }\n" + guard)
        (self.root / 'child').mkdir()
        (self.root / 'child/build.gradle').write_text(
            "plugins { id 'java' }\nrepositories { maven { url = uri('../repository') } }\n"
            "dependencies { implementation 'org.example.probe:transitive:1.0' }\n" + guard)
        for root in (self.root, self.root / 'child'):
            source = root / 'src/main/java/Example.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Example {}\n')
        ET.register_namespace('', 'https://schema.gradle.org/dependency-verification')
        ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
        tree = ET.parse(REPOSITORY / 'gradle/verification-metadata.xml')
        namespace = tree.getroot().tag.split('}')[0] + '}'
        components = tree.getroot().find(namespace + 'components')
        self.artifacts = {}
        for name in ('runtime', 'transitive', 'test-only'):
            directory = self.root / 'repository/org/example/probe' / name / '1.0'
            directory.mkdir(parents=True)
            dependency = ('<dependencies><dependency><groupId>org.example.probe</groupId>'
                          '<artifactId>transitive</artifactId><version>1.0</version>'
                          '</dependency></dependencies>') if name == 'runtime' else ''
            pom = directory / f'{name}-1.0.pom'
            pom.write_text('<project><modelVersion>4.0.0</modelVersion><groupId>org.example.probe</groupId>'
                           f'<artifactId>{name}</artifactId><version>1.0</version>{dependency}</project>')
            jar = directory / f'{name}-1.0.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('probe.txt', name)
            self.artifacts[name] = jar
            component = ET.SubElement(components, namespace + 'component',
                                      group='org.example.probe', name=name, version='1.0')
            for artifact in (pom, jar):
                entry = ET.SubElement(component, namespace + 'artifact', name=artifact.name)
                ET.SubElement(entry, namespace + 'sha256',
                              value=hashlib.sha256(artifact.read_bytes()).hexdigest())
        (self.root / 'gradle').mkdir()
        tree.write(self.root / 'gradle/verification-metadata.xml', encoding='utf-8', xml_declaration=True)

    def resolve(self, name, pattern='.*'):
        reports = self.root / 'reports' / name
        bootstrap = os.environ.get('OPENBANK_TEST_GRAPH_PLUGIN_INIT')
        environment = dict(os.environ,
                           GITHUB_DEPENDENCY_GRAPH_ENABLED='false' if bootstrap else 'true',
                           GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR='probe',
                           GITHUB_DEPENDENCY_GRAPH_JOB_ID='synthetic-test',
                           GITHUB_DEPENDENCY_GRAPH_REF='refs/heads/synthetic-test',
                           GITHUB_DEPENDENCY_GRAPH_SHA='a' * 40,
                           GITHUB_DEPENDENCY_GRAPH_WORKSPACE=str(self.root),
                           DEPENDENCY_GRAPH_REPORT_DIR=str(reports),
                           DEPENDENCY_GRAPH_INCLUDE_PROJECTS=pattern,
                           DEPENDENCY_GRAPH_EXCLUDE_CONFIGURATIONS='^(detachedConfiguration.*|classpath)$')
        init = ['--init-script', str(Path(bootstrap).resolve())] if bootstrap else []
        result = subprocess.run(
            [str(REPOSITORY / 'gradlew'), '-p', str(self.root), *init, '--init-script',
             str(SCRIPTS / 'verify-dependency-resolution.init.gradle'),
             ':ForceDependencyResolutionPlugin_resolveAllDependencies', '--dependency-verification',
             'strict', '--no-daemon', '--no-parallel', '--no-configuration-cache', '--max-workers=1'],
            cwd=REPOSITORY, env=environment, capture_output=True, text=True, timeout=180)
        return result, reports / 'probe.json'

    def test_split_graph_exactly_matches_whole_graph_without_compilation(self):
        snapshots = []
        for name, pattern in (('whole', '.*'), ('root', '^:$'), ('child', '^:child$')):
            result, report = self.resolve(name, pattern)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            snapshots.append(json.loads(report.read_text()))

        def normalized(snapshot):
            snapshot = copy.deepcopy(snapshot)
            snapshot.pop('scanned')
            for manifest in snapshot['manifests'].values():
                for node in manifest['resolved'].values():
                    node['dependencies'].sort()
            return snapshot

        self.assertEqual(normalized(snapshots[0]), normalized(resolver.merge_snapshots(snapshots[1:])))
        nodes = snapshots[0]['manifests']['probe']['resolved']
        self.assertEqual(set(nodes), {f'org.example.probe:{n}:1.0' for n in self.artifacts})

    def test_corrupt_runtime_artifact_blocks(self):
        self.assert_corrupt_artifact_blocks('runtime')

    def test_corrupt_test_artifact_blocks(self):
        self.assert_corrupt_artifact_blocks('test-only')

    def assert_corrupt_artifact_blocks(self, name):
        artifact = self.artifacts[name]
        artifact.write_bytes(artifact.read_bytes() + b'corruption-probe')
        result, _ = self.resolve('corrupt')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Dependency verification failed', result.stdout + result.stderr)

    def test_missing_module_blocks_instead_of_publishing_incomplete_graph(self):
        build = self.root / 'build.gradle'
        build.write_text(build.read_text() + "\ndependencies { implementation 'org.example.probe:missing:1.0' }\n")
        result, _ = self.resolve('missing')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Incomplete dependency graph', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
