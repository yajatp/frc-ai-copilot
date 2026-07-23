import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/**
 * FRC AI Copilot extension.
 *
 * This extension does not itself speak the MCP protocol. Its job is to make it easy to
 * build and register the project's real MCP server -- a stdio JSON-RPC Java process
 * (org.mercsmavs.frccopilot.mcp.McpServer, launched via the Gradle `application` plugin's
 * installDist output) -- with an MCP-capable client such as Claude Code or GitHub Copilot.
 *
 * See docs/SETUP.md at the project root for the authoritative description of the build and
 * the `.mcp.json` shape this extension generates.
 */

const OUTPUT_CHANNEL_NAME = 'FRC AI Copilot';

export function activate(context: vscode.ExtensionContext): void {
  const output = vscode.window.createOutputChannel(OUTPUT_CHANNEL_NAME);
  context.subscriptions.push(output);

  context.subscriptions.push(
    vscode.commands.registerCommand('frcCopilot.buildServer', () => buildServer(context, output)),
    vscode.commands.registerCommand('frcCopilot.showConfig', () => showConfig(context)),
    vscode.commands.registerCommand('frcCopilot.locateJdk', () => locateJdkCommand())
  );
}

export function deactivate(): void {
  // Nothing to tear down: this extension never keeps a long-lived process of its own.
  // The MCP server's lifecycle is owned entirely by whatever MCP client (Claude Code /
  // Copilot) launches it via the .mcp.json entry produced by frcCopilot.showConfig.
}

/**
 * Locate the FRC AI Copilot project root (the directory containing `gradlew` and
 * `settings.gradle`). This extension is expected to live at `<project root>/vscode-extension`,
 * so the most reliable signal is simply "the parent of where this extension is installed from" --
 * that holds true whether or not the project root happens to be the open VS Code workspace
 * folder. We still fall back to scanning the open workspace folders for robustness.
 */
function resolveProjectRoot(context: vscode.ExtensionContext): string {
  const fromExtension = path.resolve(context.extensionPath, '..');
  if (looksLikeProjectRoot(fromExtension)) {
    return fromExtension;
  }

  const workspaceFolders = vscode.workspace.workspaceFolders ?? [];
  for (const folder of workspaceFolders) {
    const candidate = folder.uri.fsPath;
    if (looksLikeProjectRoot(candidate)) {
      return candidate;
    }
    const parent = path.resolve(candidate, '..');
    if (looksLikeProjectRoot(parent)) {
      return parent;
    }
  }

  // Nothing definitive found; best effort so downstream commands can still report a
  // sensible (if wrong) path instead of throwing.
  return fromExtension;
}

function looksLikeProjectRoot(candidate: string): boolean {
  return fs.existsSync(path.join(candidate, 'gradlew')) && fs.existsSync(path.join(candidate, 'settings.gradle'));
}

/** Absolute path to the `installDist` launcher script for the current platform. */
function mcpServerBinPath(projectRoot: string): string {
  const binName = process.platform === 'win32' ? 'mcp-server.bat' : 'mcp-server';
  return path.join(projectRoot, 'mcp-server', 'build', 'install', 'mcp-server', 'bin', binName);
}

/**
 * Scan `~/wpilib/<year>/jdk` for every installed WPILib year and return the newest one
 * (highest year) whose `jdk` directory actually exists. Returns undefined if WPILib isn't
 * installed at all, or no year has a `jdk` subdirectory.
 */
function locateWpilibJdk(): string | undefined {
  const wpilibDir = path.join(os.homedir(), 'wpilib');
  if (!fs.existsSync(wpilibDir)) {
    return undefined;
  }

  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(wpilibDir, { withFileTypes: true });
  } catch {
    return undefined;
  }

  const years = entries
    .filter((entry) => entry.isDirectory() && /^\d+$/.test(entry.name))
    .map((entry) => entry.name)
    .sort((a, b) => Number(b) - Number(a)); // newest (highest) year first

  for (const year of years) {
    const jdkPath = path.join(wpilibDir, year, 'jdk');
    if (fs.existsSync(jdkPath)) {
      return jdkPath;
    }
  }

  return undefined;
}

function locateJdkCommand(): string | undefined {
  const jdk = locateWpilibJdk();
  if (jdk) {
    vscode.window.showInformationMessage(`FRC AI Copilot: found WPILib JDK at ${jdk}`);
  } else {
    vscode.window.showWarningMessage(
      'FRC AI Copilot: no WPILib JDK found under ~/wpilib/<year>/jdk. Install WPILib (2026) first.'
    );
  }
  return jdk;
}

