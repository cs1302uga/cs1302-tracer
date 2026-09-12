"""Ensure fixture normalization preserves observable trace differences."""
import copy
import unittest
from verify import normalize


class NormalizeTests(unittest.TestCase):
    def graph(self, first, second):
        return {"trace": [{"line": 3, "globals": {"a": ["REF", first], "b": ["REF", first]},
                            "heap": {str(first): ["INSTANCE", "Node", ["next", ["REF", second]]],
                                     str(second): ["INSTANCE", "Node", ["next", ["REF", first]]]}}]}

    def test_renames_ids_without_losing_cycles(self):
        self.assertEqual(normalize(self.graph(7, 8)), normalize(self.graph(90, 20)))

    def test_preserves_alias_changes(self):
        before = self.graph(7, 8)
        after = copy.deepcopy(before)
        after["trace"][0]["globals"]["b"] = ["REF", 8]
        self.assertNotEqual(normalize(before), normalize(after))

    def test_preserves_source_location_and_value_changes(self):
        before = self.graph(7, 8)
        after = copy.deepcopy(before)
        after["trace"][0]["line"] = 4
        self.assertNotEqual(normalize(before), normalize(after))
        after = copy.deepcopy(before)
        after["trace"][0]["heap"]["8"].append(["value", 42])
        self.assertNotEqual(normalize(before), normalize(after))

    def test_keeps_isolated_legacy_display_objects(self):
        self.assertEqual(normalize({"heap": {"7": ["LIST"]}}),
                         normalize({"heap": {"99": ["LIST"]}}))
        self.assertNotEqual(normalize({"heap": {"7": ["LIST"]}}), normalize({"heap": {}}))


if __name__ == "__main__":
    unittest.main()
