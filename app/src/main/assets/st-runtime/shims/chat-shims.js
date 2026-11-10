/**
 * rikkaST Runtime — 消息读写 shim（chat-shims.js）
 * ============================================================
 * 对齐 JS-Slash-Runner `src/function/chat_message.ts`：
 * - `getChatMessages(range, options)`
 * - `setChatMessages(messages, options)`
 * - `setChatMessage(field_values, message_id, options)`
 * - `createChatMessages(messages, options)`
 * - `deleteChatMessages(message_ids, options)`
 * - `rotateChatMessages(begin, middle, end, options)`
 */

import { tavern_events, EVENT_NAMES } from './constants.js';
import { deepClone } from './clone-util.js';

function getRuntime() {
    return window.__rikkaSt || {};
}

function getBridge() {
    return window.RikkaBridge || null;
}

function getRawChatArray() {
    const rt = getRuntime();
    if (typeof rt.getChatArray === 'function') {
        return rt.getChatArray();
    }
    const ctx = window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : null;
    return (ctx && Array.isArray(ctx.chat)) ? ctx.chat : [];
}

function parseRangeIndices(range, totalLength) {
    if (totalLength <= 0) return [];
    const resolveIdx = (n) => {
        const idx = Number(n);
        if (!Number.isInteger(idx)) return -1;
        return idx < 0 ? totalLength + idx : idx;
    };
    if (range === undefined || range === null || range === '' || range === 'all') {
        return Array.from({ length: totalLength }, (_, i) => i);
    }
    if (typeof range === 'number' || (typeof range === 'string' && /^-?\d+$/.test(range.trim()))) {
        const i = resolveIdx(Number(range));
        return (i >= 0 && i < totalLength) ? [i] : [];
    }
    if (typeof range === 'string') {
        const m = range.trim().match(/^(-?\d+)\s*-\s*(-?\d+)$/);
        if (m) {
            let start = resolveIdx(Number(m[1]));
            let end = resolveIdx(Number(m[2]));
            start = Math.max(0, Math.min(totalLength - 1, start));
            end = Math.max(0, Math.min(totalLength - 1, end));
            if (start > end) {
                const tmp = start;
                start = end;
                end = tmp;
            }
            const out = [];
            for (let i = start; i <= end; i++) out.push(i);
            return out;
        }
    }
    if (Array.isArray(range)) {
        const set = new Set();
        for (const item of range) {
            for (const idx of parseRangeIndices(item, totalLength)) {
                set.add(idx);
            }
        }
        return Array.from(set).sort((a, b) => a - b);
    }
    return [];
}

function toJsrChatMessage(raw, index, includeSwipes = false) {
    if (!raw || typeof raw !== 'object') return null;
    const role = raw.is_user ? 'user' : (raw.is_system ? 'system' : 'assistant');
    const swipes = Array.isArray(raw.swipes) && raw.swipes.length > 0
        ? raw.swipes.map((s) => String(s ?? ''))
        : [String(raw.mes ?? '')];
    const swipeId = typeof raw.swipe_id === 'number'
        ? Math.max(0, Math.min(swipes.length - 1, raw.swipe_id))
        : 0;
    const rt = getRuntime();
    const msgVars = (typeof rt.getMessageVariablesAt === 'function')
        ? rt.getMessageVariablesAt(index)
        : {};

    if (includeSwipes) {
        // [BUGFIX F7] MVU 的「切换聊天后重新初始化」会读 `t.swipes_data[o]`
        // （mvu/bundle.js 里 `let r=(klona)(t.swipes_data[o])`）。
        // 旧实现只返回 `data`（单条 swipes 的变量），没有 `swipes_data` 这个键 →
        // `undefined[0]` 抛 `TypeError: Cannot read properties of undefined (reading '0')`，
        // 被 MVU 的 catch 吞成一条 toast「[MVU]重新初始化失败」，于是每次进软件都弹一次。
        // 语义对齐 JSR：`swipes_data` = 每个 swipe 各自的变量数组（与 `swipes` 一一对应），
        // `data` 保留原含义（当前 swipe 的变量）。两者都提供，旧消费方不受影响。
        const swipesData = swipes.map((_, sIdx) => (sIdx === swipeId ? deepClone(msgVars) : {}));
        return {
            message_id: index,
            name: String(raw.name ?? ''),
            role,
            is_hidden: Boolean(raw.is_system),
            swipe_id: swipeId,
            swipes: deepClone(swipes),
            swipes_data: deepClone(swipesData),
            data: deepClone(swipesData),
            extra: deepClone(raw.extra || {}),
        };
    }

    return {
        message_id: index,
        name: String(raw.name ?? ''),
        role,
        is_hidden: Boolean(raw.is_system),
        message: String(swipes[swipeId] ?? raw.mes ?? ''),
        data: deepClone(msgVars),
        extra: deepClone(raw.extra || {}),
    };
}

