const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');
const toml = require('@iarna/toml');

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

function updateCodexConfig(executablePath, javaHome) {
    console.log('⚙️ Updating Codex config.toml...');
    
    const codexConfigDir = path.join(os.homedir(), '.codex');
    const configPath = path.join(codexConfigDir, 'config.toml');
    
    if (!fs.existsSync(codexConfigDir)) {
        fs.mkdirSync(codexConfigDir, { recursive: true });
    }

    let config = {};
    if (fs.existsSync(configPath)) {
        try {
            const fileContent = fs.readFileSync(configPath, 'utf-8');
            config = toml.parse(fileContent);
        } catch (e) {
            console.error('❌ Failed to parse existing config.toml. Creating a fresh configuration backup...');
            fs.copyFileSync(configPath, configPath + '.bak');
            config = {};
        }
    }

    if (!config.mcp_servers) {
        config.mcp_servers = {};
    }

    config.mcp_servers[SERVER_NAME] = {
        command: executablePath,
        args: [],
        env: {
            JAVA_HOME: javaHome
        }
    };

    try {
        fs.writeFileSync(configPath, toml.stringify(config));
        console.log(`✅ Successfully registered '${SERVER_NAME}' in ${configPath}`);
    } catch (e) {
        console.error('❌ Failed to write config.toml: ', e.message);
        process.exit(1);
    }
}

function main() {
    console.log('🚀 Starting Codex Integration Setup');
    const { executablePath, javaHome } = buildServer();
    updateCodexConfig(executablePath, javaHome);
    console.log('\\n🎉 Setup complete! You can now use the FRC AI Copilot inside Codex.');
    console.log('To verify, open your Codex interface and check if the tools from FRC AI Copilot are available.');
}

main();
