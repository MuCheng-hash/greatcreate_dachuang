import unittest
from unittest.mock import patch

import app


class ResourceDiscoveryClassificationTest(unittest.TestCase):
    def test_unconfigured_model_returns_unavailable_without_claims(self):
        payload = {"candidates": [{"providerPlaceId": "A1", "name": "候选地点"}]}
        with patch.object(app, "call_openai_compatible", return_value=None):
            result = app.build_resource_discovery_classification(payload)
        self.assertEqual("unavailable", result["analysisStatus"])
        self.assertEqual([], result["results"])

    def test_unknown_model_place_ids_are_removed(self):
        payload = {"candidates": [{"providerPlaceId": "A1", "name": "候选地点"}]}
        generated = {
            "results": [
                {"providerPlaceId": "A1", "ideologicalRelevant": True},
                {"providerPlaceId": "INVENTED", "ideologicalRelevant": True},
            ]
        }
        with patch.object(app, "call_openai_compatible", return_value=generated):
            result = app.build_resource_discovery_classification(payload)
        self.assertEqual(["A1"], [item["providerPlaceId"] for item in result["results"]])


if __name__ == "__main__":
    unittest.main()