export function getChatMessages(range = '0-{{lastMessageId}}', options = {}) {
    const opts = (options && typeof options === 'object') ? options : {};
    const rawChat = getRawChatArray();
    const total = rawChat.length;
    if (total === 0) return [];

    let resolvedRange = range;
    if (typeof resolvedRange === 'string' && resolvedRange.includes('{{lastMessageId}}')) {
        resolvedRange = resolvedRange.replace(/\{\{\s*lastMessageId\s*\}\}/gi, String(total - 1));
    }

    const indices = parseRangeIndices(resolvedRange, total);
    const includeSwipes = Boolean(opts.include_swipes);
    const roleFilter = opts.role ? String(opts.role).toLowerCase() : 'all';
    const hideState = opts.hide_state ? String(opts.hide_state).toLowerCase() : 'all';

    const result = [];
    for (const idx of indices) {
        const msg = toJsrChatMessage(rawChat[idx], idx, includeSwipes);
        if (!msg) continue;
        if (roleFilter !== 'all' && msg.role !== roleFilter) continue;
        if (hideState === 'hidden' && !msg.is_hidden) continue;
        if (hideState === 'unhidden' && msg.is_hidden) continue;
        result.push(msg);
    }
    return result;
}

export async function setChatMessages(messages, options = {}) {
    if (!Array.isArray(messages) || messages.length === 0) return;
    const rawChat = getRawChatArray();
    const rt = getRuntime();
    const bridge = getBridge();
    const updates = [];

    for (const m of messages) {
        if (!m || typeof m !== 'object') continue;
        const rawId = m.message_id !== undefined ? Number(m.message_id) : Number(m.id);
        const id = rawId < 0 ? rawChat.length + rawId : rawId;
        if (!Number.isInteger(id) || id < 0 || id >= rawChat.length) continue;

        const existing = rawChat[id] || {};
        const patch = { message_id: id };
        let hasPatch = false;

        let effectiveSwipes = Array.isArray(existing.swipes) ? existing.swipes.slice() : [String(existing.mes ?? '')];
        if (id === 0 && effectiveSwipes.length <= 1) {
            const ctx = window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : null;
            const charObj = ctx && Array.isArray(ctx.characters) ? ctx.characters[ctx.characterId || 0] : null;
            const altGreetings = charObj && charObj.data && Array.isArray(charObj.data.alternate_greetings)
                ? charObj.data.alternate_greetings
                : [];
            if (altGreetings.length > 0) {
                effectiveSwipes = [String(existing.mes ?? '')].concat(altGreetings.map((g) => String(g ?? '')));
                existing.swipes = effectiveSwipes.slice();
            }
        }

        if (Array.isArray(m.swipes) && m.swipes.length > 0) {
            patch.swipes = m.swipes.map((s) => String(s ?? ''));
            const sId = typeof m.swipe_id === 'number'
                ? Math.max(0, Math.min(patch.swipes.length - 1, m.swipe_id))
                : (typeof existing.swipe_id === 'number' ? existing.swipe_id : 0);
            patch.swipe_id = sId;
            patch.mes = patch.swipes[sId];
            existing.swipes = patch.swipes;
            existing.swipe_id = sId;
            existing.mes = patch.mes;
            hasPatch = true;
        } else if (m.message !== undefined || m.mes !== undefined) {
            const newText = String(m.message !== undefined ? m.message : m.mes);
            const sId = typeof m.swipe_id === 'number'
                ? Math.max(0, m.swipe_id)
                : (typeof existing.swipe_id === 'number' ? existing.swipe_id : 0);
            if (effectiveSwipes.length > sId) {
                effectiveSwipes[sId] = newText;
                patch.swipes = effectiveSwipes;
            }
            patch.swipe_id = sId;
            patch.mes = newText;
            existing.mes = newText;
            existing.swipe_id = sId;
            hasPatch = true;
        } else if (typeof m.swipe_id === 'number' && Number.isFinite(m.swipe_id)) {
            const sId = Math.max(0, Math.floor(m.swipe_id));
            patch.swipe_id = sId;
            if (effectiveSwipes.length > 1) {
                const clamped = Math.min(effectiveSwipes.length - 1, sId);
                patch.swipe_id = clamped;
                patch.swipes = effectiveSwipes;
                patch.mes = String(effectiveSwipes[clamped] ?? '');
                existing.mes = patch.mes;
            }
            existing.swipe_id = patch.swipe_id;
            hasPatch = true;
        }

        if (typeof m.is_hidden === 'boolean' || typeof m.is_system === 'boolean') {
            patch.is_system = typeof m.is_hidden === 'boolean' ? m.is_hidden : m.is_system;
            existing.is_system = patch.is_system;
            hasPatch = true;
        }
        if (typeof m.name === 'string' && m.name.trim()) {
            patch.name = m.name;
            existing.name = m.name;
            hasPatch = true;
        }

        if (m.data !== undefined && typeof rt.setMessageVariablesAt === 'function') {
            const sId = typeof patch.swipe_id === 'number'
                ? patch.swipe_id
                : (typeof existing.swipe_id === 'number' ? existing.swipe_id : 0);
            const dataPayload = Array.isArray(m.data) ? (m.data[sId] || m.data[0] || {}) : m.data;
            if (dataPayload && typeof dataPayload === 'object') {
                rt.setMessageVariablesAt(id, dataPayload);
            }
        }

        // [诊断 F7] MVU 的 initvar 会写 `swipes_data`（多 swipe 各自的变量数组），
        // 但本 shim 的写路径目前只认 `data`（单 swipe），也没有 `rt.setMessageVariablesAt`。
        // 这里**不擅自改写**（整份 messageVars 覆写有数据风险），只把它显式记进日志，
        // 避免「调用方以为写成功了、实际被静默丢弃」这种最难查的失败。
        if (m.swipes_data !== undefined && !hasPatch) {
            try {
                console.warn(
                    '[chat-shims] setChatMessages: swipes_data 未被写入（写路径暂不支持多 swipe 变量）' +
                    ' message_id=' + id + ' swipes=' + (Array.isArray(m.swipes_data) ? m.swipes_data.length : '?'),
                );
            } catch (_e) { /* noop */ }
        }

        if (hasPatch) {
            updates.push(patch);
        }
    }

    if (updates.length > 0 && bridge && typeof bridge.updateChatMessages === 'function') {
        try {
            bridge.updateChatMessages(JSON.stringify(updates));
        } catch (_e) { /* ignore */ }
    }

    const es = rt.eventSource || (window.SillyTavern && window.SillyTavern.getContext && window.SillyTavern.getContext().eventSource);
    if (options?.refresh !== 'none' && es && typeof es.emit === 'function') {
        for (const u of updates) {
            try {
                es.emit(EVENT_NAMES.MESSAGE_UPDATED || tavern_events.MESSAGE_UPDATED, u.message_id);
                if (typeof u.swipe_id === 'number') {
                    es.emit(EVENT_NAMES.MESSAGE_SWIPED || tavern_events.MESSAGE_SWIPED, u.message_id);
                }
            } catch (_e) { /* ignore */ }
        }
    }
}

