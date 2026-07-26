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
                        this._view?.webview.postMessage({ type: 'receiveMessage', text: '❌ Please configure your Gemini API Key in Settings.' });
                        return;
                    }

                    try {
                        const reply = await this.bridge.sendMessage(data.value, apiKey);
                        this._view?.webview.postMessage({ type: 'receiveMessage', text: reply });
                    } catch (e: any) {
                        this._view?.webview.postMessage({ type: 'receiveMessage', text: `❌ Error: ${e.message}` });
                    }
                    break;
                case 'clearHistory':
                    this.bridge.clearHistory();
                    break;
            }
        });
    }

    private _getHtmlForWebview() {
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
                <div id="chat-container">
                    <div class="message assistant">Hello! I'm the FRC AI Copilot powered by Gemini. Ask me about your robot logs!</div>
                </div>
                <div id="input-container">
                    <input type="text" id="chat-input" placeholder="Ask about logs or CAN health..." />
                    <button id="send-button">Send</button>
                    <button id="clear-button">Clear</button>
                </div>
                <script>
                    const vscode = acquireVsCodeApi();
                    const input = document.getElementById('chat-input');
                    const sendBtn = document.getElementById('send-button');
                    const clearBtn = document.getElementById('clear-button');
                    const chatContainer = document.getElementById('chat-container');

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
                        if (e.key === 'Enter') {
                            sendBtn.click();
                        }
                    });

                    window.addEventListener('message', event => {
                        const message = event.data;
                        if (message.type === 'receiveMessage') {
                            addMessage(message.text, 'assistant');
                        }
                    });
                </script>
            </body>
            </html>`;
    }
}
