"""Safety checks for the ESO CRD storage rewrite; no cluster access required."""

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("migrate-eso-stored-versions.py")
SPEC = importlib.util.spec_from_file_location("migrate_eso_stored_versions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def crd(stored=("v1beta1", "v1"), storage="v1"):
    return {
        "metadata": {"name": "externalsecrets.external-secrets.io", "uid": "crd-uid", "resourceVersion": "10"},
        "spec": {
            "group": "external-secrets.io", "scope": "Namespaced",
            "names": {"plural": "externalsecrets"},
            "versions": [
                {"name": "v1beta1", "served": False, "storage": storage == "v1beta1"},
                {"name": "v1", "served": True, "storage": storage == "v1"},
            ],
        },
        "status": {"storedVersions": list(stored)},
    }


def obj(marker=None):
    annotations = {} if marker is None else {MODULE.ANNOTATION: marker}
    return {"metadata": {"name": "sample", "namespace": "test", "uid": "object-uid",
                         "resourceVersion": "20", "annotations": annotations}}


class FakeKubectl:
    def __init__(self, current=None):
        self.current = current or obj()
        self.calls = []
        self.crd = crd()

    def call(self, *args, payload=None):
        self.calls.append((args, payload))
        if args[:2] == ("get", "crd"):
            return self.crd
        if args[:2] == ("get", "externalsecrets.external-secrets.io"):
            return {"items": [self.current]}
        if args[:2] == ("patch", "externalsecrets.external-secrets.io"):
            assert payload[0] == {"op": "test", "path": "/metadata/uid", "value": "object-uid"}
            assert payload[1] == {"op": "test", "path": "/metadata/resourceVersion", "value": "20"}
            self.current = obj(payload[2]["value"])
            self.current["metadata"]["resourceVersion"] = "21"
            return self.current
        if args[:2] == ("patch", "crd"):
            assert payload[0] == {"op": "test", "path": "/metadata/uid", "value": "crd-uid"}
            assert payload[1] == {"op": "test", "path": "/metadata/resourceVersion", "value": "10"}
            assert payload[2]["value"] == ["v1beta1", "v1"]
            self.crd["status"]["storedVersions"] = ["v1"]
            return self.crd
        raise AssertionError(args)


class MigrationTests(unittest.TestCase):
    def test_inventory_selects_only_v1_promoted_crds(self):
        items = []
        for plural in ("externalsecrets", "secretstores", "clustersecretstores", "pushsecrets"):
            candidate = crd(stored=("v1alpha1",) if plural == "pushsecrets" else ("v1beta1", "v1"))
            candidate["spec"]["names"]["plural"] = plural
            candidate["metadata"]["name"] = plural + ".external-secrets.io"
            items.append(candidate)

        class InventoryKubectl:
            def call(self, *args):
                self_args = args
                assert self_args == ("get", "crds")
                return {"items": items}

        self.assertEqual(3, len(MODULE.inventory(InventoryKubectl())))

    def test_rejects_old_or_unknown_storage_before_any_write(self):
        for candidate in (crd(storage="v1beta1"), crd(stored=("v1alpha1", "v1"))):
            with self.assertRaises(MODULE.MigrationError):
                MODULE.crd_from_object(candidate)

    def test_rewrite_advances_resource_version_and_verifies_all_objects(self):
        kubectl = FakeKubectl()
        candidate = MODULE.crd_from_object(kubectl.crd)
        prior = [obj()]
        MODULE.rewrite(kubectl, candidate, prior[0], "run-id")
        self.assertEqual(1, MODULE.verify(kubectl, candidate, "run-id", prior))
        self.assertEqual("21", kubectl.current["metadata"]["resourceVersion"])

    def test_unmarked_new_object_prevents_finalization(self):
        kubectl = FakeKubectl(current=obj())
        candidate = MODULE.crd_from_object(kubectl.crd)
        with self.assertRaises(MODULE.MigrationError):
            MODULE.verify(kubectl, candidate, "run-id", [])

    def test_crd_change_prevents_finalization(self):
        kubectl = FakeKubectl()
        candidate = MODULE.crd_from_object(kubectl.crd)
        kubectl.crd["metadata"]["resourceVersion"] = "11"
        # A concurrent CRD spec write can retain storedVersions; optimistic patch must use
        # the new resourceVersion rather than the stale inventory value.
        updated = MODULE.recheck_crd(kubectl, candidate)
        self.assertEqual("11", updated.resource_version)
        kubectl.crd["status"]["storedVersions"] = ["v1"]
        with self.assertRaises(MODULE.MigrationError):
            MODULE.recheck_crd(kubectl, candidate)

    def test_finalization_checks_status_and_rereads_crd(self):
        kubectl = FakeKubectl()
        candidate = MODULE.crd_from_object(kubectl.crd)
        MODULE.finalize(kubectl, candidate)
        self.assertEqual(["v1"], kubectl.crd["status"]["storedVersions"])


if __name__ == "__main__":
    unittest.main()