/**
 * 兼容旧版 JSR 单楼层更新接口：
 * - `setChatMessage("new text", 0)`
 * - `setChatMessage({ message: "new text", swipe_id: 1, data: {...} }, 0)`
 * - `setChatMessage({ swipe_id: 2 }, 0)`
 */
export async function setChatMessage(field_values, message_id = 0, options = {}) {
    if (typeof field_values === 'string') {
        return setChatMessages([{ message_id, message: field_values }], options);
    }
    if (!field_values || typeof field_values !== 'object') return;
    const text = field_values.message !== undefined ? field_values.message : field_values.mes;
    const hasData = field_values.data !== undefined;
    const hasSwipe = typeof field_values.swipe_id === 'number' || Array.isArray(field_values.swipes);
    const hasMeta = typeof field_values.is_hidden === 'boolean' ||
        typeof field_values.is_system === 'boolean' ||
        typeof field_values.name === 'string';
    if (text === undefined && !hasData && !hasSwipe && !hasMeta) return;
    return setChatMessages([{ ...field_values, message_id }], options);
}

export async function createChatMessages(messages, options = {}) {
    if (!Array.isArray(messages) || messages.length === 0) return;
    const bridge = getBridge();
    const rt = getRuntime();
    const payload = messages.map((m) => ({
        role: String(m?.role || 'assistant').toLowerCase(),
        message: String(m?.message ?? m?.mes ?? ''),
        name: m?.name ? String(m.name) : undefined,
        data: (m?.data && typeof m.data === 'object' && !Array.isArray(m.data)) ? m.data : undefined,
    }));
    if (bridge && typeof bridge.insertChatMessages === 'function') {
        try {
            bridge.insertChatMessages(JSON.stringify(payload));
        } catch (_e) { /* ignore */ }
    }
    if (typeof rt.refreshAll === 'function') {
        try { rt.refreshAll(); } catch (_e) { /* ignore */ }
    }
}

