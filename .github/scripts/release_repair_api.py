# SPDX-License-Identifier: Apache-2.0
"""Authenticated repository-bound GitHub transport for repair preflight/readback.

Read-only preflight transport and a separately bound append-only publication adapter.
"""
import json
import re
import subprocess
import urllib.request
from urllib.parse import quote


# This dedicated single-release workflow has only a handful of jobs/artifacts.
# Reject unexpected inventory expansion instead of unbounded API pagination.
MAX_REPAIR_INVENTORY = 1000


def require_main_inclusion(api, source_sha, main_sha):
    """Prove source inclusion in the approved main revision, not historical review."""
    if any(re.fullmatch(r'[0-9a-f]{40}', value) is None for value in (source_sha, main_sha)):
        raise ValueError('explicit source/main identities required')
    comparison = api.get(f'compare/{source_sha}...{main_sha}')
    if (not isinstance(comparison, dict) or comparison.get('status') not in ('ahead', 'identical')
            or type(comparison.get('behind_by')) is not int or comparison['behind_by'] != 0
            or type(comparison.get('ahead_by')) is not int or comparison['ahead_by'] < 0
            or (comparison.get('status') == 'identical' and (comparison['ahead_by'] != 0 or source_sha != main_sha))
            or (comparison.get('status') == 'ahead' and comparison['ahead_by'] == 0)
            or not isinstance(comparison.get('merge_base_commit'), dict)
            or comparison['merge_base_commit'].get('sha') != source_sha
            or not isinstance(comparison.get('base_commit'), dict)
            or comparison['base_commit'].get('sha') != source_sha):
        raise ValueError('release source is not proven included in approved main')



class ReadOnlyGitHub:
    def __init__(self, repository, tag_commit):
        if (re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) is None
                or any(segment in ('.', '..') for segment in repository.split('/'))):
            raise ValueError('invalid repository')
        self.repository = repository
        self.tag_commit = tag_commit

    def get(self, endpoint):
        result = subprocess.run(['gh', 'api', f'repos/{self.repository}/{endpoint}'], check=True, capture_output=True)
        return json.loads(result.stdout)

    def pages(self, endpoint, key=None):
        if key is None:
            # gh follows the server Link headers rather than guessing from page size.
            response = subprocess.run(
                ['gh', 'api', '--paginate', '--slurp', f'repos/{self.repository}/{endpoint}'],
                check=True, capture_output=True)
            batches = json.loads(response.stdout)
            if not isinstance(batches, list) or not batches or any(not isinstance(v, list) for v in batches):
                raise ValueError('incomplete paginated response')
            items = [item for batch in batches for item in batch]
            self.unique_ids(items)
            return items
        result = []
        total = None
        page = 1
        while True:
            batch = self.get(endpoint + ('&' if '?' in endpoint else '?') + f'per_page=100&page={page}')
            count = batch.get('total_count') if isinstance(batch, dict) else None
            if type(count) is not int or not 0 <= count <= MAX_REPAIR_INVENTORY or (total is not None and count != total):
                raise ValueError('missing or changing pagination total')
            total = count
            items = batch.get(key)
            if not isinstance(items, list) or len(items) > 100:
                raise ValueError('incomplete paginated response')
            result.extend(items)
            self.unique_ids(result)
            if len(result) > total:
                raise ValueError('pagination exceeds declared total')
            if len(result) == total:
                return result
            if len(items) != 100:
                raise ValueError('truncated pagination')
            page += 1

    @staticmethod
    def unique_ids(items):
        ids = [item.get('id') if isinstance(item, dict) else None for item in items]
        if len(ids) > MAX_REPAIR_INVENTORY or any(type(value) is not int or value <= 0 for value in ids) or len(set(ids)) != len(ids):
            raise ValueError('invalid or repeated paginated identity')

    def release(self, tag):
        return self.get('releases/tags/' + quote(tag, safe=''))

    def assets(self, release_id):
        return self.pages(f'releases/{release_id}/assets')

    def download(self, asset_id, expected_size):
        metadata = self.get(f'releases/assets/{asset_id}')
        if metadata['id'] != asset_id or type(metadata['size']) is not int or metadata['size'] != expected_size:
            raise ValueError('asset metadata length mismatch')
        # Public repository bytes: no credential is forwarded to download redirects.
        url = metadata['browser_download_url']
        prefix = f'https://github.com/{self.repository}/releases/download/'
        if not url.startswith(prefix):
            raise ValueError('unexpected asset download origin')
        with urllib.request.urlopen(url) as response:
            data = response.read(expected_size + 1)
        if len(data) != expected_size:
            raise ValueError('asset download length mismatch')
        return data


class AppendOnlyGitHub(ReadOnlyGitHub):
    """One fixed release's append-only endpoint; reads remain independently checked."""
    def __init__(self, repository, tag_commit, release_id):
        super().__init__(repository, tag_commit)
        if type(release_id) is not int or release_id <= 0:
            raise ValueError('invalid bound release ID')
        self.release_id = release_id

    def upload(self, release_id, name, data):
        import os
        import urllib.error
        if type(release_id) is not int or release_id != self.release_id:
            raise ValueError('upload release differs from bound release')
        if not isinstance(name, str) or not name or '/' in name or '\\' in name or type(data) is not bytes or not data:
            raise ValueError('invalid upload asset')
        token = os.environ.get('GH_TOKEN')
        if not token:
            raise ValueError('authenticated upload token required')

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None

        url = f'https://uploads.github.com/repos/{self.repository}/releases/{release_id}/assets?name=' + quote(name, safe='')
        request = urllib.request.Request(url, data=data, method='POST', headers={
            'Authorization': 'Bearer ' + token, 'Content-Type': 'application/octet-stream',
            'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
        })
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
                if response.status != 201:
                    raise OSError('upload outcome requires independent readback')
        except urllib.error.HTTPError as exc:
            # The pure controller performs independent exact readback even on a
            # duplicate response or ambiguous error; no response is treated as proof of success.
            raise OSError(f'upload returned HTTP {exc.code}; readback required') from None
