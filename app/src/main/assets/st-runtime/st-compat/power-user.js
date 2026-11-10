/**
 * rikkaST st-compat: power-user.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/power-user.js` 的导出面（P0 核心对象 + 常用枚举）。
 * `power_user` 为宽容对象，并与宿主 `extension_settings` (`state.thirdPartySettings`) 双向原位同步持久化：
 * - `st-memory-enhancement` 等插件会将配置写入 `power_user.muyoo_dataTable`、`power_user.IMPORTANT_USER_PRIVACY_DATA`、
 *   `power_user.table_database_templates`、`power_user.table_selected_sheets` 并调用 `saveSettings()`；
 * - 本模块在初始化与 `rikka-st-refresh` 时原位合并（in-place `Object.assign`）上述字段，确保引用恒定且跨页面/重启 100% 持久生效。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

/** Persona 描述插入位置枚举（对齐 ST 语义；数值以 ST1.18 为参照）。 */
const persona_description_positions_enum = {
    IN_PROMPT: 0,
    TOP_AN: 2,
    AFTER_CHAR: 1, // ST personas.js:91（deprecated 别名，v213 对齐）
    IN_CHAT: 1, // 旧别名保留（部分扩展可能已按此名读取）
    BOTTOM_AN: 3,
    AT_DEPTH: 4,
    NONE: 9,
};

export const persona_description_positions = persona_description_positions_enum;

/** Power User 设置（与宿主 extensionSettings 双向原位镜像）。 */
export const power_user = {
    persona_description: '',
    persona_description_position: persona_description_positions_enum.IN_PROMPT,
    persona_description_depth: 2,
    persona_description_role: 0,
    personas: {},
    default_persona: null,
    show_avatar_char_list: true,
    active_character: '',
    active_group: '',
    pinnedGroups: [],
    favorite_characters: [],
    quick_actions: [],
    custom_stopping_strings: '',
    custom_stopping_strings_macro: true,
    always_force_name2: false,
    reasoning: {
        name: 'Think XML',
        auto_parse: false,
        add_to_prompts: false,
        auto_expand: false,
        show_hidden: false,
        prefix: '<think>',
        suffix: '</think>',
        separator: '\n',
        max_additions: 1,
    },
    trim_spaces: true,
    world_info_depth: 2,
    context_size_responsive: false,
    smooth_streaming: false,
    smooth_streaming_speed: 15,
    media_display: 'list',
    message_wide: false,
    message_side: 'right',
    compact_input_area: true,
    auto_connect: true,
};

const PERSISTED_POWER_USER_KEYS = [
    'muyoo_dataTable',
    'IMPORTANT_USER_PRIVACY_DATA',
    'table_database_templates',
    'table_selected_sheets',
    'message_template',
];

export function syncPowerUserFromExtensionSettings() {
    try {
        const ctx = st();
        const ext = (ctx && ctx.extensionSettings) || null;
        if (!ext || typeof ext !== 'object') return;
        const puBucket = (ext.__power_user__ && typeof ext.__power_user__ === 'object') ? ext.__power_user__ : {};
        for (const k of Object.keys(puBucket)) {
            if (puBucket[k] !== undefined && !PERSISTED_POWER_USER_KEYS.includes(k)) {
                power_user[k] = puBucket[k];
            }
        }
        // 兼容直接存储在 st_memory_enhancement 下的配置
        if (ext.st_memory_enhancement && typeof ext.st_memory_enhancement === 'object') {
            if (!ext.muyoo_dataTable || typeof ext.muyoo_dataTable !== 'object') {
                ext.muyoo_dataTable = {};
            }
            Object.assign(ext.muyoo_dataTable, ext.st_memory_enhancement);
        }
        for (const k of PERSISTED_POWER_USER_KEYS) {
            const incoming = ext[k] !== undefined ? ext[k] : puBucket[k];
            if (incoming !== undefined) {
                if (incoming && typeof incoming === 'object' && !Array.isArray(incoming)) {
                    if (!power_user[k] || typeof power_user[k] !== 'object' || Array.isArray(power_user[k])) {
                        power_user[k] = {};
                    }
                    Object.assign(power_user[k], incoming);
                    ext[k] = power_user[k];
                } else {
                    power_user[k] = incoming;
                    ext[k] = incoming;
                }
            } else if (power_user[k] !== undefined) {
                ext[k] = power_user[k];
            }
        }
    } catch (_e) { /* noop */ }
}

