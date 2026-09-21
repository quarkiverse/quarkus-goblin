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
        .toolbar-buttons {
            display: flex;
            gap: 8px;
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
        .clear-btn.confirm {
            background: var(--lumo-error-color);
            color: var(--lumo-primary-contrast-color);
            border-color: var(--lumo-error-color);
        }
        .clear-btn:hover {
            background: var(--lumo-error-color-10pct);
        }
        .clear-btn.confirm:hover {
            background: var(--lumo-error-color);
        }
        .export-btn {
            padding: 6px 14px;
            border: 1px solid var(--lumo-primary-color-50pct);
            border-radius: 4px;
            background: var(--lumo-base-color);
            color: var(--lumo-primary-color);
            cursor: pointer;
            font-size: 13px;
        }
        .export-btn:hover {
            background: var(--lumo-primary-color-10pct);
        }
        .status-hint {
            font-size: 11px;
            color: var(--lumo-contrast-50pct);
            font-style: italic;
        }
        .summary {
            display: flex;
            flex-wrap: wrap;
            gap: 8px 16px;
            padding: 10px 14px;
            margin-bottom: 12px;
            border: 1px solid var(--lumo-contrast-10pct);
            border-radius: 6px;
            background: var(--lumo-contrast-5pct);
            font-size: 13px;
        }
        .sum-item {
            white-space: nowrap;
        }
        .sum-item b {
            color: var(--lumo-primary-color);
        }
        .filters {
            display: flex;
            flex-wrap: wrap;
            gap: 10px;
            margin-bottom: 12px;
            align-items: center;
        }
        .filters label {
            font-size: 12px;
            color: var(--lumo-contrast-60pct);
        }
        .filters select,
        .filters input {
            padding: 6px 10px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            font-size: 13px;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .filters input {
            flex: 1;
            min-width: 180px;
        }
        .filter-result {
            margin-left: auto;
            font-size: 12px;
            color: var(--lumo-contrast-60pct);
            white-space: nowrap;
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
            margin-left: 8px;
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
            table-layout: fixed;
        }
        th, td {
            text-align: left;
            padding: 8px 12px;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
            overflow: hidden;
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
        th.time-col { width: 15%; }
        th.method-col { width: 22%; }
        th.type-col { width: 14%; }
        th.duration-col { width: 10%; }
        th.config-col { width: 39%; }
        .type-badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 10px;
            font-size: 11px;
            font-weight: 600;
            white-space: nowrap;
        }
        .type-badge.latency { background: var(--lumo-primary-color-10pct); color: var(--lumo-primary-color); }
        .type-badge.exception { background: var(--lumo-error-color-10pct); color: var(--lumo-error-color); }
        .type-badge.http-status { background: var(--lumo-warning-color-10pct); color: var(--lumo-warning-color); }
        .type-badge.dependency-degradation { background: var(--lumo-success-color-10pct); color: var(--lumo-success-color); }
        .type-badge.response-body-truncate { background: rgba(8, 145, 178, 0.12); color: #0891b2; }
        .type-badge.response-body-inflate { background: rgba(234, 88, 12, 0.12); color: #ea580c; }
        .type-badge.response-header { background: var(--lumo-contrast-10pct); color: var(--lumo-contrast-color); }
        .source-badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 10px;
            font-size: 10px;
            font-weight: 600;
            margin-right: 6px;
            white-space: nowrap;
            text-transform: uppercase;
            letter-spacing: 0.4px;
        }
        .source-badge.rest-client { background: rgba(13, 148, 136, 0.12); color: #0f766e; }
        .source-badge.webclient { background: rgba(124, 58, 237, 0.12); color: #6d28d9; }
        .method-cell {
            font-family: var(--lumo-font-family-mono);
            font-size: 12px;
            word-break: break-all;
        }
        .cfg-cell {
            cursor: pointer;
            font-family: var(--lumo-font-family-mono);
            font-size: 11px;
            line-height: 1.4;
        }
        .cfg-cell.collapsed {
            white-space: nowrap;
            text-overflow: ellipsis;
        }
        .cfg-cell:hover {
            color: var(--lumo-primary-color);
        }
        .cfg-cell .expand-hint {
            color: var(--lumo-contrast-50pct);
            font-style: italic;
            font-family: var(--lumo-font-family-sans);
        }
        .empty-state {
            text-align: center;
            padding: 40px;
            color: var(--lumo-contrast-50pct);
        }
    `;

    static TYPE_LABELS = {
        latency: 'Latency',
        exception: 'Exception',
        'http-status': 'HTTP Status',
        'dependency-degradation': 'Dependency',
        'response-body-truncate': 'Truncate',
        'response-body-inflate': 'Inflate',
    };

    static properties = {
        _history: {state: true},
        _markdown: {state: true},
        _typeFilter: {state: true},
        _methodFilter: {state: true},
        _rangeFilter: {state: true},
        _expanded: {state: true},
        _confirmClear: {state: true},
    };

    constructor() {
        super();
        this._history = [];
        this._markdown = null;
        this._typeFilter = 'all';
        this._methodFilter = '';
        this._rangeFilter = 'all';
        this._expanded = new Set();
        this._confirmClear = false;
        this.jsonRpc = new JsonRpc(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._loadHistory();
        this._refreshTimer = setInterval(() => this._refreshTick(), 2000);
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        clearInterval(this._refreshTimer);
        clearTimeout(this._clearTimer);
    }

    _refreshTick() {
        const visible = (typeof this.checkVisibility === 'function')
            ? this.checkVisibility()
            : (this.offsetParent !== null);
        if (visible) {
            this._loadHistory();
        }
    }

    _loadHistory() {
        this.jsonRpc.getHistory().then(r => { this._history = r.result; });
    }

    _clearHistory() {
        if (!this._confirmClear) {
            this._confirmClear = true;
            this._clearTimer = setTimeout(() => { this._confirmClear = false; }, 3000);
            return;
        }
        this.jsonRpc.clearHistory().then(() => {
            this._history = [];
            this._confirmClear = false;
            clearTimeout(this._clearTimer);
        });
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

    _downloadMarkdown() {
        if (!this._markdown) {
            return;
        }
        const blob = new Blob([this._markdown], {type: 'text/markdown'});
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = 'goblin-report.md';
        link.click();
        URL.revokeObjectURL(url);
    }

    _onTypeChange(e) {
        this._typeFilter = e.target.value;
    }

    _onMethodInput(e) {
        this._methodFilter = e.target.value;
    }

    _onRangeChange(e) {
        this._rangeFilter = e.target.value;
    }

    _filtered() {
        const type = this._typeFilter;
        const method = (this._methodFilter || '').trim().toLowerCase();
        const rangeMs = {
            all: 0,
            '5m': 5 * 60 * 1000,
            '30m': 30 * 60 * 1000,
            '1h': 60 * 60 * 1000,
            '24h': 24 * 60 * 60 * 1000,
        }[this._rangeFilter] || 0;
        const now = Date.now();
        return this._history.filter(r => {
            if (type !== 'all' && !this._matchesType(r, type)) {
                return false;
            }
            if (method && !(r.method || '').toLowerCase().includes(method)) {
                return false;
            }
            if (rangeMs > 0 && now - r.timestamp > rangeMs) {
                return false;
            }
            return true;
        });
    }

    _matchesType(record, type) {
        if (type === 'response-body') {
            return record.type.startsWith('response-body-');
        }
        if (type === 'response-header') {
            return record.type.startsWith('response-header-');
        }
        return record.type === type;
    }

    _typeClass(record) {
        return record.type && record.type.startsWith('response-header-') ? 'response-header' : record.type;
    }

    _typeLabel(record) {
        if (record.type && record.type.startsWith('response-header-')) {
            const rest = record.type.substring('response-header-'.length);
            const separator = rest.indexOf(':');
            const action = separator >= 0 ? rest.substring(0, separator) : rest;
            const header = separator >= 0 ? rest.substring(separator + 1) : '';
            const verb = {set: 'Set', remove: 'Remove'}[action] || action;
            return `Header ${verb}: ${header}`;
        }
        return QwcGoblinHistory.TYPE_LABELS[record.type] || record.type;
    }

    _sourceClass(record) {
        if (record.method && record.method.startsWith('WebClient ')) {
            return 'webclient';
        }
        if (record.method && record.method.startsWith('REST-Client ')) {
            return 'rest-client';
        }
        return '';
    }

    _sourceLabel(record) {
        return this._sourceClass(record) === 'webclient'
            ? 'WebClient'
            : (this._sourceClass(record) === 'rest-client' ? 'REST Client' : '');
    }

    _getRows() {
        return this._filtered().slice().sort((a, b) => b.timestamp - a.timestamp);
    }

    _summary() {
        const counts = {latency: 0, exception: 0, 'http-status': 0, 'dependency-degradation': 0,
            'response-body-truncate': 0, 'response-body-inflate': 0};
        let latencySum = 0;
        let latencyCount = 0;
        let responseHeader = 0;
        for (const r of this._history) {
            if (counts[r.type] !== undefined) {
                counts[r.type]++;
            }
            if (r.type && r.type.startsWith('response-header-')) {
                responseHeader++;
            }
            if (r.latencyMs) {
                latencySum += r.latencyMs;
                latencyCount++;
            }
        }
        return {
            total: this._history.length,
            latency: counts.latency,
            exception: counts.exception,
            httpStatus: counts['http-status'],
            dependency: counts['dependency-degradation'],
            responseBody: counts['response-body-truncate'] + counts['response-body-inflate'],
            responseHeader,
            avgLatency: latencyCount > 0 ? Math.round(latencySum / latencyCount) : 0,
        };
    }

    _formatTimestamp(ts) {
        return new Date(ts).toLocaleString(undefined, {
            year: '2-digit',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            fractionalSecondDigits: 3,
        });
    }

    _isoTimestamp(ts) {
        return new Date(ts).toISOString();
    }

    _cellKey(record) {
        return `${record.type}::${record.method}::${record.timestamp}`;
    }

    _isExpanded(record) {
        return this._expanded.has(this._cellKey(record));
    }

    _toggleConfig(record) {
        const key = this._cellKey(record);
        const next = new Set(this._expanded);
        if (next.has(key)) {
            next.delete(key);
        } else {
            next.add(key);
        }
        this._expanded = next;
    }

    _shortConfig(config) {
        if (!config) {
            return '';
        }
        const max = 48;
        return config.length > max ? config.slice(0, max).trimEnd() + '…' : config;
    }

    render() {
        const rows = this._getRows();
        const filteredCount = this._filtered().length;
        const s = this._summary();
        return html`
            <div class="toolbar">
                <h3>Assault History <span class="count">(${this._history.length})</span></h3>
                <div class="toolbar-buttons">
                    <button class="export-btn" @click="${this._exportMarkdown}">Export Markdown</button>
                    <button class="clear-btn ${this._confirmClear ? 'confirm' : ''}" @click="${this._clearHistory}">
                        ${this._confirmClear ? 'Confirm clear?' : 'Clear History'}
                    </button>
                </div>
            </div>
            <div class="status-hint">Auto-refreshes every 2 seconds while this section is visible.</div>

            <div class="summary">
                <span class="sum-item">Total: <b>${s.total}</b></span>
                <span class="sum-item">Latency: <b>${s.latency}</b></span>
                <span class="sum-item">Exception: <b>${s.exception}</b></span>
                <span class="sum-item">HTTP Status: <b>${s.httpStatus}</b></span>
                <span class="sum-item">Dependency: <b>${s.dependency}</b></span>
                <span class="sum-item">Response Body: <b>${s.responseBody}</b></span>
                <span class="sum-item">Response Header: <b>${s.responseHeader}</b></span>
                <span class="sum-item">Avg latency: <b>${s.avgLatency} ms</b></span>
            </div>

            <div class="filters">
                <label>Type</label>
                <select @change="${this._onTypeChange}" .value="${this._typeFilter}">
                    <option value="all">All</option>
                    <option value="latency">Latency</option>
                    <option value="exception">Exception</option>
                    <option value="http-status">HTTP Status</option>
                    <option value="dependency-degradation">Dependency</option>
                    <option value="response-body">Response Body</option>
                    <option value="response-header">Response Header</option>
                </select>
                <label>Method</label>
                <input type="text" placeholder="Search method…" .value="${this._methodFilter}"
                       @input="${this._onMethodInput}">
                <label>Period</label>
                <select @change="${this._onRangeChange}" .value="${this._rangeFilter}">
                    <option value="all">All time</option>
                    <option value="5m">Last 5 min</option>
                    <option value="30m">Last 30 min</option>
                    <option value="1h">Last 1 hour</option>
                    <option value="24h">Last 24 hours</option>
                </select>
                <span class="filter-result">${filteredCount}/${this._history.length} shown</span>
            </div>

            ${rows.length === 0
                ? html`<div class="empty-state">No assaults matching the current filters.</div>`
                : html`
                    <table>
                        <thead>
                            <tr>
                                <th class="time-col">Time</th>
                                <th class="method-col">Method</th>
                                <th class="type-col">Type</th>
                                <th class="duration-col">Duration</th>
                                <th class="config-col">Active Config</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${rows.map(record => html`
                                <tr>
                                    <td title="${this._isoTimestamp(record.timestamp)}">${this._formatTimestamp(record.timestamp)}</td>
                                    <td class="method-cell">${record.method}</td>
                                    <td>${this._sourceClass(record)
                                        ? html`<span class="source-badge ${this._sourceClass(record)}">${this._sourceLabel(record)}</span>`
                                        : ''}<span class="type-badge ${this._typeClass(record)}">${this._typeLabel(record)}</span></td>
                                    <td>${record.latencyMs ? record.latencyMs + ' ms' : '-'}</td>
                                    <td class="cfg-cell ${this._isExpanded(record) ? 'expanded' : 'collapsed'}"
                                        title="${this._isExpanded(record) ? '' : 'Click to expand'}"
                                        @click="${() => this._toggleConfig(record)}">
                                        ${record.config ? html`
                                            ${this._isExpanded(record) ? record.config : html`${this._shortConfig(record.config)} <span class="expand-hint">…click</span>`}
                                        ` : '-'}
                                    </td>
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
                            <button class="copy-btn" @click="${this._downloadMarkdown}">Download</button>
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