import * as cp from 'child_process';
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { GoogleGenerativeAI, FunctionDeclaration, ChatSession } from '@google/generative-ai';

export class McpGeminiBridge {
    private mcpClient: Client | null = null;
    private mcpTransport: StdioClientTransport | null = null;
    private mcpProcess: cp.ChildProcess | null = null;
    private chatSession: ChatSession | null = null;
    private toolsCache: any[] = [];
    private geminiTools: any[] = [];

    constructor(private projectRoot: string) {}

    private findWpilibJdk(): string {
        const homeDir = os.homedir();
        const wpiJdkPath = path.join(homeDir, 'wpilib', '2026', 'jdk');
        if (fs.existsSync(wpiJdkPath)) return wpiJdkPath;
        throw new Error('WPILib 2026 JDK not found. Please install WPILib.');
    }

    private getExecutablePath(): string {
        const binName = process.platform === 'win32' ? 'mcp-server.bat' : 'mcp-server';
        return path.join(this.projectRoot, 'mcp-server', 'build', 'install', 'mcp-server', 'bin', binName);
    }

    private async connectMcp(): Promise<void> {
        if (this.mcpClient) return;

        const execPath = this.getExecutablePath();
        if (!fs.existsSync(execPath)) {
            throw new Error(`MCP Server not found at ${execPath}. Run "FRC AI Copilot: Build MCP Server" command first.`);
        }

        const javaHome = this.findWpilibJdk();

        this.mcpTransport = new StdioClientTransport({
            command: execPath,
            args: [],
            env: { ...process.env, JAVA_HOME: javaHome }
        });

        this.mcpClient = new Client({ name: 'vscode-gemini-client', version: '1.0.0' }, { capabilities: {} });
        await this.mcpClient.connect(this.mcpTransport);

        const toolsList = await this.mcpClient.listTools();
        this.toolsCache = toolsList.tools;
        
        // Convert MCP tools to Gemini function declarations
        const functionDeclarations: FunctionDeclaration[] = this.toolsCache.map(tool => ({
            name: tool.name,
            description: tool.description,
            parameters: tool.inputSchema as any
        }));

        if (functionDeclarations.length > 0) {
            this.geminiTools = [{ functionDeclarations }];
        }
    }

    public async sendMessage(prompt: string, apiKey: string): Promise<string> {
        await this.connectMcp();

        const genAI = new GoogleGenerativeAI(apiKey);
        const model = genAI.getGenerativeModel({
            model: "gemini-2.5-flash",
            tools: this.geminiTools.length > 0 ? this.geminiTools : undefined
        });

        if (!this.chatSession) {
            this.chatSession = model.startChat();
        }

        let result = await this.chatSession.sendMessage(prompt);
        let calls = result.response.functionCalls();

        // Handle tool calls recursively until model returns a text response
        while (calls && calls.length > 0) {
            const call = calls[0]; // Process one at a time for simplicity
            console.log(`[Gemini] Calling tool ${call.name}`);
            
            try {
                const toolResponse = await this.mcpClient!.callTool({
                    name: call.name,
                    arguments: call.args as any
                });
                
                const functionResponse = {
                    name: call.name,
                    response: toolResponse
                };

                result = await this.chatSession.sendMessage([{
                    functionResponse
                }]);
                calls = result.response.functionCalls();
            } catch (e: any) {
                console.error(`Tool execution failed: ${e.message}`);
                const errorResponse = {
                    name: call.name,
                    response: { error: e.message }
                };
                result = await this.chatSession.sendMessage([{
                    functionResponse: errorResponse
                }]);
                calls = result.response.functionCalls();
            }
        }

        return result.response.text();
    }

    public clearHistory(): void {
        this.chatSession = null;
    }
}
