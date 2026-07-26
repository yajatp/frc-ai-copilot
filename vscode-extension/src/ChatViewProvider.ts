import * as vscode from 'vscode';
import { McpGeminiBridge } from './McpGeminiBridge';

export class ChatViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'frcCopilot.chatView';
    private _view?: vscode.WebviewView;
    private bridge: McpGeminiBridge;

    constructor(
        private readonly _extensionUri: vscode.Uri,
        private readonly _projectRoot: string
    ) {
        this.bridge = new McpGeminiBridge(_projectRoot);
    }

    public resolveWebviewView(
        webviewView: vscode.WebviewView,
        context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ) {
        this._view = webviewView;

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this._extensionUri]
        };

        webviewView.webview.html = this._getHtmlForWebview();

        webviewView.webview.onDidReceiveMessage(async (data) => {
            switch (data.type) {
                case 'sendMessage':
                    const apiKey = vscode.workspace.getConfiguration('frcCopilot').get<string>('geminiApiKey');
                    if (!apiKey) {
                        this._view?.webview.postMessage({ type: 'receiveMessage', text: '❌ Please configure your Gemini API Key.' });
                        return;
                    }

                    try {
                        const reply = await this.bridge.sendMessage(data.value, apiKey);
                        this._view?.webview.postMessage({ type: 'receiveMessage', text: reply });
                    } catch (e: any) {
                        this._view?.webview.postMessage({ type: 'receiveMessage', text: `❌ Error: ${e.message}` });
                    }
                    break;
                case 'saveApiKey':
                    await vscode.workspace.getConfiguration('frcCopilot').update('geminiApiKey', data.value, vscode.ConfigurationTarget.Global);
                    this._view?.webview.postMessage({ type: 'apiKeySaved' });
                    break;
                case 'clearHistory':
                    this.bridge.clearHistory();
                    break;
            }
        });
    }

    private _getHtmlForWebview() {
        const apiKey = vscode.workspace.getConfiguration('frcCopilot').get<string>('geminiApiKey');
        const hasKey = !!apiKey && apiKey.trim().length > 0;

        return `<!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>FRC Copilot</title>
                <style>
                    body {
                        font-family: var(--vscode-font-family);
                        color: var(--vscode-editor-foreground);
                        background-color: var(--vscode-editor-background);
                        padding: 10px;
                        display: flex;
                        flex-direction: column;
                        height: 100vh;
                        box-sizing: border-box;
                        margin: 0;
                    }
                    .hidden { display: none !important; }
                    
                    /* Landing Page */
                    #landing-container {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100%;
                        text-align: center;
                        padding: 20px;
                    }
                    #landing-container h2 {
                        margin-bottom: 10px;
                    }
                    #landing-container p {
                        margin-bottom: 20px;
                        opacity: 0.8;
                    }
                    .api-input-group {
                        display: flex;
                        flex-direction: column;
                        width: 100%;
                        max-width: 300px;
                        gap: 10px;
                    }
                    
                    /* Chat Page */
                    #chat-wrapper {
                        display: flex;
                        flex-direction: column;
                        height: 100%;
                    }
                    #chat-container {
                        flex: 1;
                        overflow-y: auto;
                        padding-bottom: 10px;
                        display: flex;
                        flex-direction: column;
                        gap: 10px;
                    }
                    .message {
                        padding: 8px 12px;
                        border-radius: 6px;
                        max-width: 90%;
                        line-height: 1.4;
                    }
                    .user {
                        align-self: flex-end;
                        background-color: var(--vscode-button-background);
                        color: var(--vscode-button-foreground);
                    }
                    .assistant {
                        align-self: flex-start;
                        background-color: var(--vscode-editorWidget-background);
                        border: 1px solid var(--vscode-widget-border);
                    }
                    #input-container {
                        display: flex;
                        gap: 5px;
                        padding-top: 10px;
                        border-top: 1px solid var(--vscode-widget-border);
                        background-color: var(--vscode-editor-background);
                    }
                    input {
                        flex: 1;
                        padding: 8px;
                        border: 1px solid var(--vscode-input-border);
                        background-color: var(--vscode-input-background);
                        color: var(--vscode-input-foreground);
                        border-radius: 4px;
                    }
                    button {
                        padding: 8px 12px;
                        border: none;
                        background-color: var(--vscode-button-background);
                        color: var(--vscode-button-foreground);
                        border-radius: 4px;
                        cursor: pointer;
                    }
                    button:hover {
                        background-color: var(--vscode-button-hoverBackground);
                    }
                </style>
            </head>
            <body>
                <!-- LANDING PAGE -->
                <div id="landing-container" class="${hasKey ? 'hidden' : ''}">
                    <h2>🤖 FRC AI Copilot</h2>
                    <p>Welcome! To get started, please enter your Google AI Studio API Key (Gemini).</p>
                    <div class="api-input-group">
                        <input type="password" id="api-key-input" placeholder="Enter API Key..." />
                        <button id="save-key-button">Save API Key</button>
                    </div>
                </div>

                <!-- CHAT WRAPPER -->
                <div id="chat-wrapper" class="${hasKey ? '' : 'hidden'}">
                    <div id="chat-container">
                        <div class="message assistant">Hello! I'm the FRC AI Copilot powered by Gemini. Ask me about your robot logs!</div>
                    </div>
                    <div id="input-container">
                        <input type="text" id="chat-input" placeholder="Ask about logs or CAN health..." />
                        <button id="send-button">Send</button>
                        <button id="clear-button">Clear</button>
                    </div>
                </div>

                <script>
                    const vscode = acquireVsCodeApi();
                    
                    // UI Elements
                    const landingContainer = document.getElementById('landing-container');
                    const chatWrapper = document.getElementById('chat-wrapper');
                    
                    const apiKeyInput = document.getElementById('api-key-input');
                    const saveKeyBtn = document.getElementById('save-key-button');

                    const input = document.getElementById('chat-input');
                    const sendBtn = document.getElementById('send-button');
                    const clearBtn = document.getElementById('clear-button');
                    const chatContainer = document.getElementById('chat-container');

                    // Landing Page Logic
                    saveKeyBtn.addEventListener('click', () => {
                        const key = apiKeyInput.value.trim();
                        if (key) {
                            saveKeyBtn.textContent = 'Saving...';
                            vscode.postMessage({ type: 'saveApiKey', value: key });
                        }
                    });

                    apiKeyInput.addEventListener('keypress', (e) => {
                        if (e.key === 'Enter') saveKeyBtn.click();
                    });

                    // Chat Logic
                    function addMessage(text, className) {
                        const div = document.createElement('div');
                        div.className = 'message ' + className;
                        div.textContent = text;
                        chatContainer.appendChild(div);
                        chatContainer.scrollTop = chatContainer.scrollHeight;
                    }

                    sendBtn.addEventListener('click', () => {
                        const text = input.value.trim();
                        if (text) {
                            addMessage(text, 'user');
                            vscode.postMessage({ type: 'sendMessage', value: text });
                            input.value = '';
                        }
                    });

                    clearBtn.addEventListener('click', () => {
                        chatContainer.innerHTML = '<div class="message assistant">Chat cleared.</div>';
                        vscode.postMessage({ type: 'clearHistory' });
                    });

                    input.addEventListener('keypress', (e) => {
                        if (e.key === 'Enter') sendBtn.click();
                    });

                    // Message Listener
                    window.addEventListener('message', event => {
                        const message = event.data;
                        if (message.type === 'receiveMessage') {
                            addMessage(message.text, 'assistant');
                        } else if (message.type === 'apiKeySaved') {
                            landingContainer.classList.add('hidden');
                            chatWrapper.classList.remove('hidden');
                        }
                    });
                </script>
            </body>
            </html>`;
    }
}
