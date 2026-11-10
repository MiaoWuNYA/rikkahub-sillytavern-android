/**
 * Tavern Runtime — 脚本按钮 Shim（per-script + 宿主上报）
 *
 * 对齐 JSR 语义：每个脚本（iframe）拥有独立的按钮集与按钮事件名；
 * 注册/变更时通过 window.RikkaBridge.onScriptButtons 上报 Kotlin 宿主，
 * 由聊天界面统一渲染按钮；点击经 __rikkaButtonHub.fire 派发回脚本事件。
 */

import { eventEmit } from './event-shims.js';
import { deepClone } from './clone-util.js';

/** scriptId -> { scriptName, buttons: [{name, visible}], events: Map<name, eventName> } */
const _store = new Map();

function _ensure(scriptId, scriptName) {
    let entry = _store.get(scriptId);
    if (!entry) {
        entry = { scriptName: String(scriptName || scriptId), buttons: [], events: new Map() };
        _store.set(scriptId, entry);
    }
    return entry;
}

function _simpleHash(str) {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
        const char = str.charCodeAt(i);
        hash = ((hash << 5) - hash) + char;
        hash |= 0;
    }
    return Math.abs(hash).toString(36);
}

function _eventNameOf(scriptId, entry, name) {
    if (!entry.events.has(name)) {
        entry.events.set(name, `rikka_button_${_simpleHash(String(scriptId) + '::' + name)}`);
    }
    return entry.events.get(name);
}

function _normalize(buttons) {
    return (buttons || [])
        .filter(b => b && typeof b.name === 'string')
        .map(b => ({ name: b.name, visible: b.visible !== false }));
}

// ── 核心 API（带 scriptId 上下文）──

export function appendButtons(scriptId, scriptName, buttons) {
    const entry = _ensure(scriptId, scriptName);
    for (const btn of _normalize(buttons)) {
        if (!entry.buttons.some(b => b.name === btn.name)) {
            entry.buttons.push(btn);
        }
    }
    _notify();
    return deepClone(entry.buttons);
}

export function replaceButtons(scriptId, scriptName, buttons) {
    const entry = _ensure(scriptId, scriptName);
    entry.buttons = _normalize(buttons);
    _notify();
    return deepClone(entry.buttons);
}

export function updateButtons(scriptId, scriptName, updater) {
    const entry = _ensure(scriptId, scriptName);
    let next;
    try {
        next = updater(deepClone(entry.buttons));
    } catch (e) {
        next = entry.buttons;
    }
    entry.buttons = _normalize(next);
    _notify();
    return deepClone(entry.buttons);
}

export function listButtons(scriptId) {
    const entry = _store.get(scriptId);
    return entry ? deepClone(entry.buttons) : [];
}

export function buttonEvent(scriptId, name) {
    const entry = _ensure(scriptId, String(scriptId));
    return _eventNameOf(scriptId, entry, String(name));
}

export function fireButton(scriptId, name) {
    // [batch17] 点击链路埋点：主页面里到底认不认得这个 scriptId
    try {
        if (window.RikkaBridge && RikkaBridge.log) {
            RikkaBridge.log('debug', '[buttons] fire ' + scriptId + '::' + name +
                ' known=' + _store.has(scriptId) + ' owner=' + String(window.__rikkaOwnerTag || 'main'));
        }
    } catch (_e) { /* noop */ }
    const evt = buttonEvent(scriptId, name);
    try {
        eventEmit(evt);
        return true;
    } catch (e) {
        return false;
    }
}

/** 汇总全部脚本的可见按钮（供宿主渲染）。 */
export function allEnabledButtons() {
    const out = [];
    _store.forEach((entry, scriptId) => {
        for (const btn of entry.buttons) {
            if (btn.visible === false) continue;
            out.push({
                scriptId: String(scriptId),
                scriptName: entry.scriptName,
                name: btn.name,
                event: _eventNameOf(scriptId, entry, btn.name),
            });
        }
    });
    return out;
}

function _notify() {
    try {
        const payload = JSON.stringify(allEnabledButtons());
        if (window.RikkaBridge && typeof window.RikkaBridge.onScriptButtons === 'function') {
            window.RikkaBridge.onScriptButtons(payload);
        }
    } catch (e) { /* noop */ }
    try { _renderLegacy(); } catch (e) { /* noop */ }
}

// ── 兼容导出（无 scriptId 上下文时的全局兜底；脚本 iframe 内由 predefine 覆盖为 per-script 版本）──

export function getScriptButtons() {
    return allEnabledButtons();
}

export function replaceScriptButtons(buttons) {
    return replaceButtons('__global__', '__global__', buttons);
}

export function updateScriptButtonsWith(updater) {
    return updateButtons('__global__', '__global__', updater);
}

export function appendInexistentScriptButtons(buttons) {
    return appendButtons('__global__', '__global__', buttons);
}

export function getButtonEvent(buttonName) {
    return buttonEvent('__global__', buttonName);
}

// ── 遗留容器渲染（若 runtime 页面存在 #mvu-standalone-buttons 则同步显示）──

function _renderLegacy() {
    const container = document.getElementById('mvu-standalone-buttons');
    if (!container) return;
    container.innerHTML = '';
    for (const item of allEnabledButtons()) {
        const el = document.createElement('div');
        el.className = 'menu_button';
        el.textContent = item.name;
        el.addEventListener('click', () => fireButton(item.scriptId, item.name));
        container.appendChild(el);
    }
}

// ── 宿主 Hub（挂在 window 上，脚本 iframe 经 parent 访问）──

export const buttonHub = {
    append: appendButtons,
    replace: replaceButtons,
    update: updateButtons,
    list: listButtons,
    event: buttonEvent,
    fire: fireButton,
    all: allEnabledButtons,
    notify: _notify,
};

try { window.__rikkaButtonHub = buttonHub; } catch (e) { /* noop */ }

