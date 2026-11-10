/**
 * rikkaST st-compat: slash-commands/SlashCommandCommonEnumsProvider.js
 * ============================================================
 * 模拟 ST1.18 `commonEnumProviders` / `enumIcons`。
 *
 * ★ v201 关键修复（酒馆助手「点开黑屏」真正根因）
 * JSR(酒馆助手) 4.11.x 在 `initSlashCommands()` 里调用
 *     commonEnumProviders.boolean('trueFalse')()      // 注意：工厂调用后还要再 `()` 一次
 * 而旧 shim 只导出了 characters/groups/personas 三个 provider（且直接返回数组），
 * 于是 `commonEnumProviders.boolean` 是 undefined → `nn.boolean is not a function`
 * → 异常发生在 `$(function(){ … initSlashCommands(); app.mount($app[0]); })`
 *   这个 jQuery ready 回调里，**`app.mount()` 根本没被执行** → 设置页只剩深色背景 = 黑屏。
 * （真机日志实证：`jQuery.Deferred exception: nn.boolean is not a function`）
 *
 * ST 的契约：每个 provider 是「返回 enum 提供函数的工厂」，即
 *     typeof commonEnumProviders.boolean('trueFalse') === 'function'
 *     commonEnumProviders.boolean('trueFalse')() === SlashCommandEnumValue[]
 * 每个 provider 都返回一个新数组（不是同一个数组引用），避免共享可变状态。
 */

import { SlashCommandEnumValue } from './SlashCommandEnumValue.js';

function makeEnumValue(value, description, icon) {
    const v = new SlashCommandEnumValue(value, description);
    if (icon !== undefined) v.icon = icon;
    return v;
}

/** ST 内部：把 ['trueFalse', …] 这类标签串归一化成真正的 enum 值对。 */
function normalizePair(label, fallbackA, fallbackB) {
    const s = String(label == null ? '' : label);
    const lower = s.toLowerCase();

    if (lower.includes('truefalse') || /^(boolean|bool)/.test(lower)) {
        return ['true', 'false'];
    }
    if (lower.includes('yesno') || lower.includes('yes/no')) {
        return ['yes', 'no'];
    }
    if (lower.includes('onoff') || lower.includes('on/off')) {
        return ['on', 'off'];
    }
    if (lower === '10' || lower === '0/1' || lower === 'zeroone' || lower === 'zero/one') {
        return ['1', '0'];
    }
    // 'a|b' / 'a,b' / 'a/b' 形态
    const parts = s.split(/[|,\/]/).map((t) => t.trim()).filter(Boolean);
    if (parts.length >= 2) return [parts[0], parts[1]];
    if (s) return [s, s];
    return [fallbackA, fallbackB];
}

function pairProvider(label, fallback, icon) {
    return () => {
        const pair = normalizePair(label, fallback[0], fallback[1]);
        return [
            makeEnumValue(pair[0], pair[0], icon),
            makeEnumValue(pair[1], pair[1], icon),
        ];
    };
}

function listProvider(values, icon) {
    return () => values.map((v) => makeEnumValue(v, v, icon));
}

export const commonEnumProviders = {
    /** @see ST scripts/slash-commands/SlashCommandCommonEnumsProvider.js */
    boolean: (label) => pairProvider(label, ['true', 'false'], 'check'),

    number: () => listProvider([], 'hashtag'),
    string: () => listProvider([], 'font'),
    enum: () => listProvider([], 'list'),

    // 这些依赖宿主数据（角色 / 群组 / 人格 / 世界书），
    // 由 runtime 在拿到真实数据后经 `__rikkaSetCommonEnumSource` 注入。
    characters: () => {
        const src = getCommonEnumSource().characters || [];
        return Array.isArray(src) ? src.slice() : [];
    },
    groups: () => {
        const src = getCommonEnumSource().groups || [];
        return Array.isArray(src) ? src.slice() : [];
    },
    personas: () => {
        const src = getCommonEnumSource().personas || [];
        return Array.isArray(src) ? src.slice() : [];
    },
    worldInfos: () => {
        const src = getCommonEnumSource().worldInfos || [];
        return Array.isArray(src) ? src.slice() : [];
    },
};

let __commonEnumSource = {};
function getCommonEnumSource() {
    return __commonEnumSource && typeof __commonEnumSource === 'object' ? __commonEnumSource : {};
}

/** 供 runtime 注入真实角色/群组/人格列表（可选）。 */
export function __rikkaSetCommonEnumSource(source) {
    __commonEnumSource = source && typeof source === 'object' ? source : {};
}

export const enumIcons = {
    meta: 'diamond',
    enum: 'list',
    variable: 'square-root-variable',
    closure: 'code',
    subcommand: 'terminal',
    file: 'file',
    check: 'check',
    hashtag: 'hashtag',
    font: 'font',
    list: 'list',
    world: 'globe',
    character: 'user',
    group: 'users',
    persona: 'user-pen',
};
export const commonEnumMatchProviders = {};
