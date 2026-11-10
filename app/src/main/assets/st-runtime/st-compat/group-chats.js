/**
 * rikkaST st-compat: group-chats.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/group-chats.js` 的导出面（P0：P2 群聊接线前的安全占位）。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export let selected_group = null;
export let groups = [];
export let is_group_generating = false;

try {
    window.addEventListener('rikka-st-refresh', () => {
        try {
            const c = st();
            if (Array.isArray(c.groups)) groups = c.groups;
            if (c.selected_group !== undefined) selected_group = c.selected_group;
            if (typeof c.is_group_generating === 'boolean') is_group_generating = c.is_group_generating;
        } catch (_e) { /* noop */ }
    });
} catch (_e) { /* noop */ }

export function getGroups() {
    return groups || [];
}

// ── [v202] getGroupMembers 必须【同步】返回数组 ──────────────
// 真机实证：ST-Prompt-Template dist/index.js 里是
//     for (const e of (0, v.getGroupMembers)()) { ... }
// 旧实现写成 `async`，调用得到 Promise → `Promise is not iterable`
// → `TypeError: (0 , v.getGroupMembers) is not a function or its return value is not iterable`
// （后者是 webpack 对迭代失败统一包装后的文案，极具误导性）。
// ST 原版就是同步返回数组，这里必须对齐。
export function getGroupMembers() {
    try {
        const c = st();
        if (typeof c.getGroupMembers === 'function') {
            const r = c.getGroupMembers();
            if (Array.isArray(r)) return r;
        }
        if (Array.isArray(c.groupMembers)) return c.groupMembers;
    } catch (_e) { /* noop */ }
    return [];
}

export async function generateGroupWrapper() {
    return '';
}

// ── [batch16] saveGroupChat ─────────────────────────────────
// 依据：refdeps\st-memory-enhancement\core\manager.js:41-50 用 try/catch 包着调它；
// 但 import 期的缺名错误 try/catch 救不了 → 整条 ESM 图失败。
export async function saveGroupChat(groupId, shouldSave = true) {
    try {
        const c = (window.SillyTavern && window.SillyTavern.getContext) ? window.SillyTavern.getContext() : {};
        if (shouldSave && typeof c.saveChat === 'function') return await c.saveChat();
    } catch (err) {
        console.warn('[group-chats.js] saveGroupChat failed:', err);
    }
    return undefined;
}

// ── [v213] getGroupNames（ST group-chats.js:337）──────────
// JSR createGenerationParametersCompat.ts:74 动态 import 具名读取（fallback 路径）。
export function getGroupNames() {
    try {
        const src = st().groups;
        const list = Array.isArray(src) ? src : groups;
        const names = list
            .filter((g) => g && typeof g === "object" && g.name)
            .map((g) => String(g.name));
        return names;
    } catch (_e) { return []; }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export const DEFAULT_AUTO_MODE_DELAY = 5;
export async function createNewGroupChat(..._args) { return null; }
export async function deleteGroup(..._args) { return null; }
export async function deleteGroupChat(..._args) { return null; }
export async function deleteGroupChatByName(..._args) { return null; }
export async function editGroup(..._args) { return null; }
export function findGroupMemberId(..._args) { return null; }
export function getGroupAvatar(..._args) { return null; }
export function getGroupBlock(..._args) { return null; }
export function getGroupCharacterCards(..._args) { return null; }
export function getGroupCharacterCardsLazy(..._args) { return null; }
export async function getGroupChat(..._args) { return null; }
export function getGroupChatNames(..._args) { return null; }
export function getGroupDepthPrompts(..._args) { return null; }
export async function getGroupPastChats(..._args) { return null; }
export const groupCandidatesFilter = {};
export const groupMembersFilter = {};
export const group_activation_strategy = { NATURAL: 0, LIST: 1, MANUAL: 2, POOLED: 3 };
export let group_generation_id = null;
export const group_generation_mode = { SWAP: 0, APPEND: 1, APPEND_DISABLED: 2 };
export let hideMutedSprites = false;
export async function importGroupChat(..._args) { return null; }
export let is_group_automode_enabled = false;
export async function openGroupById(..._args) { return null; }
export async function openGroupChat(..._args) { return null; }
export let openGroupId = null;
export async function regenerateGroup(..._args) { return null; }
export async function renameGroupChat(..._args) { return null; }
export async function renameGroupMember(..._args) { return null; }
export function resetSelectedGroup(..._args) { return null; }
export async function saveGroupBookmarkChat(..._args) { return null; }
export function select_group_chats(..._args) { return null; }
export async function unshallowGroupMembers(..._args) { return null; }
