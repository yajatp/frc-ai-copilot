// Registers the FRC AI Copilot MCP server with Antigravity.
// Shared build/JDK/launcher logic lives in ../scripts/mcp-install.js.

const os = require('os');
const path = require('path');
const { buildServer, registerJson } = require('../scripts/mcp-install');

const { executablePath, javaHome } = buildServer();
const configPath = path.join(os.homedir(), '.gemini', 'antigravity-ide', 'mcp_config.json');
registerJson(configPath, executablePath, javaHome);

console.log('\nDone. Restart Antigravity to pick up the new server.');
