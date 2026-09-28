import importlib.util
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "metadata_guard", Path(__file__).with_name("check-verification-metadata-additive.py"))
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)

CONFIG = '<configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>'
OLD = '<component group="com.example" name="core" version="1.0"><artifact name="core-1.0.jar"><sha256 value="' + 'a' * 64 + '" origin="Maven Central"/></artifact></component>'
NEW = '<component group="org.example" name="extra" version="2.0"><artifact name="extra-2.0.pom"><sha256 value="' + 'b' * 64 + '" origin="Maven Central"/></artifact></component>'


def document(components: str, config: str = CONFIG) -> bytes:
    return (f'<verification-metadata xmlns="{guard.NS}">{config}<components>{components}</components>'
            '</verification-metadata>').encode()


class AdditiveMetadataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "gradle").mkdir()
        self.base = document(OLD)
        (self.root / guard.PATH).write_bytes(document(OLD + NEW))

    def tearDown(self):
        self.tmp.cleanup()

    def check(self, base: bytes, head: bytes):
        (self.root / guard.PATH).write_bytes(head)
        with patch.object(guard, "source", side_effect=lambda ref: base if ref == "BASE" else (self.root / guard.PATH).read_bytes()), \
             patch.object(guard, "digest_matches", return_value=True):
            return guard.check("BASE")

    def test_additive_entry_with_central_digest_is_eligible(self):
        (self.root / guard.PATH).write_bytes(document(OLD + NEW))
        with patch.object(guard, "source", side_effect=lambda ref: self.base if ref == "BASE" else (self.root / guard.PATH).read_bytes()), \
             patch.object(guard, "digest_matches", return_value=True) as fetched:
            self.assertEqual(guard.check("BASE"), ["org.example:extra:2.0:extra-2.0.pom"])
        fetched.assert_called_once_with("org.example", "extra", "2.0", "extra-2.0.pom", {'b' * 64})

    def test_mismatching_central_digest_is_ineligible(self):
        (self.root / guard.PATH).write_bytes(document(OLD + NEW))
        with patch.object(guard, "source", side_effect=lambda ref: self.base if ref == "BASE" else (self.root / guard.PATH).read_bytes()), \
             patch.object(guard, "digest_matches", return_value=False), \
             self.assertRaises(guard.Ineligible):
            guard.check("BASE")

    def test_existing_checksum_edit_is_ineligible(self):
        edited = OLD.replace('value="' + 'a' * 64, 'value="' + 'c' * 64)
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(edited + NEW))

    def test_config_change_is_ineligible(self):
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(OLD + NEW, CONFIG.replace("true", "false", 1)))

    def test_removal_and_non_additive_noop_are_ineligible(self):
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(NEW))
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(OLD))

    def test_malformed_xml_and_invalid_sha_are_ineligible(self):
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, b"<broken")
        invalid = NEW.replace('value="' + 'b' * 64, 'value="short')
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(OLD + invalid))

    def test_duplicate_component_and_artifact_are_ineligible(self):
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(OLD + OLD + NEW))
        duplicate_artifact = '<component group="com.example" name="core" version="1.0">' + \
            '<artifact name="core-1.0.jar"><sha256 value="' + 'a' * 64 + '" origin="Maven Central"/></artifact>' * 2 + \
            '</component>'
        with self.assertRaises(guard.Ineligible):
            self.check(document(duplicate_artifact), document(duplicate_artifact + NEW))

    def test_adding_checksum_to_existing_artifact_is_ineligible(self):
        added_checksum = OLD.replace('</artifact>', '<sha256 value="' + 'c' * 64 +
                                     '" origin="second source"/></artifact>')
        with self.assertRaises(guard.Ineligible):
            self.check(self.base, document(added_checksum + NEW))

    def test_unsafe_maven_path_is_ineligible(self):
        for group, name, version, artifact in (
            ("org.example", "extra", "2.0", "../extra.pom"),
            ("org.example", "..", "2.0", "extra.pom"),
            ("org.example", "extra", "..", "extra.pom"),
        ):
            with self.subTest(name=name, version=version, artifact=artifact), \
                 self.assertRaises(guard.Ineligible):
                guard.digest_matches(group, name, version, artifact, {'b' * 64})

    def test_network_failure_is_ineligible(self):
        with patch.object(guard.urllib.request, "urlopen", side_effect=urllib.error.URLError("offline")), \
             self.assertRaises(guard.Ineligible):
            guard.digest_matches("org.example", "extra", "2.0", "extra-2.0.pom", {'b' * 64})

    def test_download_limit_is_enforced_while_streaming(self):
        class Response:
            def __init__(self):
                self.headers = {}

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def geturl(self):
                return guard.CENTRAL + "org/example/extra/2.0/extra-2.0.pom"

            def read(self, size):
                return b"x" * 11

        with patch.object(guard, "MAX_ARTIFACT_BYTES", 10), \
             patch.object(guard.urllib.request, "urlopen", return_value=Response()), \
             self.assertRaises(guard.Ineligible):
            guard.digest_matches("org.example", "extra", "2.0", "extra-2.0.pom", {'b' * 64})


if __name__ == "__main__":
    unittest.main()
