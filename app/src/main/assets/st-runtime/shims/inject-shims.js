/**
 * rikkaST shim: inject-shims.js  [v241 + Dynamic Filter Evaluation]
 * ============================================================
 * JSR（酒馆助手）`injectPrompts` / `uninjectPrompts` —— 注入提示词。
 *
 * 完整支持：
 * - `injectPrompts(prompts, { once })` → `{ uninject() }`
 * - `uninjectPrompts(ids)`
 * - `position: 'in_chat'` 插入聊天并发送；`'none'` 不发送，仅 `should_scan` 参与世界书扫描
 * - `once: true` 只影响下一次生成
 * - `filter?: () => boolean | Promise<boolean>` 动态过滤回调：在注册时、事件触发时及生成前实时求值，
 *   经 `RikkaBridge.updateInjectFilterResults(json)` 同步至宿主 `InjectedPromptStore`。
 */

function hostBridge() {
    try {
        if (window.RikkaBridge && typeof window.RikkaBridge.injectPrompts === 'function') {
            return window.RikkaBridge;
        }
        if (window.parent && window.parent !== window && window.parent.RikkaBridge &&
            typeof window.parent.RikkaBridge.injectPrompts === 'function') {
            return window.parent.RikkaBridge;
        }
    } catch (_e) { /* noop */ }
    return null;
}

const activeInjections = new Map();
const filterCallbacks = new Map();

function evalFilterSync(fn) {
    if (typeof fn !== 'function') return { isAsync: false, passed: true };
    try {
        const res = fn();
        if (res && typeof res.then === 'function') {
            return { isAsync: true, promise: res.then((v) => !!v).catch(() => false), passed: true };
        }
        return { isAsync: false, passed: !!res };
    } catch (_e) {
        return { isAsync: false, passed: false };
    }
}

export async function evaluateAllInjectFilters(seq) {
    const results = {};
    try {
        for (const [id, fn] of filterCallbacks.entries()) {
            try {
                const r = await fn();
                results[id] = !!r;
            } catch (_e) {
                results[id] = false;
            }
            const entry = activeInjections.get(id);
            if (entry) entry.filterPassed = results[id];
        }
        try {
            const bridge = hostBridge();
            if (bridge && typeof bridge.updateInjectFilterResults === 'function' && Object.keys(results).length > 0) {
                bridge.updateInjectFilterResults(JSON.stringify(results));
            }
        } catch (_e) { /* noop */ }
    } finally {
        if (seq !== undefined && seq !== null) {
            try {
                const bridge = hostBridge();
                if (bridge && typeof bridge.onInjectFiltersEvaluated === 'function') {
                    bridge.onInjectFiltersEvaluated(Number(seq));
                }
            } catch (_e) { /* noop */ }
        }
    }
    return JSON.stringify(results);
}

try {
    if (typeof window !== 'undefined') {
        window.__rikkaEvaluateAllInjectFilters = evaluateAllInjectFilters;
    }
} catch (_e) { /* noop */ }

function normalizePrompt(raw, once) {
    if (!raw || typeof raw !== 'object') return null;
    const id = String(raw.id == null ? '' : raw.id).trim();
    if (!id) return null;
    const depthRaw = Number(raw.depth);
    const hasFilter = typeof raw.filter === 'function';
    let filterPassed = true;
    if (hasFilter) {
        filterCallbacks.set(id, raw.filter);
        const syncEval = evalFilterSync(raw.filter);
        filterPassed = syncEval.passed;
        if (syncEval.isAsync && syncEval.promise) {
            syncEval.promise.then((passed) => {
                const existing = activeInjections.get(id);
                if (existing) existing.filterPassed = passed;
                try {
                    const bridge = hostBridge();
                    if (bridge && typeof bridge.updateInjectFilterResults === 'function') {
                        bridge.updateInjectFilterResults(JSON.stringify({ [id]: passed }));
                    }
                } catch (_e) { /* noop */ }
            });
        }
    } else {
        filterCallbacks.delete(id);
    }
    return {
        id,
        position: raw.position === 'none' ? 'none' : 'in_chat',
        depth: Number.isFinite(depthRaw) && depthRaw >= 0 ? Math.floor(depthRaw) : 0,
        role: (raw.role === 'user' || raw.role === 'assistant') ? raw.role : 'system',
        content: String(raw.content == null ? '' : raw.content),
        shouldScan: !!raw.should_scan,
        once: !!once,
        hasFilter,
        filterPassed,
    };
}

export function injectPrompts(prompts, options) {
    const once = !!(options && options.once);
    const list = Array.isArray(prompts) ? prompts : [prompts];
    const normalized = list.map((p) => normalizePrompt(p, once)).filter(Boolean);
    normalized.forEach((p) => activeInjections.set(p.id, p));
    try {
        const bridge = hostBridge();
        if (bridge) bridge.injectPrompts(JSON.stringify(normalized));
    } catch (e) {
        console.warn('[injectPrompts] host bridge failed:', e);
    }
    let deleted = false;
    const uninject = () => {
        if (deleted) return;
        deleted = true;
        uninjectPrompts(normalized.map((p) => p.id));
    };
    return { uninject };
}

export function uninjectPrompts(ids) {
    const list = (Array.isArray(ids) ? ids : [ids])
        .map((id) => String(id == null ? '' : id).trim())
        .filter(Boolean);
    list.forEach((id) => {
        activeInjections.delete(id);
        filterCallbacks.delete(id);
    });
    try {
        const bridge = hostBridge();
        if (bridge) bridge.uninjectPrompts(JSON.stringify(list));
    } catch (e) {
        console.warn('[uninjectPrompts] host bridge failed:', e);
    }
}
