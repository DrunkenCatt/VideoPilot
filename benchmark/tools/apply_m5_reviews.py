"""Merge two independent CSV reviews into local M5 case files."""

from __future__ import annotations

import csv
import json
import sys
from copy import deepcopy
from pathlib import Path

DECISIONS = {"approved", "rejected"}


def read_reviews(path: Path) -> dict[str, dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    reviews: dict[str, dict[str, str]] = {}
    for row in rows:
        case_id = row.get("caseId", "").strip()
        decision = row.get("decision", "").strip().lower()
        reviewer = row.get("reviewer", "").strip()
        if not case_id or decision not in DECISIONS or not reviewer:
            raise ValueError(f"incomplete review row in {path.name}: {case_id or '<missing caseId>'}")
        if case_id in reviews:
            raise ValueError(f"duplicate review for {case_id} in {path.name}")
        reviews[case_id] = {"decision": decision, "reviewer": reviewer, "notes": row.get("notes", "").strip()}
    return reviews


def merge_reviews(cases: list[dict], first: dict[str, dict[str, str]], second: dict[str, dict[str, str]]) -> list[dict]:
    case_ids = {case["caseId"] for case in cases}
    if set(first) != case_ids or set(second) != case_ids:
        raise ValueError("both review files must contain every case exactly once")
    merged = deepcopy(cases)
    for case in merged:
        case_id = case["caseId"]
        left, right = first[case_id], second[case_id]
        if left["reviewer"].casefold() == right["reviewer"].casefold():
            raise ValueError(f"case {case_id} requires distinct reviewers")
        decisions = {left["decision"], right["decision"]}
        if decisions == {"approved"}:
            status, prefix = "approved", "APPROVED"
        elif decisions == {"rejected"}:
            status, prefix = "rejected", "REJECTED"
        else:
            status, prefix = "in_review", "DISPUTED"
        notes = "; ".join(value for value in (left["notes"], right["notes"]) if value)
        case["review"] = {"status": status, "reviewerCount": 2,
                          "notes": f"{prefix}: {notes}".rstrip(": ")}
    return merged


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n", encoding="utf-8")


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print("usage: apply_m5_reviews.py RETRIEVAL_JSONL ANSWER_JSONL REVIEWER_1_CSV REVIEWER_2_CSV", file=sys.stderr)
        return 2
    retrieval_path, answer_path, first_path, second_path = map(Path, argv[1:])
    retrieval, answers = load_jsonl(retrieval_path), load_jsonl(answer_path)
    first, second = read_reviews(first_path), read_reviews(second_path)
    all_cases = retrieval + answers
    merged = merge_reviews(all_cases, first, second)
    write_jsonl(retrieval_path, merged[:len(retrieval)])
    write_jsonl(answer_path, merged[len(retrieval):])
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
