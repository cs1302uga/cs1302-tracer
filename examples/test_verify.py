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

    def test_modern_references_preserve_aliases_and_input_offsets(self):
        before = {"steps": [{"stack": [{"variables": [{"value": {"ref": 7}},
                                                   {"value": {"ref": 7}}]}],
                             "heap": {"7": {"id": 7, "kind": "object", "fields": []}},
                             "stdinConsumed": "hello", "stdinOffset": 5}]}
        renamed = copy.deepcopy(before)
        renamed["steps"][0]["heap"] = {"90": {"id": 90, "kind": "object", "fields": []}}
        for variable in renamed["steps"][0]["stack"][0]["variables"]:
            variable["value"]["ref"] = 90
        self.assertEqual(normalize(before), normalize(renamed))
        renamed["steps"][0]["heap"]["90"]["id"] = 91
        self.assertNotEqual(normalize(before), normalize(renamed))
        renamed["steps"][0]["heap"]["90"]["id"] = 90
        renamed["steps"][0]["stdinOffset"] = 6
        self.assertNotEqual(normalize(before), normalize(renamed))

    def test_keeps_isolated_legacy_display_objects(self):
        self.assertEqual(normalize({"heap": {"7": ["LIST"]}}),
                         normalize({"heap": {"99": ["LIST"]}}))
        self.assertNotEqual(normalize({"heap": {"7": ["LIST"]}}), normalize({"heap": {}}))


class ReadmeContractTests(unittest.TestCase):
    def test_readme_examples_match_verified_fixtures(self):
        from pathlib import Path
        import json
        root = Path(__file__).resolve().parent.parent
        readme = (root / "README.md").read_text()
        for heading, fixture in [("### 1. PythonTutor Format", "record.json"),
                                 ("### 2. Modern Clean Format", "modern_record.json")]:
            section = readme.split(heading, 1)[1]
            example = section.split("```json", 1)[1].split("```", 1)[0]
            expected = json.loads((root / "examples/regression" / fixture).read_text())
            self.assertEqual(json.loads(example), expected)



if __name__ == "__main__":
    unittest.main()
