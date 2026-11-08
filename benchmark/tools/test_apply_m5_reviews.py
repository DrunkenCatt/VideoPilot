import unittest

from apply_m5_reviews import merge_reviews


class ApplyM5ReviewsTest(unittest.TestCase):
    def test_approves_only_when_two_distinct_reviewers_approve(self):
        cases = [{"caseId": "c1", "review": {"status": "in_review", "reviewerCount": 0}}]
        first = {"c1": {"decision": "approved", "reviewer": "alice", "notes": "ok"}}
        second = {"c1": {"decision": "approved", "reviewer": "bob", "notes": "ok"}}

        merged = merge_reviews(cases, first, second)

        self.assertEqual("approved", merged[0]["review"]["status"])
        self.assertEqual(2, merged[0]["review"]["reviewerCount"])

    def test_keeps_disagreement_out_of_the_scored_set(self):
        cases = [{"caseId": "c1", "review": {"status": "in_review", "reviewerCount": 0}}]
        first = {"c1": {"decision": "approved", "reviewer": "alice", "notes": "ok"}}
        second = {"c1": {"decision": "rejected", "reviewer": "bob", "notes": "window wrong"}}

        merged = merge_reviews(cases, first, second)

        self.assertEqual("in_review", merged[0]["review"]["status"])
        self.assertEqual(2, merged[0]["review"]["reviewerCount"])
        self.assertIn("DISPUTED", merged[0]["review"]["notes"])

    def test_rejects_same_reviewer_twice(self):
        cases = [{"caseId": "c1", "review": {"status": "in_review", "reviewerCount": 0}}]
        first = {"c1": {"decision": "approved", "reviewer": "alice", "notes": ""}}
        second = {"c1": {"decision": "approved", "reviewer": "alice", "notes": ""}}

        with self.assertRaisesRegex(ValueError, "distinct reviewers"):
            merge_reviews(cases, first, second)


if __name__ == "__main__":
    unittest.main()
