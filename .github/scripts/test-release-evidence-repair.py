# SPDX-License-Identifier: Apache-2.0
import unittest
from release_evidence_assets import expected_release_assets
from release_evidence_repair import apply, plan, bind_bundle, binding_digest, VerifiedBundle


class API:
    def __init__(self):
        self.repository = 'example/project'
        self.rows = {}
        self.posts = 0
        self.mode = 'normal'
        self.commit = 'a' * 40

    def release(self, tag):
        return {'id': 7, 'tag_name': tag}

    def tag_commit(self, tag):
        return self.commit

    def assets(self, release_id):
        return [{'id': n, 'name': n} for n in self.rows]

    def download(self, asset_id, expected_size):
        if len(self.rows[asset_id]) != expected_size:
            raise ValueError('existing asset bytes differ: metadata size mismatch')
        return self.rows[asset_id]

    def upload(self, release_id, name, data):
        self.posts += 1
        if self.mode == 'false422':
            return 422
        self.rows[name] = data if self.mode != 'wrong422' else b'wrong'
        if self.mode == 'timeout':
            raise TimeoutError('ambiguous successful write')
        return 201


class RepairTests(unittest.TestCase):
    def setUp(self):
        self.api = API()
        self.args = dict(tag='example-v1.0.0', commit='a' * 40, release_id=7, workflow_revision='b' * 40)
        self.p = plan(self.api, **self.args)
        self.bundle = {n: n.encode() for n in expected_release_assets(self.args['tag'])}
        self.p = bind_bundle(self.p, self.bundle, repository='example/project', producer_run_id=19, producer_run_attempt=1)

    def verify(self, p, b):
        self.assertEqual(self.api.posts, 0)
        self.assertEqual(len(b), 9)
        return VerifiedBundle(binding_digest(p))

    def test_plan_is_read_only(self):
        self.assertEqual(self.api.posts, 0)
        self.assertEqual(self.api.rows, {})

    def test_partial_and_wrong_source_refused(self):
        self.api.rows['existing'] = b'preserve'
        with self.assertRaises(ValueError): plan(self.api, **self.args)
        self.api.rows.clear()
        self.api.commit = 'c' * 40
        with self.assertRaises(ValueError): plan(self.api, **self.args)
        self.assertEqual(self.api.posts, 0)

    def test_identity_inputs_fail_closed(self):
        for key, value in [('tag', ''), ('commit', ''), ('release_id', 8), ('release_id', True), ('workflow_revision', 'main')]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                plan(self.api, **(self.args | {key: value}))

    def test_signing_or_attestation_failure_zero_uploads(self):
        for message in ['signing', 'missing provenance', 'wrong subject', 'wrong producer']:
            def reject(p, b): raise ValueError(message)
            with self.assertRaises(ValueError): apply(self.api, self.p, self.bundle, reject)
        self.assertEqual(self.api.posts, 0)

    def test_all_nine_required_before_verification(self):
        b = dict(self.bundle); b.pop(next(iter(b)))
        with self.assertRaises(ValueError): apply(self.api, self.p, b, self.verify)
        self.assertEqual(self.api.posts, 0)

    def test_success_and_exact_resume(self):
        result = apply(self.api, self.p, self.bundle, self.verify)
        self.assertEqual(len(result), 9)
        before = self.api.posts
        self.assertEqual(apply(self.api, self.p, self.bundle, lambda p, b: VerifiedBundle(binding_digest(p)), resume=True), result)
        self.assertEqual(self.api.posts, before)

    def test_422_never_assumed_success(self):
        for mode in ['false422', 'wrong422']:
            self.api = API(); self.api.mode = mode
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                apply(self.api, self.p, self.bundle, self.verify)
            self.assertEqual(self.api.posts, 1)

    def test_ambiguous_post_readback_exact_bytes(self):
        self.api.mode = 'timeout'
        self.assertEqual(len(apply(self.api, self.p, self.bundle, self.verify)), 9)

    def test_false_none_and_wrong_receipts_never_authorize(self):
        for receipt in [False, None, True, VerifiedBundle('0' * 64)]:
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                apply(self.api, self.p, self.bundle, lambda p, b: receipt)
        self.assertEqual(self.api.posts, 0)

    def test_persisted_binding_rejects_regenerated_bytes(self):
        changed = self.bundle | {next(iter(self.bundle)): b'regenerated'}
        with self.assertRaises(ValueError): apply(self.api, self.p, changed, self.verify, resume=True)
        self.assertEqual(self.api.posts, 0)

    def test_wrong_transport_repository_zero_writes(self):
        self.api.repository = 'example/other'
        with self.assertRaisesRegex(ValueError, 'repository differs'):
            apply(self.api, self.p, self.bundle, self.verify)
        self.assertEqual(self.api.posts, 0)

    def test_concurrent_expected_name_conflict_stops_immediately(self):
        upload = self.api.upload
        def conflict(release_id, name, data):
            result = upload(release_id, name, data)
            self.api.rows[sorted(self.bundle)[-1]] = b'conflicting concurrent asset'
            return result
        self.api.upload = conflict
        with self.assertRaisesRegex(ValueError, 'bytes differ'):
            apply(self.api, self.p, self.bundle, self.verify)
        self.assertEqual(self.api.posts, 1)

    def test_duplicate_asset_names_rejected(self):
        self.api.assets = lambda release_id: [{'id': 1, 'name': 'duplicate'}, {'id': 2, 'name': 'duplicate'}]
        with self.assertRaises(ValueError): plan(self.api, **self.args)
        self.assertEqual(self.api.posts, 0)

    def test_tag_moves_after_first_post_stops_remaining_writes(self):
        upload = self.api.upload
        def move(release_id, name, data):
            result = upload(release_id, name, data)
            self.api.commit = 'c' * 40
            return result
        self.api.upload = move
        with self.assertRaisesRegex(ValueError, 'identity changed'):
            apply(self.api, self.p, self.bundle, self.verify)
        self.assertEqual(self.api.posts, 1)
        self.assertEqual(len(self.api.rows), 1)  # Never delete the committed first object.

    def test_changed_resume_and_concurrent_assets_rejected(self):
        self.api.rows[next(iter(self.bundle))] = b'wrong'
        with self.assertRaises(ValueError): apply(self.api, self.p, self.bundle, self.verify, resume=True)
        self.assertEqual(self.api.posts, 0)


if __name__ == '__main__':
    unittest.main()
