"""Small, dependency-free validator for the M5 local dataset release gate."""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def validate(manifest_path: Path, retrieval_path: Path, answer_path: Path) -> list[str]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    retrieval = load_jsonl(retrieval_path)
    answers = load_jsonl(answer_path)
    errors: list[str] = []
    all_cases = retrieval + answers
    ids = [case.get("caseId") for case in all_cases]
    if len(ids) != len(set(ids)):
        errors.append("duplicate caseId")
    if len(retrieval) < 120:
        errors.append(f"retrieval count {len(retrieval)} < 120")
    if len(answers) < 30:
        errors.append(f"answer count {len(answers)} < 30")
    retrieval_channels = Counter(
        "ocr" if "ocr" in case.get("evidenceChannels", []) else "fuzzy" if case.get("difficulty") == "fuzzy" else "exact"
        for case in retrieval
    )
    expected = {"exact": 48, "fuzzy": 48, "ocr": 24}
    for category, minimum in expected.items():
        if retrieval_channels[category] < minimum:
            errors.append(f"retrieval {category} count {retrieval_channels[category]} < {minimum}")
    approved = [case for case in all_cases if case.get("review", {}).get("status") == "approved" and case.get("review", {}).get("reviewerCount", 0) >= 2]
    if len(approved) != len(all_cases):
        errors.append(f"approved double-reviewed cases {len(approved)}/{len(all_cases)}")
    split_by_video = {video["videoRef"]: video.get("split") for video in manifest.get("videos", [])}
    seen_splits: dict[str, set[str]] = {}
    for case in all_cases:
        video_ref = case.get("videoRef")
        split = case.get("split")
        if split_by_video.get(video_ref) not in (None, split):
            errors.append(f"split mismatch for {video_ref}")
        seen_splits.setdefault(video_ref, set()).add(split)
    if any(len(splits) > 1 for splits in seen_splits.values()):
        errors.append("video appears in multiple splits")
    if manifest.get("caseCounts", {}).get("retrieval") != len(retrieval):
        errors.append("manifest retrieval count mismatch")
    if manifest.get("caseCounts", {}).get("answer") != len(answers):
        errors.append("manifest answer count mismatch")
    fingerprint_input = dict(manifest)
    fingerprint_input["datasetFingerprint"] = None
    expected_fingerprint = hashlib.sha256(canonical(fingerprint_input)).hexdigest()
    if manifest.get("datasetFingerprint") != expected_fingerprint:
        errors.append("datasetFingerprint mismatch")
    return errors


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print("usage: validate_m5_dataset.py MANIFEST RETRIEVAL_JSONL ANSWER_JSONL", file=sys.stderr)
        return 2
    errors = validate(*(Path(value) for value in argv[1:]))
    if errors:
        print("INVALID")
        for error in errors:
            print(f"- {error}")
        return 1
    print("VALID")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
