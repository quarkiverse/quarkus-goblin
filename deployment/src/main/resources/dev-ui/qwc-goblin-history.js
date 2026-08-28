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
    };

    constructor() {
        super();
        this._history = [];
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

    _formatTimestamp(ts) {
        return new Date(ts).toLocaleTimeString();
    }

    render() {
        return html`
            <div class="toolbar">
                <h3>Assault History <span class="count">(${this._history.length})</span></h3>
                <button class="clear-btn" @click="${this._clearHistory}">Clear History</button>
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
                            </tr>
                        </thead>
                        <tbody>
                            ${this._history.map(record => html`
                                <tr>
                                    <td>${this._formatTimestamp(record.timestamp)}</td>
                                    <td>${record.method}</td>
                                    <td>${record.type}</td>
                                </tr>
                            `)}
                        </tbody>
                    </table>
                `}
        `;
    }
}

customElements.define('qwc-goblin-history', QwcGoblinHistory);
