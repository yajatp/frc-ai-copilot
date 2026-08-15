// Registers the FRC AI Copilot MCP server with Codex.
// Shared build/JDK/launcher logic lives in ../scripts/mcp-install.js. Codex differs from the other
// editors in storing its configuration as TOML under `mcp_servers`, so only the write is local.

const fs = require('fs');
const os = require('os');
const path = require('path');
const toml = require('@iarna/toml');
const { SERVER_NAME, buildServer, writeConfig } = require('../scripts/mcp-install');

const { executablePath, javaHome } = buildServer();
const configPath = path.join(os.homedir(), '.codex', 'config.toml');

let config = {};
if (fs.existsSync(configPath)) {
    try {
        config = toml.parse(fs.readFileSync(configPath, 'utf-8'));
    } catch (e) {
        // Back the file up rather than discarding it — it holds the user's other settings.
        const backup = configPath + '.bak';
        fs.copyFileSync(configPath, backup);
        console.error(`Existing config.toml is not valid TOML; backed it up to ${backup}`);
        config = {};
    }
}

if (!config.mcp_servers) {
    config.mcp_servers = {};
}
config.mcp_servers[SERVER_NAME] = {
    command: executablePath,
    args: [],
    env: { JAVA_HOME: javaHome },
};

writeConfig(configPath, toml.stringify(config));
console.log('\nDone. Restart Codex to pick up the new server.');
