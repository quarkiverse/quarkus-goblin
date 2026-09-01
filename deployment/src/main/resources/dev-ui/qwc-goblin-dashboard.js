import {LitElement, html, css} from 'lit';
import {JsonRpc} from 'jsonrpc';

export class QwcGoblinDashboard extends LitElement {

    static styles = css`
        :host {
            display: block;
            padding: 16px;
            color: var(--lumo-contrast-color);
            background: var(--lumo-base-color);
        }
        h3 { margin: 0 0 16px 0; }
        .status-section {
            display: flex;
            align-items: center;
            gap: 12px;
            margin-bottom: 16px;
            padding: 12px 16px;
            border-radius: 8px;
            background: var(--lumo-contrast-5pct);
            border: 1px solid var(--lumo-contrast-10pct);
        }
        .status-dot {
            width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0;
        }
        .status-dot.active { background: var(--lumo-success-color); }
        .status-dot.inactive { background: var(--lumo-error-color); }
        .status-text { font-weight: 500; }
        .status-sep { color: var(--lumo-contrast-30pct); }
        .toggle-btn {
            margin-left: auto;
            padding: 6px 16px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
            cursor: pointer;
            font-size: 13px;
        }
        .toggle-btn:hover { background: var(--lumo-contrast-5pct); }
        .section {
            margin-bottom: 16px;
            border: 1px solid var(--lumo-contrast-10pct);
            border-radius: 8px;
            padding: 16px;
        }
        .section h4 {
            margin: 0 0 12px 0;
            font-size: 12px;
            color: var(--lumo-contrast-60pct);
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .assault-toggles {
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        .assault-toggle {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 8px 12px;
            border-radius: 6px;
            border: 1px solid var(--lumo-contrast-10pct);
            cursor: pointer;
            transition: background 0.15s;
        }
        .assault-toggle:hover { background: var(--lumo-contrast-5pct); }
        .assault-toggle.enabled {
            border-color: var(--lumo-primary-color-50pct);
            background: var(--lumo-primary-color-10pct);
        }
        .assault-toggle .label { flex: 1; font-size: 14px; }
        .assault-toggle .desc { font-size: 12px; color: var(--lumo-contrast-60pct); }
        .section .assault-toggle {
            margin-bottom: 4px;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
            border-radius: 6px 6px 0 0;
        }
        .section .assault-toggle + .form-row,
        .section .assault-toggle + .save-btn {
            margin-top: 6px;
        }
        .section .assault-toggle + .helper {
            margin-top: 4px;
        }
        .switch {
            position: relative;
            width: 40px; height: 22px;
            flex-shrink: 0;
        }
        .switch input { opacity: 0; width: 0; height: 0; }
        .slider {
            position: absolute; inset: 0;
            background: var(--lumo-contrast-20pct);
            border-radius: 22px;
            transition: background 0.2s;
            cursor: pointer;
        }
        .slider::before {
            content: '';
            position: absolute;
            width: 16px; height: 16px;
            left: 3px; top: 3px;
            background: var(--lumo-base-color);
            border-radius: 50%;
            transition: transform 0.2s;
            box-shadow: 0 1px 3px rgba(0,0,0,0.2);
        }
        .switch input:checked + .slider { background: var(--lumo-primary-color); }
        .switch input:checked + .slider::before { transform: translateX(18px); }
        .form-row {
            display: flex;
            align-items: center;
            gap: 10px;
            margin-bottom: 8px;
        }
        .form-row label {
            font-size: 13px;
            min-width: 100px;
            color: var(--lumo-contrast-color);
        }
        .form-row input[type="number"],
        .form-row input[type="text"] {
            padding: 6px 10px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            font-size: 13px;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .form-row input:focus {
            outline: none;
            border-color: var(--lumo-primary-color);
            box-shadow: 0 0 0 1px var(--lumo-primary-color-50pct);
        }
        .form-row input[type="text"] { flex: 1; }
        .form-row input:disabled,
        .form-row input[disabled] {
            opacity: 0.4;
            cursor: not-allowed;
        }
        .helper {
            font-size: 11px;
            color: var(--lumo-contrast-50pct);
            margin-top: 2px;
            font-style: italic;
        }
        .save-btn {
            padding: 6px 16px;
            border: none;
            border-radius: 4px;
            background: var(--lumo-primary-color);
            color: var(--lumo-primary-contrast-color);
            cursor: pointer;
            font-size: 13px;
            margin-top: 8px;
        }
        .save-btn:disabled {
            opacity: 0.4;
            cursor: not-allowed;
        }
        .save-btn:hover:not(:disabled) { filter: brightness(1.1); }
        .config-group { margin-top: 16px; }
        .config-group h4 { margin-bottom: 8px; }
        .toast {
            position: fixed; bottom: 20px; right: 20px;
            padding: 10px 18px;
            background: var(--lumo-success-color);
            color: var(--lumo-primary-contrast-color);
            border-radius: 6px;
            font-size: 13px;
            z-index: 1000;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
            animation: fadeOut 2s forwards;
        }
        @keyframes fadeOut {
            0% { opacity: 1; } 70% { opacity: 1; } 100% { opacity: 0; }
        }
    `;

