/**
 * rikkaST st-compat: extensions.js
 * ============================================================
 * 模拟 SillyTavern 1.19.0 `public/scripts/extensions.js` 的完整导出面。
 * 第三方扩展最高频的 `getContext` / `extension_settings` / `renderExtensionTemplateAsync` / `ModuleWorkerWrapper` 均由此提供。
 */

import { SimpleMutex } from './util/SimpleMutex.js';

const rootWin = () => (typeof window !== 'undefined' ? window : globalThis);
const st = () => { const w = rootWin(); return (w && w.SillyTavern && w.SillyTavern.getContext ? w.SillyTavern.getContext() : {}); };
const host = () => (window.__rikkaSt || {});

export const UNSET_VALUE = undefined;
export const EMPTY_AUTHOR = '<No author>';
export { SimpleMutex, SimpleMutex as ModuleWorkerWrapper };

/** 全扩展共享的设置对象（live：runtime refreshAll 后自动同步）。 */
export let extension_settings = st().extensionSettings || {};
export let extensionNames = [];
export let modules = [];

try {
    if (typeof window !== 'undefined' && window.addEventListener) window.addEventListener('rikka-st-refresh', () => {
        try {
            const c = st();
            if (c.extensionSettings && typeof c.extensionSettings === 'object') {
                extension_settings = c.extensionSettings;
            }
        } catch (_e) { /* noop */ }
        try { __rikkaRefreshExtensionTypes(); } catch (_e) { /* noop */ }
    });
} catch (_e) { /* noop */ }

/**
 * ── [v202] 预置 `extension_settings.tavern_helper` 的完整默认形状 ──────────
 */
function __rikkaSeedTavernHelperSettings(settings) {
    try {
        if (!settings || typeof settings !== 'object') return;
        const s = settings.script && typeof settings.script === 'object' ? settings.script : (settings.script = {});
        const e = s.enabled && typeof s.enabled === 'object' ? s.enabled : (s.enabled = {});
        if (!Array.isArray(e.presets)) e.presets = [];
        if (!Array.isArray(e.characters)) e.characters = [];
        if (typeof e.global !== 'boolean') e.global = true;
        const pu = s.popuped && typeof s.popuped === 'object' ? s.popuped : (s.popuped = {});
        if (!Array.isArray(pu.presets)) pu.presets = [];
        if (!Array.isArray(pu.characters)) pu.characters = [];
        if (!Array.isArray(s.scripts)) s.scripts = [];
    } catch (_e) { /* noop */ }
}

export function __rikkaEnsureTavernHelperSeed() {
    try {
        __rikkaSeedTavernHelperSettings(extension_settings.tavern_helper);
    } catch (_e) { /* noop */ }
}

try { __rikkaEnsureTavernHelperSeed(); } catch (_e) { /* noop */ }

