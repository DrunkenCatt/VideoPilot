import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from validate_m5_dataset import canonical, validate


class ValidateM5DatasetTest(unittest.TestCase):
    def test_rejects_frozen_manifest_without_frozen_at(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest()
            manifest["status"] = "frozen"
            manifest["frozenAt"] = None
            retrieval = [self._retrieval("r-1")]
            answers = [self._answer("a-1")]
            index = [self._index("r-1", "exact"), self._index("a-1", "answer")]
            manifest["datasetFingerprint"] = self._fingerprint(manifest, retrieval, answers, index)
            self._write(root, manifest, retrieval, answers, index)

            errors = validate(root / "manifest.json", root / "retrieval.jsonl", root / "answer.jsonl", root / "index.jsonl", minimum_counts=False)

            self.assertIn("frozen manifest requires frozenAt", errors)

    def test_fingerprint_covers_cases_and_review_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest()
            retrieval = [self._retrieval("r-1")]
            answers = [self._answer("a-1")]
            index = [self._index("r-1", "exact"), self._index("a-1", "answer")]
            self._write(root, manifest, retrieval, answers, index)
            first = self._fingerprint(manifest, retrieval, answers, index)
            manifest["datasetFingerprint"] = first
            self._write(root, manifest, retrieval, answers, index)

            changed = [dict(retrieval[0], question="changed")]
            errors = validate(root / "manifest.json", root / "retrieval.jsonl", root / "answer.jsonl", root / "index.jsonl", minimum_counts=False)
            self.assertEqual([], errors)
            self._write(root, manifest, changed, answers, index)
            errors = validate(root / "manifest.json", root / "retrieval.jsonl", root / "answer.jsonl", root / "index.jsonl", minimum_counts=False)
            self.assertIn("datasetFingerprint mismatch", errors)

    def test_rejects_missing_review_index_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest()
            retrieval = [self._retrieval("r-1")]
            answers = [self._answer("a-1")]
            index = [self._index("r-1", "exact")]
            manifest["datasetFingerprint"] = self._fingerprint(manifest, retrieval, answers, index)
            self._write(root, manifest, retrieval, answers, index)

            errors = validate(root / "manifest.json", root / "retrieval.jsonl", root / "answer.jsonl", root / "index.jsonl", minimum_counts=False)

            self.assertIn("review index caseIds do not match dataset caseIds", errors)

    def test_rejects_invalid_evidence_windows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest()
            retrieval = [self._retrieval("r-1")]
            retrieval[0]["timeWindow"] = {"startMs": 10, "endMs": 10}
            answers = [self._answer("a-1")]
            index = [self._index("r-1", "exact"), self._index("a-1", "answer")]
            manifest["datasetFingerprint"] = self._fingerprint(manifest, retrieval, answers, index)
            self._write(root, manifest, retrieval, answers, index)

            errors = validate(root / "manifest.json", root / "retrieval.jsonl", root / "answer.jsonl", root / "index.jsonl", minimum_counts=False)

            self.assertIn("case r-1 has invalid timeWindow", errors)

    def test_rejects_case_mapped_to_unknown_video(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest()
            retrieval = [self._retrieval("r-1")]
            answers = [self._answer("a-1")]
            index = [dict(self._index("r-1", "exact"), videoRef="unknown"), self._index("a-1", "answer")]
            manifest["datasetFingerprint"] = self._fingerprint(manifest, retrieval, answers, index)
            self._write(root, manifest, retrieval, answers, index)

            errors = validate(root / "manifest.json", root / "retrieval.jsonl", root / "answer.jsonl", root / "index.jsonl", minimum_counts=False)

            self.assertIn("case r-1 references unknown video unknown", errors)

    @staticmethod
    def _manifest():
        return {"datasetFingerprint": "", "split": "dev", "status": "draft", "frozenAt": None,
                "videos": [{"videoRef": "video-a", "split": "dev"}], "caseCounts": {"retrieval": 1, "answer": 1}}

    @staticmethod
    def _retrieval(case_id):
        return {"caseId": case_id, "split": "dev", "question": "q", "timeWindow": {"startMs": 0, "endMs": 1}, "review": {"status": "approved", "reviewerCount": 2}}

    @staticmethod
    def _answer(case_id):
        return {"caseId": case_id, "split": "dev", "question": "answer q", "timeWindow": {"startMs": 0, "endMs": 1}, "review": {"status": "approved", "reviewerCount": 2}}

    @staticmethod
    def _index(case_id, category):
        return {"caseId": case_id, "videoRef": "video-a", "category": category}

    @staticmethod
    def _fingerprint(manifest, retrieval, answers, index):
        value = dict(manifest)
        value["datasetFingerprint"] = None
        return hashlib.sha256(canonical({"manifest": value, "retrieval": retrieval, "answers": answers, "reviewIndex": index})).hexdigest()

    @staticmethod
    def _write(root, manifest, retrieval, answers, index):
        (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        for name, rows in (("retrieval.jsonl", retrieval), ("answer.jsonl", answers), ("index.jsonl", index)):
            (root / name).write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
