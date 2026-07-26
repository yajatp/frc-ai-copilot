# Cursor Integration for FRC AI Copilot

This module provides a seamless installation script to register the FRC AI Copilot MCP server with the **Cursor IDE**. 

By installing this, Cursor's AI Agent gains direct access to your FRC robot logs, CAN health analysis, and PathPlanner `.path` editing capabilities across all your FRC workspaces.

## Setup Instructions

Ensure you have Node.js installed, then run the following commands:

```bash
# Navigate to this directory
cd cursor-integration

# Run the integration script
npm run install-cursor-mcp
```

### What does the script do?
1. Validates your WPILib 2026 installation.
2. Compiles the Java MCP Server (`./gradlew :mcp-server:installDist`).
3. Automatically updates your global Cursor configuration (`~/.cursor/mcp.json`) to include `frc-ai-copilot` as an MCP server.
4. Correctly configures the `JAVA_HOME` environment variable for Cursor so the JNI native libraries for WPILib load natively.

## Verification
Open Cursor, navigate to **Settings > Features > MCP**, and check if the `frc-ai-copilot` server is running with a green dot and its tools (e.g., `log_info`, `can_health`) are listed.
