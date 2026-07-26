const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

const SERVER_NAME = 'frc-ai-copilot';

function findWpilibJdk() {
    const homeDir = os.homedir();
    // Default 2026 WPILib JDK path
    const wpiJdkPath = path.join(homeDir, 'wpilib', '2026', 'jdk');
    if (fs.existsSync(wpiJdkPath)) {
        return wpiJdkPath;
    }
    console.error('❌ Could not find WPILib 2026 JDK at: ' + wpiJdkPath);
    console.error('Please ensure WPILib 2026 is installed.');
    process.exit(1);
}

function buildServer() {
    console.log('🔨 Building FRC AI Copilot MCP server...');
    const rootDir = path.resolve(__dirname, '..');
    try {
        const wpiJdkPath = findWpilibJdk();
        const javaHome = wpiJdkPath;
        
        execSync('./gradlew :mcp-server:installDist', { 
            cwd: rootDir, 
            stdio: 'inherit',
            env: { ...process.env, JAVA_HOME: javaHome }
        });
        
        const executablePath = path.join(rootDir, 'mcp-server', 'build', 'install', 'mcp-server', 'bin', 'mcp-server');
        if (!fs.existsSync(executablePath)) {
            throw new Error(`Executable not found at ${executablePath}`);
        }
        console.log('✅ Server built successfully!');
        return { executablePath, javaHome };
    } catch (e) {
        console.error('❌ Failed to build server: ', e.message);
        process.exit(1);
    }
}

function updateCursorConfig(executablePath, javaHome) {
    console.log('⚙️ Updating Cursor mcp.json...');
    
    const cursorConfigDir = path.join(os.homedir(), '.cursor');
    const configPath = path.join(cursorConfigDir, 'mcp.json');
    
    if (!fs.existsSync(cursorConfigDir)) {
        fs.mkdirSync(cursorConfigDir, { recursive: true });
    }

    let config = { mcpServers: {} };
    if (fs.existsSync(configPath)) {
        try {
            const fileContent = fs.readFileSync(configPath, 'utf-8');
            config = JSON.parse(fileContent);
            if (!config.mcpServers) {
                config.mcpServers = {};
            }
        } catch (e) {
            console.error('❌ Failed to parse existing mcp.json. Creating a fresh configuration backup...');
            fs.copyFileSync(configPath, configPath + '.bak');
            config = { mcpServers: {} };
        }
    }

    config.mcpServers[SERVER_NAME] = {
        command: executablePath,
        args: [],
        env: {
            JAVA_HOME: javaHome
        }
    };

    try {
        fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
        console.log(`✅ Successfully registered '${SERVER_NAME}' in ${configPath}`);
    } catch (e) {
        console.error('❌ Failed to write mcp.json: ', e.message);
        process.exit(1);
    }
}

function main() {
    console.log('🚀 Starting Cursor Integration Setup');
    const { executablePath, javaHome } = buildServer();
    updateCursorConfig(executablePath, javaHome);
    console.log('\\n🎉 Setup complete! You can now use the FRC AI Copilot inside Cursor IDE.');
    console.log('To verify, open Cursor Settings > Features > MCP (or Developer Console) and check if the tools are available.');
}

main();
