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


def validate(
    manifest_path: Path,
    retrieval_path: Path,
    answer_path: Path,
    review_index_path: Path,
    *,
    minimum_counts: bool = True,
) -> list[str]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    retrieval = load_jsonl(retrieval_path)
    answers = load_jsonl(answer_path)
    review_index = load_jsonl(review_index_path)
    errors: list[str] = []
    all_cases = retrieval + answers
    ids = [case.get("caseId") for case in all_cases]
    if len(ids) != len(set(ids)):
        errors.append("duplicate caseId")
    normalized_questions = [" ".join(case.get("question", "").casefold().split()) for case in all_cases]
    if len(normalized_questions) != len(set(normalized_questions)):
        errors.append("duplicate question")
    for case in all_cases:
        case_id = case.get("caseId")
        window = case.get("timeWindow", {})
        if window.get("startMs", -1) >= window.get("endMs", -1):
            errors.append(f"case {case_id} has invalid timeWindow")
        if case.get("answerability") == "answerable" and not case.get("requiredFacts"):
            errors.append(f"case {case_id} has no requiredFacts")
    for case in retrieval:
        if case.get("answerability") == "answerable" and not case.get("expectedHits"):
            errors.append(f"case {case.get('caseId')} has no expectedHits")
        for hit in case.get("expectedHits", []):
            if hit.get("startMs", -1) >= hit.get("endMs", -1):
                errors.append(f"case {case.get('caseId')} has invalid expectedHit")
            window = case.get("timeWindow", {})
            if hit.get("endMs", -1) <= window.get("startMs", -1) or hit.get("startMs", -1) >= window.get("endMs", -1):
                errors.append(f"case {case.get('caseId')} expectedHit is outside timeWindow")
    for case in answers:
        if case.get("answerability") == "answerable":
            if not case.get("referenceAnswer"):
                errors.append(f"case {case.get('caseId')} has no referenceAnswer")
            if not case.get("requiredCitations"):
                errors.append(f"case {case.get('caseId')} has no requiredCitations")
        elif case.get("requiredCitations") or case.get("referenceAnswer") is not None:
            errors.append(f"unanswerable case {case.get('caseId')} contains answer evidence")
    if minimum_counts and len(retrieval) < 120:
        errors.append(f"retrieval count {len(retrieval)} < 120")
    if minimum_counts and len(answers) < 30:
        errors.append(f"answer count {len(answers)} < 30")
    indexed = {entry.get("caseId"): entry for entry in review_index}
    if len(indexed) != len(review_index) or set(indexed) != set(ids):
        errors.append("review index caseIds do not match dataset caseIds")
    retrieval_channels = Counter(indexed.get(case_id, {}).get("category") for case_id in ids[:len(retrieval)])
    expected = {"exact": 48, "fuzzy": 48, "ocr": 24}
    for category, minimum in expected.items():
        if minimum_counts and retrieval_channels[category] < minimum:
            errors.append(f"retrieval {category} count {retrieval_channels[category]} < {minimum}")
    approved = [case for case in all_cases if case.get("review", {}).get("status") == "approved" and case.get("review", {}).get("reviewerCount", 0) >= 2]
    if len(approved) != len(all_cases):
        errors.append(f"approved double-reviewed cases {len(approved)}/{len(all_cases)}")
    split_by_video = {video["videoRef"]: video.get("split") for video in manifest.get("videos", [])}
    manifest_split = manifest.get("split")
    if any(split != manifest_split for split in split_by_video.values()):
        errors.append("manifest contains a video from another split")
    seen_splits: dict[str, set[str]] = {}
    for case in all_cases:
        video_ref = indexed.get(case.get("caseId"), {}).get("videoRef")
        split = case.get("split")
        if split != manifest_split:
            errors.append(f"case {case.get('caseId')} is outside manifest split")
        if video_ref not in split_by_video:
            errors.append(f"case {case.get('caseId')} references unknown video {video_ref}")
        elif split_by_video[video_ref] != split:
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
    expected_fingerprint = hashlib.sha256(canonical({
        "manifest": fingerprint_input,
        "retrieval": retrieval,
        "answers": answers,
        "reviewIndex": review_index,
    })).hexdigest()
    if manifest.get("datasetFingerprint") != expected_fingerprint:
        errors.append("datasetFingerprint mismatch")
    return errors


def validate_release(groups: list[tuple[Path, Path, Path, Path]]) -> list[str]:
    errors: list[str] = []
    retrieval_count = answer_count = approved_count = 0
    categories: Counter = Counter()
    video_splits: dict[str, set[str]] = {}
    all_ids: list[str] = []
    for group in groups:
        errors.extend(validate(*group, minimum_counts=False))
        manifest = json.loads(group[0].read_text(encoding="utf-8"))
        retrieval, answers, index = load_jsonl(group[1]), load_jsonl(group[2]), load_jsonl(group[3])
        indexed = {entry["caseId"]: entry for entry in index}
        retrieval_count += len(retrieval)
        answer_count += len(answers)
        all_ids.extend(case["caseId"] for case in retrieval + answers)
        categories.update(indexed[case["caseId"]]["category"] for case in retrieval)
        approved_count += sum(case.get("review", {}).get("status") == "approved" and case.get("review", {}).get("reviewerCount", 0) >= 2 for case in retrieval + answers)
        for video in manifest.get("videos", []):
            video_splits.setdefault(video["videoRef"], set()).add(manifest.get("split"))
    if retrieval_count < 120:
        errors.append(f"release retrieval count {retrieval_count} < 120")
    if answer_count < 30:
        errors.append(f"release answer count {answer_count} < 30")
    for category, minimum in {"exact": 48, "fuzzy": 48, "ocr": 24}.items():
        if categories[category] < minimum:
            errors.append(f"release retrieval {category} count {categories[category]} < {minimum}")
    total = retrieval_count + answer_count
    if approved_count != total:
        errors.append(f"release approved double-reviewed cases {approved_count}/{total}")
    if len(all_ids) != len(set(all_ids)):
        errors.append("caseId appears in multiple release splits")
    if any(len(splits) > 1 for splits in video_splits.values()):
        errors.append("video appears in multiple release splits")
    return errors


def main(argv: list[str]) -> int:
    if len(argv) < 5 or (len(argv) - 1) % 4:
        print("usage: validate_m5_dataset.py MANIFEST RETRIEVAL ANSWER REVIEW_INDEX [MANIFEST RETRIEVAL ANSWER REVIEW_INDEX ...]", file=sys.stderr)
        return 2
    paths = [Path(value) for value in argv[1:]]
    groups = [tuple(paths[offset:offset + 4]) for offset in range(0, len(paths), 4)]
    errors = validate_release(groups)
    if errors:
        print("INVALID")
        for error in errors:
            print(f"- {error}")
        return 1
    print("VALID")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
