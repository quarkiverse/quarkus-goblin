import {LitElement, html, css} from 'lit';
import {JsonRpc} from 'jsonrpc';

const AUTO_OFF_KEY = 'goblin.autoOffDeadline';
const CUSTOM_PROFILES_KEY = 'goblin.customProfiles';

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
            flex-wrap: wrap;
            gap: 12px;
            margin-bottom: 16px;
            padding: 12px 16px;
            border-radius: 8px;
            background: var(--lumo-contrast-5pct);
            border: 1px solid var(--lumo-contrast-10pct);
        }
        .status-section + .status-section { margin-top: -8px; }
        .status-dot {
            width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0;
        }
        .status-dot.active { background: var(--lumo-success-color); }
        .status-dot.inactive { background: var(--lumo-error-color); }
        .status-text { font-weight: 500; }
        .status-sep { color: var(--lumo-contrast-30pct); }
        .status-level { display: flex; align-items: center; gap: 6px; }
        .status-level input[type="number"] {
            width: 60px;
            padding: 4px 8px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            font-size: 13px;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .toggle-btn, .save-btn, .danger-btn {
            padding: 6px 16px;
            border-radius: 4px;
            font-size: 13px;
            cursor: pointer;
        }
        .toggle-btn {
            border: 1px solid var(--lumo-contrast-30pct);
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .toggle-btn:hover { background: var(--lumo-contrast-5pct); }
        .danger-btn {
            border: none;
            background: var(--lumo-error-color);
            color: var(--lumo-primary-contrast-color);
        }
        .danger-btn:hover:not(:disabled) { filter: brightness(1.1); }
        .danger-zone-btn {
            border: 1px solid var(--lumo-error-color);
            background: transparent;
            color: var(--lumo-error-color);
        }
        .btn-text { border: none; background: none; color: var(--lumo-primary-color); padding: 0; cursor: pointer; font-size: 13px; }
        .btn-text:hover { text-decoration: underline; }
        .counters { display: flex; align-items: center; gap: 8px; font-size: 12px; }
        .chip {
            display: inline-flex; align-items: center; gap: 4px;
            padding: 2px 8px; border-radius: 10px;
            background: var(--lumo-contrast-10pct); font-size: 11px;
        }
        .chip b { font-weight: 600; }
        .blast-banner {
            margin-bottom: 16px;
            padding: 10px 16px;
            border-radius: 8px;
            border: 1px solid var(--lumo-error-color);
            background: var(--lumo-error-color-10pct, rgba(255,0,0,0.08));
            color: var(--lumo-error-color);
            font-size: 13px;
        }
        .auto-off { display: flex; align-items: center; gap: 8px; font-size: 13px; }
        .auto-off select {
            padding: 4px 8px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            font-size: 13px;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .countdown { font-weight: 600; color: var(--lumo-warning-color, #f0ad4e); }
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
        .priority {
            font-size: 10px;
            font-weight: 600;
            padding: 2px 6px;
            border-radius: 8px;
            background: var(--lumo-contrast-10pct);
            color: var(--lumo-contrast-60pct);
            flex-shrink: 0;
        }
        .manual-override {
            font-size: 10px;
            font-weight: 600;
            padding: 2px 6px;
            border-radius: 8px;
            background: var(--lumo-warning-color-10pct, rgba(240, 173, 78, 0.2));
            color: var(--lumo-warning-color, #f0ad4e);
            flex-shrink: 0;
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
        .switch input:focus-visible + .slider {
            outline: 2px solid var(--lumo-primary-color-50pct);
            outline-offset: 2px;
        }
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
        .form-row input[type="text"],
        .form-row select {
            padding: 6px 10px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 4px;
            font-size: 13px;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .form-row input.invalid {
            border-color: var(--lumo-error-color);
            box-shadow: 0 0 0 1px var(--lumo-error-color);
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
        }
        .field-error {
            font-size: 11px;
            color: var(--lumo-error-color);
            margin: 2px 0 6px 110px;
        }
        .quick-picks { display: flex; gap: 6px; margin: 2px 0 8px 0; flex-wrap: wrap; }
        .quick-pick {
            padding: 3px 10px;
            border: 1px solid var(--lumo-contrast-30pct);
            border-radius: 12px;
            font-size: 12px;
            cursor: pointer;
            background: var(--lumo-base-color);
            color: var(--lumo-contrast-color);
        }
        .quick-pick:hover { background: var(--lumo-contrast-5pct); }
        .save-btn {
            border: none;
            background: var(--lumo-primary-color);
            color: var(--lumo-primary-contrast-color);
            margin-top: 8px;
        }
        .save-btn:disabled {
            opacity: 0.4;
            cursor: not-allowed;
        }
        .save-btn:hover:not(:disabled) { filter: brightness(1.1); }
        .config-group { margin-top: 16px; }
        .config-group h4 { margin-bottom: 8px; }
        .config-group .form-row { display: flex; align-items: center; gap: 8px; }
        .config-group .actions { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
        .toast {
            --goblin-toast-bg: var(--lumo-success-color);
            position: fixed; bottom: 20px; right: 20px;
            padding: 10px 18px;
            background: var(--goblin-toast-bg);
            color: var(--lumo-primary-contrast-color);
            border-radius: 6px;
            font-size: 13px;
            z-index: 1000;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
            animation: fadeOut 5.5s forwards;
        }
        .toast.toast-warning { --goblin-toast-bg: var(--lumo-warning-color, #f0ad4e); }
        .toast.toast-error { --goblin-toast-bg: var(--lumo-error-color); }
        .toast.toast-info { --goblin-toast-bg: var(--lumo-primary-color); }
        @keyframes fadeOut {
            0% { opacity: 1; } 70% { opacity: 1; } 100% { opacity: 0; }
        }
    `;

    static profiles = [
        {
            value: 'NONE', label: 'None (manual)',
            desc: 'No predefined setup; control each assault manually.',
            toggles: {latencyEnabled: false, exceptionEnabled: false, httpStatusEnabled: false,
                dependencyDegradationEnabled: false, clientLatencyEnabled: false, clientExceptionEnabled: false,
                responseBodyEnabled: false},
        },
        {
            value: 'SLOW_FAILURE', label: 'Slow failure',
            desc: 'Delay then fail: latency 100-5000 ms plus a RuntimeException.',
            toggles: {latencyEnabled: true, exceptionEnabled: true, httpStatusEnabled: false,
                dependencyDegradationEnabled: false, clientLatencyEnabled: false, clientExceptionEnabled: false,
                responseBodyEnabled: false},
        },
        {
            value: 'INTERMITTENT', label: 'Intermittent',
            desc: 'Percentage-based random HTTP 500 responses. Combine with target level.',
            toggles: {latencyEnabled: false, exceptionEnabled: false, httpStatusEnabled: true,
                dependencyDegradationEnabled: false, clientLatencyEnabled: false, clientExceptionEnabled: false,
                responseBodyEnabled: false},
        },
        {
            value: 'TIMEOUT', label: 'Timeout',
            desc: 'Very high fixed latency (30 s) to exercise @Timeout and fallback rules.',
            toggles: {latencyEnabled: true, exceptionEnabled: false, httpStatusEnabled: false,
                dependencyDegradationEnabled: false, clientLatencyEnabled: false, clientExceptionEnabled: false,
                responseBodyEnabled: false},
        },
    ];

    static HTTP_PICKS = [500, 503, 429, 404];

    static properties = {
        _config: {state: true},
        _status: {state: true},
        _counters: {state: true},
        _toast: {state: true},
        _dirty: {state: true},
        _errors: {state: true},
        _autoOffDeadline: {state: true},
        _autoOffRemaining: {state: true},
        _customProfiles: {state: true},
        _activeCustom: {state: true},
    };

    constructor() {
        super();
        this._config = null;
        this._form = null;
        this._status = null;
        this._counters = null;
        this._toast = '';
        this._dirty = {};
        this._errors = {};
        this._autoOffDeadline = null;
        this._autoOffRemaining = 0;
        this._customProfiles = [];
        this._activeCustom = null;
        this.jsonRpc = new JsonRpc(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._loadData();
        this._loadCustomProfiles();
        this._restoreAutoOff();
        this._refreshTimer = setInterval(() => this._refresh(), 2000);
        this._tickTimer = setInterval(() => this._tick(), 1000);
        this._keyHandler = (e) => {
            if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'X' || e.key === 'x')) {
                e.preventDefault();
                this._confirmDisableAll();
            }
        };
        document.addEventListener('keydown', this._keyHandler);
    }

    disconnectedCallback() {
        clearInterval(this._refreshTimer);
        clearInterval(this._tickTimer);
        document.removeEventListener('keydown', this._keyHandler);
        super.disconnectedCallback();
    }

    _loadData() {
        this.jsonRpc.getConfig().then(r => { this._applyConfigResult(r.result); });
        this._refresh();
    }

    _refresh() {
        this.jsonRpc.getStatus().then(r => { this._status = {...r.result}; });
        this.jsonRpc.getCounters().then(r => { this._counters = {...r.result}; });
    }

    _applyConfigResult(result) {
        if (!result) {
            return;
        }
        this._config = {...result};
        this._form = this._syncFormFromConfig(result);
        this._status = {
            ...this._status,
            active: result.active !== undefined ? result.active : (this._status && this._status.active !== undefined ? this._status.active : true),
            profile: result.profile,
            level: result.level,
            latencyEnabled: result.latencyEnabled,
            exceptionEnabled: result.exceptionEnabled,
            httpStatusEnabled: result.httpStatusEnabled,
            dependencyDegradationEnabled: result.dependencyDegradationEnabled,
            clientLatencyEnabled: result.clientLatencyEnabled,
            clientExceptionEnabled: result.clientExceptionEnabled,
            responseBodyEnabled: result.responseBodyEnabled,
        };
        this._dirty = {};
        this._errors = {};
    }

    _showToast(msg, kind) {
        this._toast = {msg, kind};
        setTimeout(() => { if (this._toast && this._toast.msg === msg) { this._toast = ''; } }, 6000);
    }

    _toastClass(kind) {
        if (kind === 'warning') return 'toast-warning';
        if (kind === 'error') return 'toast-error';
        if (kind === 'info') return 'toast-info';
        return '';
    }

    _syncFormFromConfig(result) {
        const latency = result.latency || {};
        const exception = result.exception || {};
        const httpStatus = result.httpStatus || {};
        const body = result.body || {};
        return {
            level: String(result.level),
            latency: {
                min: String(latency.minMilliseconds),
                max: String(latency.maxMilliseconds),
            },
            exception: {
                type: exception.type == null ? '' : exception.type,
                message: exception.message == null ? '' : exception.message,
            },
            httpStatus: {
                code: httpStatus.code == null ? '' : String(httpStatus.code),
                message: httpStatus.message == null ? '' : httpStatus.message,
            },
            body: {
                mode: body.mode || 'TRUNCATE',
                pct: body.percentage == null ? '' : String(body.percentage),
            },
        };
    }

    _ensureForm() {
        if (!this._form) {
            this._form = this._syncFormFromConfig(this._config || {});
        }
    }

    _val(id) {
        const el = this.shadowRoot.getElementById(id);
        return el ? el.value : null;
    }

    _markDirty(section) {
        this._dirty = {...this._dirty, [section]: true};
    }

    _validate(section, ok, message) {
        this._errors = {...this._errors, [section]: ok ? undefined : message};
    }

    _errorsFor(section) {
        return this._errors[section];
    }

    _dirtyFor(section) {
        return !!this._dirty[section];
    }

    // ==================== status ====================

    _toggleActive() {
        this.jsonRpc.toggleActive().then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
            }
        });
    }

    _confirmDisableAll() {
        if (confirm('Disable ALL assaults and deactivate chaos? This is reversible via the dashboard.')) {
            this.jsonRpc.disableAll().then(r => {
                if (r.result.ok) {
                    this._applyConfigResult(r.result);
                    this._cancelAutoOff();
                    this._showToast('All assaults disabled', 'info');
                }
            });
        }
    }

    _disableAll() {
        this._confirmDisableAll();
    }

    _saveLevel() {
        if (this._errorsFor('level')) {
            this._showToast(this._errorsFor('level'), 'error');
            return;
        }
        this._ensureForm();
        const level = parseInt(this._form.level);
        this.jsonRpc.setTargetLevel({level}).then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
                this._showToast(r.result.warning || 'Target level updated', r.result.warning ? 'warning' : '');
            }
        });
    }

    _validateLevel() {
        this._ensureForm();
        this._form.level = this._val('target-level') || '';
        const level = parseInt(this._form.level);
        const ok = !isNaN(level) && level >= 0 && level <= 100;
        this._validate('level', ok, 'Level must be between 0 and 100');
        this._markDirty('level');
    }

    // ==================== auto-off / countdown ====================

    _startAutoOff() {
        const minutes = parseInt(this.shadowRoot.getElementById('auto-off-minutes').value);
        if (isNaN(minutes) || minutes <= 0) {
            return;
        }
        const deadline = Date.now() + minutes * 60000;
        localStorage.setItem(AUTO_OFF_KEY, deadline);
        this._autoOffDeadline = deadline;
        this._tick();
        this._showToast(`Chaos will auto-disable in ${minutes} min`, 'info');
    }

    _cancelAutoOff() {
        localStorage.removeItem(AUTO_OFF_KEY);
        this._autoOffDeadline = null;
        this._autoOffRemaining = 0;
    }

    _restoreAutoOff() {
        const raw = localStorage.getItem(AUTO_OFF_KEY);
        const deadline = parseInt(raw);
        if (!isNaN(deadline) && deadline > Date.now()) {
            this._autoOffDeadline = deadline;
        } else if (!isNaN(deadline)) {
            localStorage.removeItem(AUTO_OFF_KEY);
        }
    }

    _tick() {
        if (!this._autoOffDeadline) {
            if (this._autoOffRemaining !== 0) {
                this._autoOffRemaining = 0;
            }
            return;
        }
        const remaining = Math.max(0, Math.round((this._autoOffDeadline - Date.now()) / 1000));
        if (this._autoOffRemaining !== remaining) {
            this._autoOffRemaining = remaining;
        }
        if (remaining === 0) {
            this._autoOffDeadline = null;
            localStorage.removeItem(AUTO_OFF_KEY);
            if (this._status && this._status.active) {
                this.jsonRpc.setActive(false).then(r => {
                    if (r.result && r.result.ok) {
                        this._applyConfigResult(r.result);
                        this._showToast('Chaos auto-disabled', 'info');
                    }
                });
            }
        }
    }

    _autoOffLabel() {
        const t = this._autoOffRemaining;
        if (t <= 0) {
            return '';
        }
        const mm = Math.floor(t / 60);
        const ss = t % 60;
        return `${mm}m${ss < 10 ? '0' : ''}${ss}s`;
    }

    // ==================== assaults ====================

    _toggleAssault(key, rpcMethod) {
        this.jsonRpc[rpcMethod]().then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
                const enabled = r.result[key];
                this._showToast(`${key.replace('Enabled', '')} ${enabled ? 'enabled' : 'disabled'}`, enabled ? '' : 'info');
            }
        });
    }

    _saveLatency() {
        if (this._errorsFor('latency')) {
            this._showToast(this._errorsFor('latency'), 'error');
            return;
        }
        this._ensureForm();
        const min = parseInt(this._form.latency.min);
        const max = parseInt(this._form.latency.max);
        this.jsonRpc.setLatencyRange({minMs: min, maxMs: max}).then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
                this._showToast(r.result.warning || 'Latency updated', r.result.warning ? 'warning' : '');
            }
        });
    }

    _validateLatency() {
        this._ensureForm();
        this._form.latency.min = this._val('lat-min') || '';
        this._form.latency.max = this._val('lat-max') || '';
        const min = parseInt(this._form.latency.min);
        const max = parseInt(this._form.latency.max);
        const ok = !isNaN(min) && !isNaN(max) && min >= 0 && max >= 0 && min <= max;
        this._validate('latency', ok, 'Requires min >= 0, max >= 0 and min <= max');
        this._markDirty('latency');
    }

    _saveException() {
        if (this._errorsFor('exception')) {
            this._showToast(this._errorsFor('exception'), 'error');
            return;
        }
        this._ensureForm();
        const type = this._form.exception.type;
        const msg = this._form.exception.message;
        this.jsonRpc.setExceptionConfig({type, message: msg}).then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
                this._showToast(r.result.warning || 'Exception updated', r.result.warning ? 'warning' : '');
            }
        });
    }

    _validateException() {
        this._ensureForm();
        this._form.exception.type = this._val('exc-type') || '';
        const type = this._form.exception.type.trim();
        const ok = type.length > 0;
        this._validate('exception', ok, 'Exception class must not be empty');
        this._markDirty('exception');
    }

    _inputExceptionMessage() {
        this._ensureForm();
        this._form.exception.message = this._val('exc-msg') || '';
        this._markDirty('exception');
    }

    _pickException(type) {
        this._ensureForm();
        this._form.exception.type = type;
        this.shadowRoot.getElementById('exc-type').value = type;
        this._validateException();
    }

    _saveHttpStatus() {
        if (this._errorsFor('httpStatus')) {
            this._showToast(this._errorsFor('httpStatus'), 'error');
            return;
        }
        this._ensureForm();
        const code = parseInt(this._form.httpStatus.code);
        const msg = this._form.httpStatus.message;
        this.jsonRpc.setHttpStatusConfig({code, message: msg}).then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
                this._showToast(r.result.warning || 'HTTP status updated', r.result.warning ? 'warning' : '');
            }
        });
    }

    _validateHttpStatus() {
        this._ensureForm();
        this._form.httpStatus.code = this._val('http-code') || '';
        const code = parseInt(this._form.httpStatus.code);
        const ok = !isNaN(code) && code >= 100 && code <= 599;
        this._validate('httpStatus', ok, 'Status code must be between 100 and 599');
        this._markDirty('httpStatus');
    }

    _inputHttpMessage() {
        this._ensureForm();
        this._form.httpStatus.message = this._val('http-msg') || '';
        this._markDirty('httpStatus');
    }

    _pickHttpCode(code) {
        this._ensureForm();
        this._form.httpStatus.code = String(code);
        this.shadowRoot.getElementById('http-code').value = String(code);
        this._validateHttpStatus();
    }

    _saveBody() {
        if (this._errorsFor('body')) {
            this._showToast(this._errorsFor('body'), 'error');
            return;
        }
        this._ensureForm();
        const mode = this._form.body.mode;
        const percentage = parseInt(this._form.body.pct);
        this.jsonRpc.setResponseBodyConfig({mode, percentage}).then(r => {
            if (r.result.ok) {
                this._applyConfigResult(r.result);
                this._showToast(r.result.warning || 'Response body updated', r.result.warning ? 'warning' : '');
            } else {
                this._showToast(r.result && r.result.error || 'Response body update failed', 'error');
            }
        }).catch(() => this._showToast('Response body update failed', 'error'));
    }

    _validateBody() {
        this._ensureForm();
        this._form.body.mode = this._val('body-mode') || this._form.body.mode;
        this._form.body.pct = this._val('body-pct') || '';
        const mode = this._form.body.mode;
        const percentage = parseInt(this._form.body.pct);
        const bounds = mode === 'INFLATE' ? [101, 1000] : [0, 100];
        const ok = !isNaN(percentage) && percentage >= bounds[0] && percentage <= bounds[1];
        this._validate('body', ok, `Percentage must be ${bounds[0]}-${bounds[1]} for ${mode}`);
        this._markDirty('body');
    }

    _bodyModeChanged() {
        this._validateBody();
    }

    // ==================== profiles ====================

    _profileLabel(profile) {
        const match = QwcGoblinDashboard.profiles.find(p => p.value === profile);
        if (match) {
            return match.label;
        }
        if (this._activeCustom) {
            return this._activeCustom;
        }
        return profile;
    }

    _profileDesc(profile) {
        const match = QwcGoblinDashboard.profiles.find(p => p.value === profile);
        return match ? match.desc : (this._activeCustom ? 'Custom profile saved in this browser.' : '');
    }

    _isManualOverride() {
        const c = this._config;
        if (!c || c.profile === 'NONE') {
            return false;
        }
        const profile = QwcGoblinDashboard.profiles.find(p => p.value === c.profile);
        if (!profile) {
            return false;
        }
        return ['latencyEnabled', 'exceptionEnabled', 'httpStatusEnabled', 'dependencyDegradationEnabled',
            'clientLatencyEnabled', 'clientExceptionEnabled', 'responseBodyEnabled'].some(k => c[k] !== profile.toggles[k]);
    }

    _setProfile(e) {
        const value = e.target.value;
        if (value.startsWith('custom:')) {
            const name = value.substring(7);
            const saved = this._customProfiles.find(p => p.name === name);
            if (!saved) {
                return;
            }
            this.jsonRpc.applyConfig(saved.config).then(r => {
                if (r.result && r.result.ok) {
                    this._activeCustom = name;
                    this._applyConfigResult(r.result);
                    this._showToast(r.result.warning || `Profile '${name}' applied`, r.result.warning ? 'warning' : '');
                } else {
                    this._showToast(r.result && r.result.error || 'Profile application failed', 'error');
                }
            }).catch(() => this._showToast('Profile application failed', 'error'));
            return;
        }
        const profile = QwcGoblinDashboard.profiles.find(p => p.value === value);
        if (profile && profile.value !== 'NONE' && !confirm(`Apply profile '${profile.label}'?\n${profile.desc}`)) {
            return;
        }
        this.jsonRpc.setProfile({profile: value}).then(r => {
            if (r && r.result && r.result.ok) {
                this._activeCustom = null;
                this._applyConfigResult(r.result);
                this._showToast(`Profile ${this._profileLabel(r.result.profile)} applied`);
            } else {
                this._showToast(r && r.result && r.result.error || 'Profile update failed', 'error');
            }
        }).catch(() => this._showToast('Profile update failed', 'error'));
    }

    _loadCustomProfiles() {
        try {
            this._customProfiles = JSON.parse(localStorage.getItem(CUSTOM_PROFILES_KEY) || '[]');
        } catch (e) {
            this._customProfiles = [];
        }
    }

    _saveCustomProfile() {
        if (!this._config) {
            return;
        }
        const name = (prompt('Save the current configuration as a custom profile:', '') || '').trim();
        if (!name) {
            return;
        }
        const next = [...this._customProfiles.filter(p => p.name !== name), {name, config: this._config}];
        localStorage.setItem(CUSTOM_PROFILES_KEY, JSON.stringify(next));
        this._customProfiles = next;
        this._showToast(`Profile '${name}' saved locally`, 'info');
    }

    _deleteCustomProfileActivate() {
        if (!this._activeCustom) {
            return;
        }
        this._deleteCustomProfile(this._activeCustom);
    }

    _deleteCustomProfile(name) {
        const next = this._customProfiles.filter(p => p.name !== name);
        localStorage.setItem(CUSTOM_PROFILES_KEY, JSON.stringify(next));
        this._customProfiles = next;
        this._activeCustom = null;
        this._showToast(`Profile '${name}' deleted`, 'info');
    }

    // ==================== danger zone ====================

    _resetDefaults() {
        if (confirm('Reset all assaults and parameters to their application.properties defaults?')) {
            this.jsonRpc.resetDefaults().then(r => {
                if (r.result.ok) {
                    this._applyConfigResult(r.result);
                    this._cancelAutoOff();
                    this._showToast(r.result.warning || 'Configuration reset to defaults', r.result.warning ? 'warning' : '');
                } else {
                    this._showToast(r.result.error, 'error');
                }
            });
        }
    }

    _exportConfig() {
        if (!this._config) {
            return;
        }
        const blob = new Blob([JSON.stringify(this._config, null, 2)], {type: 'application/json'});
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'goblin-config.json';
        a.click();
        URL.revokeObjectURL(url);
    }

    _importConfig(e) {
        const file = e.target.files && e.target.files[0];
        if (!file) {
            return;
        }
        const reader = new FileReader();
        reader.onload = () => {
            try {
                const config = JSON.parse(reader.result);
                this.jsonRpc.applyConfig(config).then(r => {
                    if (r.result && r.result.ok) {
                        this._applyConfigResult(r.result);
                        this._showToast(r.result.warning || 'Configuration imported', r.result.warning ? 'warning' : '');
                    } else {
                        this._showToast(r.result && r.result.error || 'Import failed', 'error');
                    }
                }).catch(() => this._showToast('Import failed', 'error'));
            } catch (err) {
                this._showToast('Invalid configuration file', 'error');
            }
        };
        reader.readAsText(file);
        e.target.value = '';
    }

    _resetCounters() {
        this.jsonRpc.resetCounters().then(r => {
            if (r.result.ok) {
                this._refresh();
                this._showToast('Counters reset', 'info');
            }
        });
    }

    // ==================== derived state ====================

    _enabledServerAssaults() {
        const c = this._status || {};
        return ['latencyEnabled', 'exceptionEnabled', 'httpStatusEnabled', 'dependencyDegradationEnabled',
            'responseBodyEnabled'].filter(k => c[k]).length;
    }

    _enabledClientAssaults() {
        const c = this._status || {};
        return ['clientLatencyEnabled', 'clientExceptionEnabled'].filter(k => c[k]).length;
    }

    _blastRadius() {
        const s = this._status || {};
        const server = this._enabledServerAssaults();
        if (!s.active || server === 0) {
            return null;
        }
        if (s.level >= 100 && server >= 2) {
            return `${server} server-side assaults enabled at ${s.level}% level: every eligible request is assaulted.`;
        }
        return null;
    }

    _counterCount(mode) {
        const byType = this._counters && this._counters.byType ? this._counters.byType : {};
        let total = 0;
        for (const key in byType) {
            if (mode === 'response' && key.indexOf('response-body') === 0) {
                total += byType[key];
            } else if (mode === 'server' && key.indexOf('response-body') !== 0
                && key.indexOf('client-') !== 0) {
                total += byType[key];
            } else if (mode === 'client' && key.indexOf('client-') === 0) {
                total += byType[key];
            }
        }
        return total;
    }

    // ==================== render ====================

    render() {
        const c = this._config;
        const s = this._status;
        const counters = this._counters;
        return html`
            ${this._toast ? html`<div class="toast ${this._toastClass(this._toast.kind)}">${this._toast.msg}</div>` : ''}
            <h3>Goblin Chaos Engineering</h3>

            ${s ? html`
            <div class="status-section">
                <span class="status-dot ${s.active ? 'active' : 'inactive'}"></span>
                <span class="status-text">${s.active ? 'Active' : 'Inactive'}</span>
                <span class="status-sep">|</span>
                <div class="status-level">
                    <label>Level</label>
                    <input type="number" id="target-level"
                           .value="${this._form ? this._form.level : (c ? c.level : s.level)}" min="0" max="100"
                           @input="${this._validateLevel}">
                    <button class="toggle-btn" @click="${this._saveLevel}"
                            ?disabled="${!this._dirtyFor('level') || this._errorsFor('level')}">Save</button>
                </div>
                <span class="status-sep">|</span>
                <span class="status-text">Profile: ${this._profileLabel(s.profile)}
                    ${this._isManualOverride() ? html`<span class="manual-override">override</span>` : ''}</span>
                <span class="status-sep">|</span>
                <div class="counters">
                    <span class="chip"><b>${counters ? counters.total : 0}</b> assaults
                        <button class="btn-text" @click="${this._resetCounters}">reset</button>
                    </span>
                    ${counters && counters.total ? html`
                        <span class="chip">server <b>${this._counterCount('server')}</b></span>
                        <span class="chip">client <b>${this._counterCount('client')}</b></span>
                        <span class="chip">body <b>${this._counterCount('response')}</b></span>
                    ` : ''}
                </div>
                <button class="toggle-btn" @click="${this._toggleActive}">
                    ${s.active ? 'Deactivate' : 'Activate'}
                </button>
                <button class="danger-btn" @click="${this._disableAll}" title="Ctrl/Cmd+Shift+X">
                    ⏻ Disable all
                </button>
            </div>
            ${this._errorsFor('level') ? html`<div class="field-error">${this._errorsFor('level')}</div>` : ''}

            <div class="status-section">
                <div class="auto-off">
                    <span>Auto-off</span>
                    <select id="auto-off-minutes" ?disabled="${!!this._autoOffDeadline}">
                        <option value="5">5 min</option>
                        <option value="10">10 min</option>
                        <option value="30">30 min</option>
                        <option value="60">60 min</option>
                    </select>
                    ${this._autoOffDeadline ? html`
                        <button class="toggle-btn" @click="${this._cancelAutoOff}">Cancel</button>
                        <span class="countdown">off in ${this._autoOffLabel()}</span>
                    ` : html`
                        <button class="toggle-btn" @click="${this._startAutoOff}">Start</button>
                    `}
                </div>
            </div>

            ${this._blastRadius() ? html`<div class="blast-banner">${this._blastRadius()}</div>` : ''}
            ` : ''}

            ${c ? html`

                <div class="section">
                    <h4>Profile</h4>
                    <div class="form-row">
                        <label>Profile</label>
                        <select id="goblin-profile" @change="${this._setProfile}">
                            ${QwcGoblinDashboard.profiles.map(p => html`
                            <option value="${p.value}" ?selected="${c.profile === p.value}">${p.label}</option>
                            `)}
                            ${this._customProfiles.map(p => html`
                            <option value="custom:${p.name}"
                                    ?selected="${this._activeCustom === p.name}">Custom: ${p.name}</option>
                            `)}
                        </select>
                        <button class="toggle-btn" @click="${this._saveCustomProfile}">Save current as profile</button>
                        ${this._activeCustom ? html`<button class="toggle-btn" @click="${this._deleteCustomProfileActivate}">Delete active</button>` : ''}
                    </div>
                    <div class="helper">${this._profileDesc(c.profile)}</div>
                </div>

                <div class="section">
                    <h4>Server-side assaults
                        <span class="helper">Run in priority order: a delaying assault first, then the first aborting
                            assault (exception &gt; HTTP status &gt; dependency) short-circuits; response body applies to the
                            emitted response.</span>
                    </h4>

                    <div class="assault-toggle ${c.latencyEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('latencyEnabled', 'toggleLatency')}"
                         title="Priority 10 - artificial delay before processing">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="Latency assault"
                                   ?checked="${c.latencyEnabled}"
                                   @change="${() => this._toggleAssault('latencyEnabled', 'toggleLatency')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">Latency</div>
                            <div class="desc">Artificial delay before processing</div>
                        </div>
                        <span class="priority">10</span>
                    </div>
                    ${c.latencyEnabled ? html`
                    <div class="form-row">
                        <label>Min</label>
                        <input type="number" id="lat-min"
                               .value="${this._form ? this._form.latency.min : c.latency.minMilliseconds}"
                               min="0" @input="${this._validateLatency}">
                        <label>Max</label>
                        <input type="number" id="lat-max"
                               .value="${this._form ? this._form.latency.max : c.latency.maxMilliseconds}"
                               min="0" @input="${this._validateLatency}">
                        <button class="save-btn" @click="${this._saveLatency}"
                                ?disabled="${!this._dirtyFor('latency') || this._errorsFor('latency')}">Save</button>
                    </div>
                    ${this._errorsFor('latency') ? html`<div class="field-error">${this._errorsFor('latency')}</div>` : ''}
                    <div class="helper">Current: ${c.latency.minMilliseconds}-${c.latency.maxMilliseconds} ms.
                        Used by the client latency assault too.</div>` : html`
                    <div class="helper">Current: ${c.latency.minMilliseconds}-${c.latency.maxMilliseconds} ms.
                        Enable latency assault to configure.</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.exceptionEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('exceptionEnabled', 'toggleException')}"
                         title="Priority 20 - throws before executing the method">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="Exception assault"
                                   ?checked="${c.exceptionEnabled}"
                                   @change="${() => this._toggleAssault('exceptionEnabled', 'toggleException')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">Exception</div>
                            <div class="desc">Throw exception before method execution</div>
                        </div>
                        <span class="priority">20</span>
                        ${c.exceptionEnabled ? html`<span class="priority">${c.exception.type}</span>` : ''}
                    </div>
                    ${c.exceptionEnabled ? html`
                    <div class="quick-picks">
                        ${(c.exceptionPresets || []).map(t => html`
                        <button class="quick-pick" @click="${() => this._pickException(t)}">${t.split('.').pop()}</button>
                        `)}
                    </div>
                    <div class="form-row">
                        <label>Class</label>
                        <input type="text" id="exc-type"
                               .value="${this._form ? this._form.exception.type : c.exception.type}"
                               @input="${this._validateException}">
                    </div>
                    ${this._errorsFor('exception') ? html`<div class="field-error">${this._errorsFor('exception')}</div>` : ''}
                    <div class="form-row">
                        <label>Message</label>
                        <input type="text" id="exc-msg"
                               .value="${this._form ? this._form.exception.message : c.exception.message}"
                               @input="${this._inputExceptionMessage}">
                    </div>
                    <button class="save-btn" @click="${this._saveException}"
                            ?disabled="${!this._dirtyFor('exception') || this._errorsFor('exception')}">Save</button>` : html`
                    <div class="helper">Current: ${c.exception.type}. Enable exception assault to configure.</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.httpStatusEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('httpStatusEnabled', 'toggleHttpStatus')}"
                         title="Priority 30 - aborts the request with a status code">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="HTTP status assault"
                                   ?checked="${c.httpStatusEnabled}"
                                   @change="${() => this._toggleAssault('httpStatusEnabled', 'toggleHttpStatus')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">HTTP Status</div>
                            <div class="desc">Return specific HTTP status code</div>
                        </div>
                        <span class="priority">30</span>
                        ${c.httpStatusEnabled ? html`<span class="priority">${c.httpStatus.code}</span>` : ''}
                    </div>
                    ${c.httpStatusEnabled ? html`
                    <div class="quick-picks">
                        ${QwcGoblinDashboard.HTTP_PICKS.map(code => html`
                        <button class="quick-pick" @click="${() => this._pickHttpCode(code)}">${code}</button>
                        `)}
                    </div>
                    <div class="form-row">
                        <label>Code</label>
                        <input type="number" id="http-code"
                               .value="${this._form ? this._form.httpStatus.code : c.httpStatus.code}"
                               min="100" max="599" @input="${this._validateHttpStatus}">
                    </div>
                    ${this._errorsFor('httpStatus') ? html`<div class="field-error">${this._errorsFor('httpStatus')}</div>` : ''}
                    <div class="form-row">
                        <label>Message</label>
                        <input type="text" id="http-msg"
                               .value="${this._form ? this._form.httpStatus.message : c.httpStatus.message}"
                               @input="${this._inputHttpMessage}">
                    </div>
                    <button class="save-btn" @click="${this._saveHttpStatus}"
                            ?disabled="${!this._dirtyFor('httpStatus') || this._errorsFor('httpStatus')}">Save</button>`
                    : html`<div class="helper">Current: ${c.httpStatus.code}. Enable HTTP status assault to configure.</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.dependencyDegradationEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('dependencyDegradationEnabled', 'toggleDependencyDegradation')}"
                         title="Priority 40 - simulates a downstream service failure">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="Dependency degradation assault"
                                   ?checked="${c.dependencyDegradationEnabled}"
                                   @change="${() => this._toggleAssault('dependencyDegradationEnabled', 'toggleDependencyDegradation')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">Dependency Degradation</div>
                            <div class="desc">Simulate downstream service failure (503)</div>
                        </div>
                        <span class="priority">40</span>
                    </div>
                    ${c.dependencyDegradationEnabled ? html`
                    <div class="helper">Returns HTTP 503 with a fixed "Dependency unavailable (Goblin chaos)" body.</div>` : html`
                    <div class="helper">Returns HTTP 503 with a fixed "Dependency unavailable (Goblin chaos)" body.</div>`}
                </div>

                <div class="section">
                    <div class="assault-toggle ${c.responseBodyEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('responseBodyEnabled', 'toggleResponseBody')}"
                         title="Response phase - rewrites the emitted entity">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="Response body assault"
                                   ?checked="${c.responseBodyEnabled}"
                                   @change="${() => this._toggleAssault('responseBodyEnabled', 'toggleResponseBody')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">Response Body</div>
                            <div class="desc">Truncate or inflate the response payload</div>
                        </div>
                        <span class="priority">R</span>
                    </div>
                    ${c.responseBodyEnabled ? html`
                    <div class="form-row">
                        <label>Mode</label>
                        <select id="body-mode" @change="${this._bodyModeChanged}">
                            <option value="TRUNCATE" ?selected="${this._form.body.mode === 'TRUNCATE'}">Truncate</option>
                            <option value="INFLATE" ?selected="${this._form.body.mode === 'INFLATE'}">Inflate</option>
                        </select>
                    </div>
                    <div class="form-row">
                        <label>Percentage %</label>
                        <input type="number" id="body-pct" .value="${this._form.body.pct}"
                               min="${this._form.body.mode === 'INFLATE' ? 101 : 0}"
                               max="${this._form.body.mode === 'INFLATE' ? 1000 : 100}"
                               @input="${this._validateBody}">
                        <button class="save-btn" @click="${this._saveBody}"
                                ?disabled="${!this._dirtyFor('body') || this._errorsFor('body')}">Save</button>
                    </div>
                    ${this._errorsFor('body') ? html`<div class="field-error">${this._errorsFor('body')}</div>` : ''}
                    <div class="helper">Truncate keeps percentage% of the original body; inflate pads it up to percentage% of
                        its original length. Inflate must stay 101-1000%.</div>` : html`
                    <div class="helper">Current: ${c.body.mode} ${c.body.percentage}%. Enable response body assault to configure.</div>`}
                </div>

                <div class="section">
                    <h4>Client-side assaults
                        <span class="helper">Affect outgoing REST Client calls; reuse the latency range and exception
                            class above. The remote service is never modified.</span>
                    </h4>
                    <div class="assault-toggle ${c.clientLatencyEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('clientLatencyEnabled', 'toggleClientLatency')}">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="Client latency assault"
                                   ?checked="${c.clientLatencyEnabled}"
                                   @change="${() => this._toggleAssault('clientLatencyEnabled', 'toggleClientLatency')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">Client Latency</div>
                            <div class="desc">Delay outgoing REST Client calls before dispatch</div>
                        </div>
                        <span class="priority">C</span>
                    </div>
                    <div class="helper">Uses the latency range configured above (${c.latency.minMilliseconds}-${c.latency.maxMilliseconds} ms).</div>
                    <div class="assault-toggle ${c.clientExceptionEnabled ? 'enabled' : ''}"
                         @click="${() => this._toggleAssault('clientExceptionEnabled', 'toggleClientException')}">
                        <label class="switch" @click="${e => e.stopPropagation()}">
                            <input type="checkbox" role="switch" aria-label="Client exception assault"
                                   ?checked="${c.clientExceptionEnabled}"
                                   @change="${() => this._toggleAssault('clientExceptionEnabled', 'toggleClientException')}">
                            <span class="slider" aria-hidden="true"></span>
                        </label>
                        <div>
                            <div class="label">Client Exception</div>
                            <div class="desc">Throw before outbound REST Client calls are dispatched</div>
                        </div>
                        <span class="priority">C</span>
                    </div>
                    <div class="helper">Uses the exception class/message configured above (${c.exception.type}).</div>
                </div>

                <div class="section config-group">
                    <h4>Danger zone</h4>
                    <div class="actions">
                        <button class="danger-btn danger-zone-btn" @click="${this._resetDefaults}">Reset to defaults</button>
                        <button class="toggle-btn" @click="${this._exportConfig}">Export config</button>
                        <label class="toggle-btn" style="cursor:pointer">
                            Import config
                            <input type="file" accept="application/json,.json" style="display:none"
                                   @change="${this._importConfig}">
                        </label>
                    </div>
                    <div class="helper">State persists in .goblin-state.json in the working directory. Export/import lets you
                        share a setup (including the target level) between environments.</div>
                </div>

            ` : ''}
        `;
    }
}

customElements.define('qwc-goblin-dashboard', QwcGoblinDashboard);