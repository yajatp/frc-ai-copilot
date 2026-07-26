# Codex Integration for FRC AI Copilot

This module provides a seamless installation script to register the FRC AI Copilot MCP server with [Codex AI](https://openai.com/). 

The MCP server connects Codex directly to your FRC robot logs, CAN health analysis, and simulation tools.

## Setup Instructions

Ensure you have Node.js installed, then run the following commands:

```bash
# Navigate to this directory
cd codex-integration

# Install the dependencies
npm install

# Run the integration script
npm run install-codex-mcp
```

### What does the script do?
1. Validates your WPILib 2026 installation.
2. Compiles the Java MCP Server (`./gradlew :mcp-server:installDist`).
3. Automatically updates your Codex configuration (`~/.codex/config.toml`) to include `frc-ai-copilot` as an MCP server.
4. Correctly configures the `JAVA_HOME` environment variable for Codex so the JNI native libraries for WPILib load correctly.

## Verification
Open Codex and check if the `frc-ai-copilot` server is running and its tools (e.g. `log_info`, `can_health`) are available.