export function syncPowerUserToExtensionSettings() {
    try {
        const ctx = st();
        const ext = (ctx && ctx.extensionSettings) || null;
        if (!ext || typeof ext !== 'object') return;
        if (!ext.__power_user__ || typeof ext.__power_user__ !== 'object') {
            ext.__power_user__ = {};
        }
        for (const k of PERSISTED_POWER_USER_KEYS) {
            if (power_user[k] !== undefined) {
                ext[k] = power_user[k];
                ext.__power_user__[k] = power_user[k];
            }
        }
    } catch (_e) { /* noop */ }
}

try {
    window.__rikkaSyncPowerUserFromExt = syncPowerUserFromExtensionSettings;
    window.__rikkaSyncPowerUserToExt = syncPowerUserToExtensionSettings;
    syncPowerUserFromExtensionSettings();
    window.addEventListener('rikka-st-refresh', syncPowerUserFromExtensionSettings);
} catch (_e) { /* noop */ }

export const flushEphemeralStoppingStrings = () => {};

try { if (!power_user.context) power_user.context = {}; } catch (err) { /* noop */ }

/** ST power-user.js:1554：把 settings.power_user 合并进 power_user。 */
export async function loadPowerUserSettings(settings = {}, data = null) {
    try {
        const fromSettings = settings && settings.power_user;
        if (fromSettings && typeof fromSettings === 'object') Object.assign(power_user, fromSettings);
        const fromData = data && data.power_user;
        if (fromData && typeof fromData === 'object') Object.assign(power_user, fromData);
        syncPowerUserFromExtensionSettings();
    } catch (err) {
        console.warn('[power-user.js] loadPowerUserSettings failed:', err);
    }
    return power_user;
}

/** ST power-user.js:1475：应用外观设置。 */
export function applyPowerUserSettings() {
    try {
        if (power_user.reduce_motion) document.body.classList.add('reduce-motion');
        if (power_user.avatar_style !== undefined) document.body.dataset.avatarStyle = String(power_user.avatar_style);
    } catch (err) {
        console.warn('[power-user.js] applyPowerUserSettings failed:', err);
    }
}

/** ST power-user.js:1925：把「全局或上下文」设置摊平成对象。 */
export function getContextSettings() {
    const compiled = {};
    try {
        const ctx = power_user.context || {};
        for (const key of Object.keys(ctx)) compiled[key] = ctx[key];
        for (const key of ['max_context', 'amount_gen', 'max_context_unlocked', 'instruct', 'context', 'sysprompt', 'reasoning']) {
            if (power_user[key] !== undefined) compiled[key] = power_user[key];
        }
    } catch (err) {
        console.warn('[power-user.js] getContextSettings failed:', err);
    }
    return compiled;
}

// Re-export getTokenizerModel from st() context if available (ST 1.19.0)
export function getTokenizerModel() {
    try {
        const c = st();
        if (c && typeof c.getTokenizerModel === 'function') return c.getTokenizerModel();
    } catch (_e) { /* noop */ }
    return '';
}

// ---- [ST 1.19.0 Full Export Parity] ----
export const MAX_CONTEXT_DEFAULT = 8192;
export const MAX_RESPONSE_DEFAULT = 2048;
export function addEphemeralStoppingString(..._args) { return null; }
export function applyStylePins(..._args) { return null; }
export const chat_styles = { DEFAULT: 0, BUBBLES: 1, DOCUMENT: 2 };
export function collapseNewlines(..._args) { return null; }
export let context_presets = [];
export function fixMarkdown(..._args) { return null; }
export function forceCharacterEditorTokenize(..._args) { return null; }
export function fuzzySearchCharacters(..._args) { return null; }
export function fuzzySearchGroups(..._args) { return null; }
export function fuzzySearchPersonas(..._args) { return null; }
export function fuzzySearchTags(..._args) { return null; }
export function fuzzySearchWorldInfo(..._args) { return null; }
export function generatedTextFiltered(..._args) { return null; }
export function getCustomStoppingStrings(..._args) { return null; }
export function getThemeObject(..._args) { return null; }
export function loadMovingUIState(..._args) { return null; }
export function performFuzzySearch(..._args) { return null; }
export function playMessageSound(..._args) { return null; }
export function registerDebugFunction(..._args) { return null; }
export function renderStoryString(..._args) { return null; }
export function resetMovableStyles(..._args) { return null; }
export const send_on_enter_options = { DISABLED: -1, AUTO: 0, ENABLED: 1 };
export function sortEntitiesList(..._args) { return null; }
export const toastPositionClasses = ['toast-top-left', 'toast-top-center', 'toast-top-right', 'toast-bottom-left', 'toast-bottom-center', 'toast-bottom-right'];