    static properties = {
        _config: {state: true},
        _status: {state: true},
        _toast: {state: true},
    };

    constructor() {
        super();
        this._config = null;
        this._status = null;
        this._toast = '';
        this.jsonRpc = new JsonRpc(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._loadData();
    }

    _loadData() {
        this.jsonRpc.getConfig().then(r => { this._config = r.result; });
        this.jsonRpc.getStatus().then(r => { this._status = r.result; });
    }

    _showToast(msg) {
        this._toast = msg;
        setTimeout(() => { this._toast = ''; }, 2000);
    }

    _toggleActive() {
        this.jsonRpc.toggleActive().then(r => {
            this._status = {...this._status, active: r.result.active};
        });
    }

    _toggleAssault(key, rpcMethod) {
        this.jsonRpc[rpcMethod]().then(r => {
            if (r.result.ok) {
                this._config = {...this._config, ...r.result};
                this._status = {...this._status, ...r.result};
                const enabled = r.result[key];
                this._showToast(`${key.replace('Enabled', '')} ${enabled ? 'enabled' : 'disabled'}`);
            }
        });
    }

    _saveLatency() {
        const min = parseInt(this.shadowRoot.getElementById('lat-min').value);
        const max = parseInt(this.shadowRoot.getElementById('lat-max').value);
        this.jsonRpc.setLatencyRange({minMs: min, maxMs: max}).then(r => {
            if (r.result.ok) {
                this._config = {...this._config, latency: {
                    minMilliseconds: r.result.minMilliseconds, maxMilliseconds: r.result.maxMilliseconds
                }};
                this._showToast('Latency updated');
            }
        });
    }

    _saveException() {
        const type = this.shadowRoot.getElementById('exc-type').value;
        const msg = this.shadowRoot.getElementById('exc-msg').value;
        this.jsonRpc.setExceptionConfig({type, message: msg}).then(r => {
            if (r.result.ok) {
                this._config = {...this._config, exception: {type: r.result.type, message: r.result.message}};
                this._showToast('Exception updated');
            }
        });
    }

    _saveHttpStatus() {
        const code = parseInt(this.shadowRoot.getElementById('http-code').value);
        const msg = this.shadowRoot.getElementById('http-msg').value;
        this.jsonRpc.setHttpStatusConfig({code, message: msg}).then(r => {
            if (r.result.ok) {
                this._config = {...this._config, httpStatus: {code: r.result.code, message: r.result.message}};
                this._showToast('HTTP status updated');
            }
        });
    }

    _saveLevel() {
        const level = parseInt(this.shadowRoot.getElementById('target-level').value);
        this.jsonRpc.setTargetLevel({level}).then(r => {
            if (r.result.ok) {
                this._config = {...this._config, level: r.result.level};
                this._status = {...this._status, level: r.result.level};
                this._showToast('Target level updated');
            }
        });
    }

    render() {
        const c = this._config;
        return html`
            ${this._toast ? html`<div class="toast">${this._toast}</div>` : ''}
            <h3>Goblin Chaos Engineering</h3>

            ${this._status ? html`
                <div class="status-section">
                    <span class="status-dot ${this._status.active ? 'active' : 'inactive'}"></span>
                    <span class="status-text">${this._status.active ? 'Active' : 'Inactive'}</span>
                    <span class="status-sep">|</span>
                    <span class="status-text">Level: ${this._status.level}%</span>
                    <button class="toggle-btn" @click="${this._toggleActive}">
                        ${this._status.active ? 'Deactivate' : 'Activate'}
                    </button>
                </div>
            ` : ''}

            ${c ? html`

                <div class="section">
                    <div class="assault-toggle ${c.latencyEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('latencyEnabled', 'toggleLatency')}">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" ?checked="${c.latencyEnabled}"
                                   @change="${() => this._toggleAssault('latencyEnabled', 'toggleLatency')}">
                            <span class="slider"></span>
                        </label>
                        <div>
                            <div class="label">Latency</div>
                            <div class="desc">Artificial delay before processing</div>
                        </div>
                    </div>
                    ${c.latencyEnabled ? html`
                    <div class="form-row">
                        <label>Min</label>
                        <input type="number" id="lat-min" .value="${c.latency.minMilliseconds}" min="0">
                        <label>Max</label>
                        <input type="number" id="lat-max" .value="${c.latency.maxMilliseconds}" min="0">
                        <button class="save-btn" @click="${this._saveLatency}">Save</button>
                    </div>` : html`
                    <div class="helper">Enable latency assault to configure</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.exceptionEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('exceptionEnabled', 'toggleException')}">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" ?checked="${c.exceptionEnabled}"
                                   @change="${() => this._toggleAssault('exceptionEnabled', 'toggleException')}">
                            <span class="slider"></span>
                        </label>
                        <div>
                            <div class="label">Exception</div>
                            <div class="desc">Throw exception before method execution</div>
                        </div>
                    </div>
                    ${c.exceptionEnabled ? html`
                    <div class="form-row">
                        <label>Class</label>
                        <input type="text" id="exc-type" .value="${c.exception.type}">
                    </div>
                    <div class="form-row">
                        <label>Message</label>
                        <input type="text" id="exc-msg" .value="${c.exception.message}">
                    </div>
                    <button class="save-btn" @click="${this._saveException}">Save</button>` : html`
                    <div class="helper">Enable exception assault to configure</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.httpStatusEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('httpStatusEnabled', 'toggleHttpStatus')}">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" ?checked="${c.httpStatusEnabled}"
                                   @change="${() => this._toggleAssault('httpStatusEnabled', 'toggleHttpStatus')}">
                            <span class="slider"></span>
                        </label>
                        <div>
                            <div class="label">HTTP Status</div>
                            <div class="desc">Return specific HTTP status code</div>
                        </div>
                    </div>
                    ${c.httpStatusEnabled ? html`
                    <div class="form-row">
                        <label>Code</label>
                        <input type="number" id="http-code" .value="${c.httpStatus.code}" min="100" max="599">
                    </div>
                    <div class="form-row">
                        <label>Message</label>
                        <input type="text" id="http-msg" .value="${c.httpStatus.message}">
                    </div>
                    <button class="save-btn" @click="${this._saveHttpStatus}">Save</button>` : html`
                    <div class="helper">Enable HTTP status assault to configure</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.dependencyDegradationEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('dependencyDegradationEnabled', 'toggleDependencyDegradation')}">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" ?checked="${c.dependencyDegradationEnabled}"
                                   @change="${() => this._toggleAssault('dependencyDegradationEnabled', 'toggleDependencyDegradation')}">
                            <span class="slider"></span>
                        </label>
                        <div>
                            <div class="label">Dependency Degradation</div>
                            <div class="desc">Simulate downstream service failure (503)</div>
                        </div>
                    </div>
                    ${c.dependencyDegradationEnabled ? html`
                    <div class="helper">Returns HTTP 503 with a fixed "Dependency unavailable (Goblin chaos)" body.</div>` : html`
                    <div class="helper">Enable dependency degradation assault.</div>`}
                </div>

                <div class="section">
                    <h4>Target Level</h4>
                    <div class="form-row">
                        <label>Requests %</label>
                        <input type="number" id="target-level" .value="${c.level}" min="0" max="100">
                        <button class="save-btn" @click="${this._saveLevel}">Save</button>
                    </div>
                </div>

            ` : ''}
        `;
    }
}

customElements.define('qwc-goblin-dashboard', QwcGoblinDashboard);
