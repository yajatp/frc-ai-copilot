# Antigravity Integration for FRC AI Copilot

This module registers the FRC AI Copilot MCP server with the **Antigravity IDE**.

## Setup Instructions

Ensure you have Node.js installed, then run the following commands:

```bash
# Navigate to this directory
cd antigravity-integration

# Run the integration script
npm run install-antigravity-mcp
```

### What does the script do?
1. Validates your WPILib 2026 installation.
2. Compiles the Java MCP Server (`./gradlew :mcp-server:installDist`).
3. Automatically updates your global Antigravity configuration (`~/.gemini/antigravity-ide/mcp_config.json`) to include `frc-ai-copilot` as an MCP server.
4. Correctly configures the `JAVA_HOME` environment variable.
