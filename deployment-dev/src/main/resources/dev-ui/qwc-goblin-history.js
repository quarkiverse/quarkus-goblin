import {LitElement, html, css} from 'lit';
import {JsonRpc} from 'jsonrpc';

export class QwcGoblinHistory extends LitElement {

    static styles = css`
        :host {
            display: block;
            padding: 16px;
            color: var(--lumo-contrast-color);
            background: var(--lumo-base-color);
        }
        .toolbar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 12px;
        }
        .toolbar h3 {
            margin: 0;
            color: var(--lumo-contrast-color);
        }
        .count {
            color: var(--lumo-contrast-60pct);
            font-weight: normal;
        }
        .clear-btn {
            padding: 6px 14px;
            border: 1px solid var(--lumo-error-color-50pct);
            border-radius: 4px;
            background: var(--lumo-base-color);
            color: var(--lumo-error-color);
            cursor: pointer;
            font-size: 13px;
        }
        .clear-btn:hover {
            background: var(--lumo-error-color-10pct);
        }
        .export-btn {
            padding: 6px 14px;
            border: 1px solid var(--lumo-primary-color-50pct);
            border-radius: 4px;
            background: var(--lumo-base-color);
            color: var(--lumo-primary-color);
            cursor: pointer;
            font-size: 13px;
            margin-right: 8px;
        }
        .export-btn:hover {
            background: var(--lumo-primary-color-10pct);
        }
        .report {
            margin-top: 16px;
            border: 1px solid var(--lumo-contrast-10pct);
            border-radius: 6px;
        }
        .report-toolbar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 8px 12px;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
        }
        .report-toolbar span {
            font-weight: 600;
            font-size: 13px;
            color: var(--lumo-contrast-60pct);
        }
        .copy-btn {
            padding: 4px 12px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            background: var(--lumo-base-color);
            color: var(--lumo-primary-color);
            cursor: pointer;
            font-size: 12px;
        }
        .copy-btn:hover {
            background: var(--lumo-primary-color-10pct);
        }
        pre {
            margin: 0;
            padding: 16px;
            overflow: auto;
            max-height: 400px;
            font-family: var(--lumo-font-family-mono);
            font-size: 12px;
            line-height: 1.5;
            color: var(--lumo-contrast-color);
            background: var(--lumo-shade-5pct);
            white-space: pre-wrap;
            word-break: break-word;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 13px;
        }
        th, td {
            text-align: left;
            padding: 8px 12px;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
        }
        th {
            font-weight: 600;
            color: var(--lumo-contrast-60pct);
            text-transform: uppercase;
            font-size: 11px;
            letter-spacing: 0.5px;
        }
        td {
            color: var(--lumo-contrast-color);
        }
        .empty-state {
            text-align: center;
            padding: 40px;
            color: var(--lumo-contrast-50pct);
        }
    `;

    static properties = {
        _history: {state: true},
        _markdown: {state: true},
    };

    constructor() {
        super();
        this._history = [];
        this._markdown = null;
        this.jsonRpc = new JsonRpc(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._loadHistory();
    }

    _loadHistory() {
        this.jsonRpc.getHistory().then(r => { this._history = r.result; });
    }

    _clearHistory() {
        this.jsonRpc.clearHistory().then(() => { this._history = []; });
    }

    _exportMarkdown() {
        this.jsonRpc.getMarkdownReport().then(r => { this._markdown = r.result.markdown; });
    }

    _closeReport() {
        this._markdown = null;
    }

    _copyMarkdown() {
        if (this._markdown) {
            navigator.clipboard.writeText(this._markdown);
        }
    }

    _formatTimestamp(ts) {
        return new Date(ts).toLocaleTimeString();
    }

    render() {
        return html`
            <div class="toolbar">
                <h3>Assault History <span class="count">(${this._history.length})</span></h3>
                <div>
                    <button class="export-btn" @click="${this._exportMarkdown}">Export Markdown</button>
                    <button class="clear-btn" @click="${this._clearHistory}">Clear History</button>
                </div>
            </div>

            ${this._history.length === 0
                ? html`<div class="empty-state">No assaults recorded yet.</div>`
                : html`
                    <table>
                        <thead>
                            <tr>
                                <th>Time</th>
                                <th>Method</th>
                                <th>Type</th>
                                <th>Duration</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${this._history.map(record => html`
                                <tr>
                                    <td>${this._formatTimestamp(record.timestamp)}</td>
                                    <td>${record.method}</td>
                                    <td>${record.type}</td>
                                    <td>${record.latencyMs ? record.latencyMs + ' ms' : '-'}</td>
                                </tr>
                            `)}
                        </tbody>
                    </table>
                `}

            ${this._markdown ? html`
                <div class="report">
                    <div class="report-toolbar">
                        <span>Markdown Report</span>
                        <div>
                            <button class="copy-btn" @click="${this._copyMarkdown}">Copy</button>
                            <button class="copy-btn" @click="${this._closeReport}">Close</button>
                        </div>
                    </div>
                    <pre>${this._markdown}</pre>
                </div>
            ` : ''}
        `;
    }
}

customElements.define('qwc-goblin-history', QwcGoblinHistory);