async function buildServer(context: vscode.ExtensionContext, output: vscode.OutputChannel): Promise<void> {
  const projectRoot = resolveProjectRoot(context);
  const gradlewName = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
  const gradlewPath = path.join(projectRoot, gradlewName);

  if (!fs.existsSync(gradlewPath)) {
    vscode.window.showErrorMessage(
      `FRC AI Copilot: could not find ${gradlewName} at "${gradlewPath}". Is this the FRC AI Copilot project root?`
    );
    return;
  }

  const jdk = locateWpilibJdk();
  if (!jdk) {
    const choice = await vscode.window.showWarningMessage(
      'FRC AI Copilot: no WPILib JDK found under ~/wpilib/<year>/jdk. The Gradle build needs JAVA_HOME ' +
        'pointed at the WPILib JDK, or it may fail / pick the wrong Java. Continue anyway?',
      'Continue',
      'Cancel'
    );
    if (choice !== 'Continue') {
      return;
    }
  }

  const env: NodeJS.ProcessEnv = { ...process.env };
  if (jdk) {
    env.JAVA_HOME = jdk;
  }
  // Keep Gradle's cache/wrapper/daemon state inside the project, per docs/SETUP.md.
  env.GRADLE_USER_HOME = path.join(projectRoot, '.gradle-home');

  output.show(true);
  output.appendLine(`> Building mcp-server in ${projectRoot}`);
  output.appendLine(`> JAVA_HOME=${env.JAVA_HOME ?? '(not set, using PATH java)'}`);
  output.appendLine(`> GRADLE_USER_HOME=${env.GRADLE_USER_HOME}`);
  output.appendLine(`> ${gradlewPath} :mcp-server:installDist`);
  output.appendLine('');

  await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'FRC AI Copilot: building mcp-server…',
      cancellable: false,
    },
    () =>
      new Promise<void>((resolve) => {
        const child = cp.spawn(gradlewPath, [':mcp-server:installDist'], {
          cwd: projectRoot,
          env,
        });

        child.stdout.on('data', (chunk: Buffer) => output.append(chunk.toString()));
        child.stderr.on('data', (chunk: Buffer) => output.append(chunk.toString()));

        child.on('error', (err: Error) => {
          output.appendLine(`\n[frcCopilot] failed to launch ${gradlewName}: ${err.message}`);
          vscode.window.showErrorMessage(`FRC AI Copilot: failed to launch ${gradlewName}: ${err.message}`);
          resolve();
        });

        child.on('close', (code: number | null) => {
          if (code === 0) {
            vscode.window.showInformationMessage('FRC AI Copilot: mcp-server built successfully.');
          } else {
            vscode.window.showErrorMessage(
              `FRC AI Copilot: build failed (exit code ${code ?? 'unknown'}). See the "${OUTPUT_CHANNEL_NAME}" output channel for details.`
            );
          }
          resolve();
        });
      })
  );
}

async function showConfig(context: vscode.ExtensionContext): Promise<void> {
  const projectRoot = resolveProjectRoot(context);
  const serverBin = mcpServerBinPath(projectRoot);
  const jdk = locateWpilibJdk() ?? path.join(os.homedir(), 'wpilib', '2026', 'jdk');

  if (!fs.existsSync(serverBin)) {
    vscode.window.showWarningMessage(
      `FRC AI Copilot: mcp-server launcher not found at "${serverBin}". Run ` +
        '"FRC AI Copilot: Build MCP Server" first, then re-run this command for an accurate path.'
    );
  }

  const config = {
    mcpServers: {
      'frc-copilot': {
        command: serverBin,
        env: {
          JAVA_HOME: jdk,
        },
      },
    },
  };

  const snippet = JSON.stringify(config, null, 2);

  const doc = await vscode.workspace.openTextDocument({ content: snippet, language: 'json' });
  await vscode.window.showTextDocument(doc, { preview: false });

  await vscode.env.clipboard.writeText(snippet);
  vscode.window.showInformationMessage(
    'FRC AI Copilot: .mcp.json snippet opened in editor and copied to clipboard. Save it as .mcp.json ' +
      'at the project root (or merge it into an existing one).'
  );
}