export async function deleteChatMessages(message_ids, options = {}) {
    const rawChat = getRawChatArray();
    const ids = parseRangeIndices(message_ids, rawChat.length);
    if (ids.length === 0) return;
    const bridge = getBridge();
    const rt = getRuntime();
    if (bridge && typeof bridge.deleteChatMessages === 'function') {
        try {
            bridge.deleteChatMessages(JSON.stringify(ids));
        } catch (_e) { /* ignore */ }
    }
    if (typeof rt.refreshAll === 'function') {
        try { rt.refreshAll(); } catch (_e) { /* ignore */ }
    }
}

export async function rotateChatMessages(begin, middle, end, options = {}) {
    const rawChat = getRawChatArray();
    const len = rawChat.length;
    if (len === 0) return;
    const b = begin < 0 ? len + begin : Number(begin);
    const m = middle < 0 ? len + middle : Number(middle);
    const e = end < 0 ? len + end : Number(end);
    if (!Number.isInteger(b) || !Number.isInteger(m) || !Number.isInteger(e)) return;
    if (b < 0 || b >= m || m >= e || e > len) return;

    const slice = getChatMessages(`${b}-${e - 1}`, { include_swipes: true });
    const offset = m - b;
    const rotated = slice.slice(offset).concat(slice.slice(0, offset));
    const updates = rotated.map((item, idx) => ({
        message_id: b + idx,
        name: item.name,
        is_hidden: item.is_hidden,
        swipe_id: item.swipe_id,
        swipes: item.swipes,
        data: item.data,
    }));
    await setChatMessages(updates, options);
}
