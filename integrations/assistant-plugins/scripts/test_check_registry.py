#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression proof for the literal Kotlin registry boundary reader."""
import unittest
import check


def source(schema):
    return '''val capabilities: Map<String, String> = mapOf(
        "sample" to "query.sample.readonly",
    )
    ToolDefinition(name = "sample", inputSchema = ''' + schema + ''')'''


class RegistryTests(unittest.TestCase):
    def test_nested_keywords_are_not_properties(self):
        parsed = check.parse_registry(source('''obj(mapOf(
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 50),
            "type" to strProp(), "description" to strProp(),
            "minimum" to strProp(), "maximum" to strProp(),
        ), required = listOf("type"))'''))["sample"]
        self.assertEqual(parsed["properties"], ["description", "limit", "maximum", "minimum", "type"])
        self.assertEqual(parsed["required"], ["type"])
        listing = {"tools": {"included": [{"name": "sample", "capability": "query.sample.readonly",
                   "readOnlyHint": True, "title": "Sample",
                   "properties": ["description", "limit", "minimum", "type"], "required": ["type"]}],
                   "excluded": []}}
        self.assertTrue(any("properties" in err for err in check.check_tools(listing, {"sample": parsed})))

    def test_quotes_comments_and_nested_values(self):
        parsed = check.parse_registry(source(r'''obj(mapOf(
            // "fake" to strProp()), ToolDefinition(
            "account_id2" to strProp("a ) \\\" quoted ( description"),
            /* nested /* comment ) */ "fake" to x */
            "limit" to mapOf("minimum" to 1, "items" to listOf("x,y")),
        ), listOf("account_id2"))'''))["sample"]
        self.assertEqual(parsed["properties"], ["account_id2", "limit"])

    def test_empty_generic_map(self):
        self.assertEqual(check.parse_registry(source(
            'obj(mapOf<String, Any>(), required = emptyList())'))["sample"]["properties"], [])

    def test_unsupported_shapes_fail_closed(self):
        for schema in (
            'obj(properties, listOf("id"))',
            'obj(mapOf(key to strProp()), emptyList())',
            'obj(mapOf("id" to strProp()) + extra, emptyList())',
            'obj(mapOf("id" to strProp(), "id" to strProp()), emptyList())',
            'obj(mapOf("id" to strProp()), requiredArgs)',
            'obj(mapOf("id" to strProp()), listOf("id") + extra)',
            'obj(mapOf("${key}" to strProp()), emptyList())',
            'obj(mapOf("id" to strProp()), listOf("id"), extra)',
            'obj(mapOf("id" to strProp()], emptyList())',
        ):
            with self.subTest(schema=schema), self.assertRaises(ValueError):
                check.parse_registry(source(schema))

    def test_required_drift_still_detected(self):
        registry = check.parse_registry(source('obj(mapOf("id" to strProp()), listOf("id"))'))
        listing = {"tools": {"included": [{"name": "sample", "capability": "query.sample.readonly",
                   "readOnlyHint": True, "title": "Sample", "properties": ["id"], "required": []}],
                   "excluded": []}}
        self.assertTrue(any("required" in err for err in check.check_tools(listing, registry)))


if __name__ == "__main__":
    unittest.main()
