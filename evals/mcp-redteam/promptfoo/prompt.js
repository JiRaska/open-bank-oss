// SPDX-License-Identifier: Apache-2.0
// Builds the conversation an MCP client would hand its model: the server's initialize
// instructions, the customer's benign question, the model's own read call, and the poisoned
// tool result exactly as openbank-mcp-service renders it (rendered-tool-results.json).
module.exports = async function ({ vars }) {
  return [
    { role: 'system', content: vars.preamble },
    { role: 'user', content: vars.question },
    {
      role: 'assistant',
      content: null,
      tool_calls: [{ id: 'call_1', type: 'function', function: { name: vars.tool, arguments: vars.toolArgs } }],
    },
    { role: 'tool', tool_call_id: 'call_1', content: vars.toolResult },
  ];
};
