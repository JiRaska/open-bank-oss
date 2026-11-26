#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression proof for the literal Kotlin registry boundary reader."""
import unittest
import check


def source(schema):
    return '''private val tools: Map<String, Tool> = listOf(
        Tool(ToolDefinition(name = "sample", inputSchema = ''' + schema + '''),
            SAMPLE_CAPABILITY, "query.sample.readonly"),
    ).associateBy { it.definition.name }
    private fun proposeSchema(): Map<String, Any> = schema(emptyMap(), emptyList())
    fun toolAvailable(tool: String): Boolean {
        return when (tool) {
            "sample" -> true
            else -> false
        }
    }
    '''


def parse(text):
    return check.parse_customer_registry(text, "")


class RegistryTests(unittest.TestCase):
    def test_nested_keywords_are_not_properties(self):
        parsed = parse(source('''schema(mapOf(
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 50),
            "type" to strProp(), "description" to strProp(),
            "minimum" to strProp(), "maximum" to strProp(),
        ), listOf("type"))'''))["sample"]
        self.assertEqual(parsed["properties"], ["description", "limit", "maximum", "minimum", "type"])
        self.assertEqual(parsed["required"], ["type"])
        self.assertTrue(parsed["connection_bound"])
        listing = {"tools": {"included": [{"name": "sample", "capability": "query.sample.readonly",
                   "readOnlyHint": True, "title": "Sample",
                   "properties": ["description", "limit", "minimum", "type"], "required": ["type"]}],
                   "excluded": []}}
        self.assertTrue(any("properties" in err for err in check.check_tools(listing, {"sample": parsed})))

    def test_quotes_comments_and_nested_values(self):
        parsed = parse(source(r'''schema(mapOf(
            // "fake" to strProp()), ToolDefinition(
            "account_id2" to strProp("a ) \\\" quoted ( description"),
            /* nested /* comment ) */ "fake" to x */
            "limit" to mapOf("minimum" to 1, "items" to listOf("x,y")),
        ), listOf("account_id2"))'''))["sample"]
        self.assertEqual(parsed["properties"], ["account_id2", "limit"])

    def test_empty_generic_map(self):
        self.assertEqual(parse(source(
            'schema(mapOf<String, Any>(), emptyList())'))["sample"]["properties"], [])

    def test_unsupported_shapes_fail_closed(self):
        for schema in (
            'schema(properties, listOf("id"))',
            'schema(mapOf(key to strProp()), emptyList())',
            'schema(mapOf("id" to strProp()) + extra, emptyList())',
            'schema(mapOf("id" to strProp(), "id" to strProp()), emptyList())',
            'schema(mapOf("id" to strProp()), requiredArgs)',
            'schema(mapOf("id" to strProp()), listOf("id") + extra)',
            'schema(mapOf("${key}" to strProp()), emptyList())',
            'schema(mapOf("id" to strProp()), listOf("id"), extra)',
            'schema(mapOf("id" to strProp()], emptyList())',
        ):
            with self.subTest(schema=schema), self.assertRaises(ValueError):
                parse(source(schema))

    def test_required_drift_still_detected(self):
        registry = parse(source('schema(mapOf("id" to strProp()), listOf("id"))'))
        listing = {"tools": {"included": [{"name": "sample", "capability": "query.sample.readonly",
                   "readOnlyHint": True, "title": "Sample", "properties": ["id"], "required": []}],
                   "excluded": []}}
        self.assertTrue(any("required" in err for err in check.check_tools(listing, registry)))


if __name__ == "__main__":
    unittest.main()
