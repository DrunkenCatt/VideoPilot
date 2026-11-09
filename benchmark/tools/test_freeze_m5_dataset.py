import tempfile
import unittest
from pathlib import Path

from freeze_m5_dataset import freeze_manifest, write_manifest_once


class FreezeM5DatasetTest(unittest.TestCase):
    def test_freezes_approved_dataset_and_recomputes_fingerprint(self):
        manifest = {"status": "draft", "frozenAt": None, "datasetFingerprint": "old"}
        retrieval = [{"caseId": "r1", "review": {"status": "approved", "reviewerCount": 2}}]
        answers = [{"caseId": "a1", "review": {"status": "approved", "reviewerCount": 2}}]
        index = [{"caseId": "r1"}, {"caseId": "a1"}]

        frozen = freeze_manifest(manifest, retrieval, answers, index, "2026-11-09T13:30:00Z")

        self.assertEqual("frozen", frozen["status"])
        self.assertEqual("2026-11-09T13:30:00Z", frozen["frozenAt"])
        self.assertRegex(frozen["datasetFingerprint"], "^[a-f0-9]{64}$")
        self.assertNotEqual("old", frozen["datasetFingerprint"])

    def test_rejects_unapproved_case(self):
        manifest = {"status": "draft", "frozenAt": None, "datasetFingerprint": "old"}
        retrieval = [{"caseId": "r1", "review": {"status": "in_review", "reviewerCount": 2}}]

        with self.assertRaisesRegex(ValueError, "not double-approved"):
            freeze_manifest(manifest, retrieval, [], [{"caseId": "r1"}], "2026-11-09T13:30:00Z")

    def test_does_not_overwrite_frozen_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "frozen.json"
            output.write_text("original", encoding="utf-8")

            with self.assertRaises(FileExistsError):
                write_manifest_once(output, {"status": "frozen"})

            self.assertEqual("original", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
