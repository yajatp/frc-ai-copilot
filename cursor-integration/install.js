// Registers the FRC AI Copilot MCP server with Cursor.
// Shared build/JDK/launcher logic lives in ../scripts/mcp-install.js.

const os = require('os');
const path = require('path');
const { buildServer, registerJson } = require('../scripts/mcp-install');

const { executablePath, javaHome } = buildServer();
const configPath = path.join(os.homedir(), '.cursor', 'mcp.json');
registerJson(configPath, executablePath, javaHome);

console.log('\nDone. In Cursor, check Settings > MCP to confirm the tools are listed.');