function normalizeFolderAlias(raw) {
    return String(raw == null ? '' : raw)
        .trim()
        .replace(/^third-party\//i, '')
        .replace(/-(?:main|master|dev|release|v?\d+(?:\.\d+)*(?:-[a-z0-9._-]+)?)$/i, '')
        .toLowerCase();
}

/**
 * [v243 BUGFIX C2] 扩展类型表。
 * 同时提供保留常量键与 `third-party/<folder>` 映射（含去版本后缀别名）。
 */
export const extensionTypes = { SYSTEM: 'system', LOCAL: 'local', GLOBAL: 'global' };

function __rikkaRefreshExtensionTypes() {
    try {
        const list = host().extensions || [];
        const names = [];
        for (const e of list) {
            if (e && e.folder) {
                const f = String(e.folder);
                const fullName = 'third-party/' + f;
                extensionTypes[fullName] = 'local';
                names.push(fullName);
                const alias = normalizeFolderAlias(f);
                if (alias && alias !== f.toLowerCase()) {
                    extensionTypes['third-party/' + alias] = 'local';
                }
            }
        }
        extensionNames = names;
    } catch (_e) { /* noop */ }
}
try { __rikkaRefreshExtensionTypes(); } catch (_e) { /* noop */ }

export function getContext() {
    return st();
}

export function getApiUrl() {
    return (extension_settings && extension_settings.apiUrl) || 'http://127.0.0.1:5100';
}

export async function doExtrasFetch(url, options = {}) {
    return fetch(url, options);
}

/** 写扩展字段的便捷函数（对齐 ST：extension_settings[name][field] = value）。 */
export function writeExtensionField(extensionName, fieldName, value) {
    try {
        if (!extension_settings[extensionName]) extension_settings[extensionName] = {};
        extension_settings[extensionName][fieldName] = value;
        saveMetadataDebounced();
    } catch (_e) { /* noop */ }
}

export function writeExtensionFieldBulk(extensionName, fields = {}) {
    try {
        if (!extension_settings[extensionName]) extension_settings[extensionName] = {};
        if (fields && typeof fields === 'object') {
            Object.assign(extension_settings[extensionName], fields);
        }
        saveMetadataDebounced();
    } catch (_e) { /* noop */ }
}

export function loadExtensionSettings(_settings = {}) {
    try {
        __rikkaRefreshExtensionTypes();
    } catch (_e) { /* noop */ }
}

export function isOfficialExtension(_name) {
    return false;
}

export function cancelDebouncedMetadataSave() {}

export async function runGenerationInterceptors(_chat, _contextSize, _abort, _type) {
    return false;
}

/**
 * 扩展模板渲染（异步与同步兼容）。
 * 真正实现由 runtime.js 挂到 window.renderExtensionTemplateAsync / window.renderTemplateAsync。
 */
export function renderExtensionTemplateAsync(extensionName, templateId, templateData = {}, sanitize = true, localize = true) {
    try {
        const f = window.renderExtensionTemplateAsync;
        if (typeof f === 'function') return f(extensionName, templateId, templateData, sanitize, localize);
    } catch (_e) { /* noop */ }
    return Promise.resolve('');
}

export function renderExtensionTemplate(extensionName, templateId, templateData = {}, sanitize = true, localize = true) {
    try {
        const f = window.renderExtensionTemplate;
        if (typeof f === 'function') return f(extensionName, templateId, templateData, sanitize, localize);
    } catch (_e) { /* noop */ }
    return '';
}

export function renderTemplateAsync(pathOrTemplateId, nameOrData, data) {
    try {
        const f = window.renderTemplateAsync;
        if (typeof f === 'function') return f(pathOrTemplateId, nameOrData, data);
    } catch (_e) { /* noop */ }
    return Promise.resolve('');
}

export function renderTemplate(pathOrTemplateId, nameOrData, data) {
    try {
        const f = window.renderTemplate;
        if (typeof f === 'function') return f(pathOrTemplateId, nameOrData, data);
    } catch (_e) { /* noop */ }
    return '';
}

export function saveMetadataDebounced() {
    try {
        const f = st().saveMetadataDebounced;
        if (typeof f === 'function') f();
    } catch (_e) { /* noop */ }
}

// ---------------------------------------------------------------- 兼容测试钩子（部分扩展/工具链 import）
export function __setExtensionSettings(obj) {
    try {
        Object.assign(extension_settings, obj || {});
    } catch (_e) { /* noop */ }
}

export const __setReplayContext = () => {};

export function openThirdPartyExtensionMenu() {}

/**
 * [v243 BUGFIX C1 + ST 1.19.0 别名支持] 扩展清单查询。
 */
export function getExtensionManifest(extensionName) {
    try {
        const c = st();
        if (c && typeof c.getExtensionManifest === 'function') {
            const m = c.getExtensionManifest(extensionName);
            if (m) return m;
        }
        const list = host().extensions || [];
        const want = String(extensionName == null ? '' : extensionName).trim().toLowerCase();
        if (!want) return null;
        const wantAlias = normalizeFolderAlias(want);
        const norm = (v) => String(v == null ? '' : v).trim().toLowerCase();
        return list.find((e) => e && (
            norm(e.folder) === want ||
            norm(e.name) === want ||
            norm(e.displayName) === want ||
            (wantAlias && normalizeFolderAlias(e.folder) === wantAlias)
        )) || null;
    } catch (_e) { return null; }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export async function deleteExtension(..._args) { return null; }
export async function disableExtension(..._args) { return null; }
export function doDailyExtensionUpdatesCheck(..._args) { return null; }
export async function enableExtension(..._args) { return null; }
export function findExtension(..._args) { return null; }
export function getAuthorFromUrl(..._args) { return null; }
export async function initExtensions(..._args) { return null; }
export async function installExtension(..._args) { return null; }
