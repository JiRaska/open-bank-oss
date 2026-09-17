# OpenBank ClusterFuzzLite seed corpus

This read-only branch holds the two public parser seeds used by the PR fuzz lane.
The source copies are `fuzz/ossfuzz/corpus/` on the reviewed default branch.
PR workflows read this branch through ClusterFuzzLite's git filestore; they do
not push to it or receive a write token. Keep this branch small so PR fuzzing
does not enumerate the repository's large GitHub Actions artifact collection.

See [issue #10268](https://github.com/JiRaska/open-bank-oss/issues/10268).
