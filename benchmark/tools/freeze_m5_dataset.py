"""Freeze a double-reviewed M5 dataset manifest without publishing local case text."""

from __future__ import annotations

import hashlib
import json
import sys
from copy import deepcopy
from datetime import datetime
from pathlib import Path

from validate_m5_dataset import canonical, load_jsonl, validate


def freeze_manifest(manifest: dict, retrieval: list[dict], answers: list[dict],
                    review_index: list[dict], frozen_at: str) -> dict:
    if manifest.get("status") != "draft":
        raise ValueError("only a draft manifest can be frozen")
    try:
        parsed = datetime.fromisoformat(frozen_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("frozenAt must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError("frozenAt must include a timezone")
    cases = retrieval + answers
    unapproved = [case["caseId"] for case in cases
                  if case.get("review", {}).get("status") != "approved"
                  or case.get("review", {}).get("reviewerCount", 0) < 2]
    if unapproved:
        raise ValueError(f"case {unapproved[0]} is not double-approved")
    if {case["caseId"] for case in cases} != {entry["caseId"] for entry in review_index}:
        raise ValueError("review index caseIds do not match dataset caseIds")

    frozen = deepcopy(manifest)
    frozen["status"] = "frozen"
    frozen["frozenAt"] = frozen_at
    frozen["datasetFingerprint"] = None
    payload = {"manifest": frozen, "retrieval": retrieval, "answers": answers,
               "reviewIndex": review_index}
    frozen["datasetFingerprint"] = hashlib.sha256(canonical(payload)).hexdigest()
    return frozen


def write_manifest_once(path: Path, manifest: dict) -> None:
    with path.open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main(argv: list[str]) -> int:
    if len(argv) != 7:
        print("usage: freeze_m5_dataset.py DRAFT RETRIEVAL ANSWER REVIEW_INDEX OUTPUT FROZEN_AT", file=sys.stderr)
        return 2
    draft_path, retrieval_path, answer_path, review_path, output_path = map(Path, argv[1:6])
    frozen_at = argv[6]
    manifest = json.loads(draft_path.read_text(encoding="utf-8"))
    retrieval, answers, review_index = (load_jsonl(retrieval_path), load_jsonl(answer_path),
                                        load_jsonl(review_path))
    frozen = freeze_manifest(manifest, retrieval, answers, review_index, frozen_at)
    write_manifest_once(output_path, frozen)
    errors = validate(output_path, retrieval_path, answer_path, review_path, minimum_counts=False)
    if errors:
        output_path.unlink(missing_ok=True)
        raise ValueError("; ".join(errors))
    print(f"frozen {frozen['split']}={frozen['datasetFingerprint']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
