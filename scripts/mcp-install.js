/**
 * Shared logic for the editor integration installers.
 *
 * Each editor stores its MCP configuration in a different place and format, but finding the JDK,
 * building the server, and locating the launcher are identical — and were copied into three
 * installers, all three of which had the same two Windows bugs (invoking `./gradlew`, which does not
 * exist on Windows, and pointing at the extensionless launcher instead of the `.bat`). One copy
 * means one place to fix.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const SERVER_NAME = 'frc-ai-copilot';
const isWindows = process.platform === 'win32';

/** Repository root — this file lives in <root>/scripts. */
function projectRoot() {
    return path.resolve(__dirname, '..');
}

/**
 * Locate the WPILib JDK. Honours an existing JAVA_HOME first, so anyone who has already pointed at a
 * JDK is not overridden. WPILib's Windows installer defaults to the shared Public profile rather
 * than the user's home directory, which is why that path is checked too.
 */
function findJdk() {
    const candidates = [];
    if (process.env.JAVA_HOME) {
        candidates.push(process.env.JAVA_HOME);
    }
    candidates.push(path.join(os.homedir(), 'wpilib', '2026', 'jdk'));
    if (isWindows) {
        candidates.push(path.join('C:', 'Users', 'Public', 'wpilib', '2026', 'jdk'));
    }

    for (const candidate of candidates) {
        if (fs.existsSync(candidate)) {
            return candidate;
        }
    }
    console.error('Could not find a JDK. Tried:');
    candidates.forEach((c) => console.error('  ' + c));
    console.error('Install WPILib 2026, or set JAVA_HOME to a JDK 17+.');
    process.exit(1);
}

/** The Gradle wrapper and the built launcher, both platform-correct. */
function gradlewPath() {
    return path.join(projectRoot(), isWindows ? 'gradlew.bat' : 'gradlew');
}

function launcherPath() {
    return path.join(
        projectRoot(), 'mcp-server', 'build', 'install', 'mcp-server', 'bin',
        isWindows ? 'mcp-server.bat' : 'mcp-server');
}

/** Build the MCP server and return the launcher path plus the JDK it was built with. */
function buildServer() {
    const javaHome = findJdk();
    console.log('Building the FRC AI Copilot MCP server...');
    try {
        // execFileSync, not execSync: the path may contain spaces (this project's own directory
        // does), which an unquoted shell command line would split into separate arguments.
        execFileSync(gradlewPath(), [':mcp-server:installDist'], {
            cwd: projectRoot(),
            stdio: 'inherit',
            env: { ...process.env, JAVA_HOME: javaHome },
        });
    } catch (e) {
        console.error('Build failed: ' + e.message);
        process.exit(1);
    }

    const executablePath = launcherPath();
    if (!fs.existsSync(executablePath)) {
        console.error('Build reported success but no launcher at ' + executablePath);
        process.exit(1);
    }
    console.log('Built: ' + executablePath);
    return { executablePath, javaHome };
}

/** Read a JSON config, backing it up rather than discarding it if it does not parse. */
function readJsonConfig(configPath) {
    if (!fs.existsSync(configPath)) {
        return {};
    }
    try {
        return JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    } catch (e) {
        const backup = configPath + '.bak';
        fs.copyFileSync(configPath, backup);
        console.error(`Existing config at ${configPath} is not valid JSON; backed it up to ${backup}`);
        return {};
    }
}

function writeConfig(configPath, contents) {
    fs.mkdirSync(path.dirname(configPath), { recursive: true });
    fs.writeFileSync(configPath, contents);
    console.log(`Registered '${SERVER_NAME}' in ${configPath}`);
}

/**
 * Register the server in an editor config that uses the common `mcpServers` JSON shape. Merges into
 * whatever is already there — clobbering a user's other MCP servers to install one is not acceptable.
 */
function registerJson(configPath, executablePath, javaHome) {
    const config = readJsonConfig(configPath);
    if (!config.mcpServers) {
        config.mcpServers = {};
    }
    config.mcpServers[SERVER_NAME] = {
        command: executablePath,
        args: [],
        env: { JAVA_HOME: javaHome },
    };
    writeConfig(configPath, JSON.stringify(config, null, 2) + '\n');
}

module.exports = {
    SERVER_NAME,
    isWindows,
    projectRoot,
    findJdk,
    gradlewPath,
    launcherPath,
    buildServer,
    readJsonConfig,
    writeConfig,
    registerJson,
};
