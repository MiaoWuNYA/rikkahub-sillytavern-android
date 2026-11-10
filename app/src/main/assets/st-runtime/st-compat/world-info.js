/**
 * rikkaST st-compat: world-info.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/world-info.js` 的导出面。
 * 读路径（loadWorldInfo）转发到宿主（卡内嵌世界书）；写路径 P0 为 no-op。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

// ---------------------------------------------------------------- 数据（live）
// [v208] ST 契约（world-info.js:65-68）：
//   world_info 是【对象】{ globalSelect: string[], charLore: [...] }，不是数组；
//   world_names / selected_world_info 是 string[]。
// 此前三者恒为空/形态错 → ST-PT worldinfo.ts:660 把所有书过滤光（WI 功能静默全灭）、
//   JSR lorebook.ts:88 读 world_info_settings.world_info.globalSelect 直接 TypeError。
export let world_info = { globalSelect: [], charLore: [] };
export let world_names = [];
export let selected_world_info = [];

function __refreshWorldInfoData() {
    const c = st();
    // selected_world_info：优先取 ctx 的 selected_world_info，其次 world_info.globalSelect
    if (Array.isArray(c.selected_world_info) && c.selected_world_info.length) {
        selected_world_info = c.selected_world_info;
    } else if (c.world_info && Array.isArray(c.world_info.globalSelect)) {
        selected_world_info = c.world_info.globalSelect;
    }
    if (c.world_info && typeof c.world_info === 'object' && !Array.isArray(c.world_info)) {
        world_info = c.world_info;
        if (!Array.isArray(world_info.globalSelect)) world_info.globalSelect = selected_world_info.slice();
        if (!Array.isArray(world_info.charLore)) world_info.charLore = [];
    }
    if (Array.isArray(c.world_names)) world_names = c.world_names;
}

try {
    window.addEventListener('rikka-st-refresh', () => { try { __refreshWorldInfoData(); } catch (_e) { /* noop */ } });
    __refreshWorldInfoData();
} catch (_e) { /* noop */ }

// ---------------------------------------------------------------- 常量（对齐 ST1.18）
// [v208] 对齐 ST 真源（world-info.js:33-38）：{AND_ANY:0, NOT_ALL:1, NOT_ANY:2, AND_ALL:3}。
// 此前 1/2/3 互换 → ST-PT worldinfo.ts:563-567 的 switch 把 NOT_ALL 走进 AND_ALL 分支，激活条件相反。
// 与宿主 TavernCard.kt:77 的官方注释（0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL）也终于一致。
export const world_info_logic = { AND_ANY: 0, NOT_ALL: 1, NOT_ANY: 2, AND_ALL: 3 };
export const world_info_position = {
    before: 0,
    after: 1,
    ANTop: 2,
    ANBottom: 3,
    atDepth: 4,
    EMTop: 5,
    EMBottom: 6,
    // [v208] ST world-info.js:855-864 有 outlet:7（JSR replay.ts:101 的 outlet 分桶靠它）
    outlet: 7,
};

// [v208] ST world-info.js:27-31（此前缺失）
export const world_info_insertion_strategy = { evenly: 0, character_first: 1, global_first: 2 };

// [v208] ST world-info.js:98（此前缺失）
export const MAX_SCAN_DEPTH = 1000;
export const wi_anchor_position = { before: 0, after: 1 };
export const DEFAULT_DEPTH = 4;
export const DEFAULT_WEIGHT = 100;
export const METADATA_KEY = 'world_info';

// ---------------------------------------------------------------- 设置（静态近似）
export const world_info_include_names = true;
export const world_info_max_recursion_steps = 0;
export const world_info_case_sensitive = false;
export const world_info_match_whole_words = false;
export const world_info_use_group_scoring = false;

export function getWorldInfoSettings() {
    try { __refreshWorldInfoData(); } catch (_e) { /* noop */ }
    // [v208] 对齐 ST getWorldInfoSettings（world-info.js:795-812，14 键）：
    //  ① 补 world_info（对象本体，含 globalSelect/charLore）与 world_info_budget_cap —— 缺它们
    //     JSR lorebook.ts:88 `(settings.world_info as {globalSelect}).globalSelect` 直接 TypeError（P0-2）；
    //  ② world_info_depth 用 ST 全局默认 2（ST 69），不是 entry 的 DEFAULT_DEPTH(4)
    //     （JSR lorebook.ts:90 拿它当 scan_depth，此前一直是 4，P1-5）。
    return {
        world_info,
        world_info_budget_cap: 0,
        world_info_depth: 2,
        world_info_budget: 25,
        world_info_recursive: false,
        world_info_min_activations: 0,
        world_info_min_activations_depth_max: 0,
        world_info_overflow_alert: false,
        // 其余键名与 ST 一致：budget/recursive/include_names/case_sensitive/match_whole_words/
        //   character_strategy/use_group_scoring/max_recursion_steps（见下）
        world_info_case_sensitive: false,
        world_info_match_whole_words: false,
        world_info_include_names: true,
        world_info_use_group_scoring: false,
        world_info_character_strategy: 1,
        world_info_max_recursion_steps: 0,
    };
}

// ---------------------------------------------------------------- 读 / 写
export async function loadWorldInfo(name) {
    // [v208] P0-1：对齐 ST loadWorldInfo（world-info.js:2036-2058）—— 未命中返回 null/undefined（falsy）。
    // 此前返回 {}（真值）：ST-PT worldinfo.ts:216-222 先 `if (!lorebook) return []` 再
    // `Object.values(lorebook.entries)`，把 {} 当有效书 → 对 undefined.entries 取 values → TypeError。
    if (!name) return null;
    let r = null;
    try {
        const f = st().loadWorldInfo;
        if (typeof f === 'function') r = await f(name);
    } catch (_e) { r = null; }
    // 宿主未命中已返回 JSON null；再兜一层：非对象 / 无 entries 都算「无此书」
    if (!r || typeof r !== 'object' || !r.entries || typeof r.entries !== 'object') return null;
    return r;
}

export async function saveWorldInfo(name, data, immediately = false) {
    void immediately;
    if (!name || !data || typeof data !== 'object') return false;
    try {
        const c = st();
        if (typeof c.saveWorldInfo === 'function') {
            return await c.saveWorldInfo(name, data, immediately);
        }
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.createOrReplaceLorebook === 'function') {
            const entriesObj = (data.entries && typeof data.entries === 'object') ? data.entries : {};
            const entriesArr = Object.values(entriesObj).filter((e) => e && typeof e === 'object');
            return Boolean(window.RikkaBridge.createOrReplaceLorebook(String(name), JSON.stringify(entriesArr)));
        }
    } catch (_e) { /* noop */ }
    return false;
}
export async function updateWorldInfoList() {
    try { __refreshWorldInfoData(); } catch (_e) { /* noop */ }
}
export async function deleteWorldInfo(name) {
    if (!name) return false;
    try {
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.deleteLorebook === 'function') {
            return Boolean(window.RikkaBridge.deleteLorebook(String(name)));
        }
    } catch (_e) { /* noop */ }
    return false;
}
export async function createNewWorldInfo(name) {
    const book = { name: String(name || ''), entries: {} };
    if (name) await saveWorldInfo(name, book, true);
    return book;
}
export function createWorldInfoEntry(name, data) {
    void name;
    const entry = JSON.parse(JSON.stringify(newWorldInfoEntryTemplate));
    if (data && typeof data === 'object') {
        if (!data.entries || typeof data.entries !== 'object') data.entries = {};
        const existingUids = Object.keys(data.entries).map((k) => Number(k)).filter((n) => Number.isFinite(n));
        const nextUid = existingUids.length > 0 ? Math.max(...existingUids) + 1 : 0;
        entry.uid = nextUid;
        data.entries[String(nextUid)] = entry;
    } else {
        entry.uid = 0;
    }
    return entry;
}
export async function deleteWorldInfoEntry(data, uid) {
    if (!data || typeof data !== 'object' || !data.entries) return false;
    const key = String(uid);
    if (Object.prototype.hasOwnProperty.call(data.entries, key)) {
        delete data.entries[key];
        return true;
    }
    return false;
}
export function setWIOriginalDataValue() {}
export function reloadEditor() {}
export function setWorldInfoButtonClass() {}

export function convertCharacterBook(book) {
    // [v208] P2-3：对齐 ST convertCharacterBook（world-info.js:5617-5674）——
    // entries 转成 uid→entry 对象，每条套用 newWorldInfoEntryTemplate 默认值，并带 originalData。
    // 此前原样透传卡规格字段（keys/secondary_keys），JSR import_raw.ts:46 的角色书导入整体无效。
    try {
        if (!book || typeof book !== 'object') return { entries: {}, originalData: book };
        const list = Array.isArray(book.entries) ? book.entries : Object.values(book.entries || {});
        const entries = {};
        list.forEach((entry) => {
            if (!entry || typeof entry !== 'object') return;
            const uid = Number(entry.id != null ? entry.id : entry.uid);
            const base = JSON.parse(JSON.stringify(newWorldInfoEntryTemplate));
            base.uid = uid;
            base.key = Array.isArray(entry.keys) ? entry.keys.slice() : (Array.isArray(entry.key) ? entry.key.slice() : []);
            base.keysecondary = Array.isArray(entry.secondary_keys) ? entry.secondary_keys.slice()
                : (Array.isArray(entry.keysecondary) ? entry.keysecondary.slice() : []);
            base.comment = String(entry.comment == null ? '' : entry.comment);
            base.content = String(entry.content == null ? '' : entry.content);
            base.constant = entry.constant === true;
            base.selective = entry.selective !== false;
            base.selectiveLogic = Number(entry.selective_logic != null ? entry.selective_logic : entry.selectiveLogic) || 0;
            base.addMemo = entry.addMemo === true || (typeof entry.comment === 'string' && entry.comment.length > 0);
            base.order = Number(entry.insertion_order != null ? entry.insertion_order : entry.order) || 0;
            base.position = Number(entry.position != null ? entry.position : 0) || 0;
            base.disable = entry.enabled === false;
            base.excludeRecursion = entry.exclude_recursion === true;
            base.preventRecursion = entry.prevent_recursion === true;
            base.delayUntilRecursion = Number(entry.extensions?.delay_until_recursion ?? entry.delay_until_recursion) || 0;
            base.probability = Number(entry.extensions?.probability ?? entry.probability) || 100;
            base.useProbability = entry.extensions?.use_probability !== false;
            base.depth = Number(entry.extensions?.depth ?? entry.depth) || DEFAULT_DEPTH;
            base.group = String(entry.group ?? '');
            base.groupOverride = entry.group_override === true;
            base.groupWeight = Number(entry.extensions?.group_weight ?? entry.group_weight) || DEFAULT_WEIGHT;
            base.scanDepth = entry.extensions?.scan_depth != null ? Number(entry.extensions.scan_depth) : null;
            base.caseSensitive = entry.extensions?.case_sensitive != null ? entry.extensions.case_sensitive === true : null;
            base.matchWholeWords = entry.extensions?.match_whole_words != null ? entry.extensions.match_whole_words === true : null;
            base.useGroupScoring = entry.extensions?.use_group_scoring != null ? entry.extensions.use_group_scoring === true : null;
            base.automationId = String(entry.extensions?.automation_id ?? entry.automation_id ?? '');
            base.role = roleToStNumber(entry.extensions?.role ?? entry.role);
            base.outletName = String(entry.extensions?.outlet_name ?? entry.outlet_name ?? '');
            base.triggers = Array.isArray(entry.triggers) ? entry.triggers.slice() : (Array.isArray(entry.extensions?.triggers) ? entry.extensions.triggers.slice() : []);
            base.vectorized = entry.extensions?.vectorized === true || entry.vectorized === true;
            base.sticky = entry.extensions?.sticky != null ? Number(entry.extensions.sticky) : (entry.sticky != null ? Number(entry.sticky) : null);
            base.cooldown = entry.extensions?.cooldown != null ? Number(entry.extensions.cooldown) : (entry.cooldown != null ? Number(entry.cooldown) : null);
            base.delay = entry.extensions?.delay != null ? Number(entry.extensions.delay) : (entry.delay != null ? Number(entry.delay) : null);
            base.displayIndex = entry.extensions?.display_index != null ? Number(entry.extensions.display_index) : (entry.displayIndex != null ? Number(entry.displayIndex) : uid);
            base.ignoreBudget = entry.extensions?.ignore_budget === true || entry.ignoreBudget === true;
            base.matchPersonaDescription = entry.extensions?.match_persona_description === true || entry.matchPersonaDescription === true;
            base.matchCharacterDescription = entry.extensions?.match_character_description === true || entry.matchCharacterDescription === true;
            base.matchCharacterPersonality = entry.extensions?.match_character_personality === true || entry.matchCharacterPersonality === true;
            base.matchCharacterDepthPrompt = entry.extensions?.match_character_depth_prompt === true || entry.matchCharacterDepthPrompt === true;
            base.matchScenario = entry.extensions?.match_scenario === true || entry.matchScenario === true;
            base.matchCreatorNotes = entry.extensions?.match_creator_notes === true || entry.matchCreatorNotes === true;
            base.characterFilter = entry.extensions?.character_filter || entry.characterFilter || { isExclude: false, names: [], tags: [] };
            base.extensions = (entry.extensions && typeof entry.extensions === 'object') ? Object.assign({}, entry.extensions) : {};
            base.world = String(book.name == null ? '' : book.name);
            entries[String(uid)] = base;
        });
        return { entries, originalData: book };
    } catch (_e) {
        return { entries: {}, originalData: book };
    }
}

/** [v208] ST extension_prompt_roles（0/1/2）—— convertCharacterBook 用。 */
function roleToStNumber(role) {
    const r = String(role == null ? '' : role).toLowerCase();
    if (r === 'user' || r === '1') return 1;
    if (r === 'assistant' || r === '2') return 2;
    return 0;
}

export function parseRegexFromString(str) {
    // [v208] P2-2：对齐 ST parseRegexFromString（world-info.js:2901-2926）—— 只认 /pattern/flags 字面量。
    // 此前对任意字符串 new RegExp → 含 . + ? 等元字符的普通关键词被误当正则
    // （ST-PT worldinfo.ts:505-508 的语义是「先试正则，不是正则格式就走普通字符串匹配」）。
    if (typeof str !== 'string') return null;
    const m = str.match(/^\/(.+)\/([gimsuy]*)$/s);
    if (!m) return null;
    try {
        return new RegExp(m[1], m[2]);
    } catch (_e) {
        return null;
    }
}

// ---------------------------------------------------------------- 扫描 / 注入（直连宿主只读扫描桥）
export async function getWorldInfoPrompt(chat = [], maxContext = 0, isDryRun = true, globalScanData = {}) {
    const fallback = {
        worldInfoString: '', worldInfoBefore: '', worldInfoAfter: '',
        worldInfoExamples: [], worldInfoDepth: [],
        anBefore: '', anAfter: '', outletEntries: [],
    };
    try {
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.getWorldInfoPrompt === 'function') {
            const raw = window.RikkaBridge.getWorldInfoPrompt(
                JSON.stringify(Array.isArray(chat) ? chat : []),
                Number(maxContext) || 0,
                Boolean(isDryRun),
                JSON.stringify(globalScanData || {}),
            );
            if (raw) {
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object') return Object.assign({}, fallback, parsed);
            }
        }
    } catch (_e) { /* noop */ }
    return fallback;
}

export async function checkWorldInfo(chat = [], maxContext = 0, isDryRun = true, globalScanData = {}) {
    const res = await getWorldInfoPrompt(chat, maxContext, isDryRun, globalScanData);
    return Object.assign({ found: Boolean(res.worldInfoString) }, res);
}

export async function charUpdatePrimaryWorld() {}

// ── [batch16] newWorldInfoEntryTemplate ──────────────────────────
// 依据：JS-Slash-Runner 的 dist bundle 具名 import；缺失时真机日志为
//   `SyntaxError: The requested module '../../../../../scripts/world-info.js' does not provide an export named 'newWorldInfoEntryTemplate'`
// 语义对齐 ST：新建世界书条目的默认模板（uid 归 0，由调用方回填）。
// [v208] P1-7：对齐 ST newWorldInfoEntryDefinition（world-info.js:4082-4125，去掉 excludeFromTemplate
// 的 characterFilterNames/Tags/Exclude 后共 39 键）。此前少 9 键、多 uid/displayIndex、
// role/delayUntilRecursion 默认值错 —— JSR compatibility.ts:46 以它为底模铺字段，缺的键就是 undefined。
export const newWorldInfoEntryTemplate = {
    key: [],
    keysecondary: [],
    comment: '',
    content: '',
    constant: false,
    vectorized: false,
    selective: true,
    selectiveLogic: world_info_logic.AND_ANY,
    addMemo: false,
    order: 100,
    position: world_info_position.before,
    disable: false,
    excludeRecursion: false,
    preventRecursion: false,
    probability: 100,
    useProbability: true,
    depth: DEFAULT_DEPTH,
    ignoreBudget: false,
    outletName: '',
    group: '',
    groupOverride: false,
    groupWeight: DEFAULT_WEIGHT,
    scanDepth: null,
    caseSensitive: null,
    matchWholeWords: null,
    useGroupScoring: null,
    automationId: '',
    matchPersonaDescription: false,
    matchCharacterDescription: false,
    matchCharacterPersonality: false,
    matchCharacterDepthPrompt: false,
    matchScenario: false,
    matchCreatorNotes: false,
    // ST 模板里 delayUntilRecursion 默认是数字 0（4104）、role 是 0（4117）；sticky/cooldown/delay 是 null
    delayUntilRecursion: 0,
    role: 0,
    sticky: null,
    cooldown: null,
    delay: null,
    triggers: [],
};

export const originalWIDataKeyMap = {
    key: 'keys',
    keysecondary: 'secondary_keys',
    comment: 'comment',
    content: 'content',
    constant: 'constant',
    selective: 'selective',
    order: 'insertion_order',
    position: 'position',
    disable: 'enabled',
    excludeRecursion: 'exclude_recursion',
    preventRecursion: 'prevent_recursion',
    delayUntilRecursion: 'extensions.delay_until_recursion',
    probability: 'extensions.probability',
    useProbability: 'extensions.use_probability',
    depth: 'extensions.depth',
    group: 'group',
    groupOverride: 'group_override',
    groupWeight: 'extensions.group_weight',
    scanDepth: 'extensions.scan_depth',
    caseSensitive: 'extensions.case_sensitive',
    matchWholeWords: 'extensions.match_whole_words',
    useGroupScoring: 'extensions.use_group_scoring',
    automationId: 'extensions.automation_id',
    role: 'extensions.role',
    outletName: 'extensions.outlet_name',
    sticky: 'extensions.sticky',
    cooldown: 'extensions.cooldown',
    delay: 'extensions.delay',
    triggers: 'triggers',
    characterFilter: 'extensions.character_filter',
};

export function updateWorldInfoSettings(settings, selected) {
    try {
        if (Array.isArray(selected)) {
            selected_world_info = selected.slice();
            if (world_info && typeof world_info === 'object') {
                world_info.globalSelect = selected.slice();
            }
        }
        if (settings && typeof settings === 'object' && settings.world_info && typeof settings.world_info === 'object') {
            world_info = settings.world_info;
        }
    } catch (_e) { /* noop */ }
}

export async function onWorldInfoChange(eventOrOptions, worldName) {
    try {
        const name = typeof worldName === 'string' ? worldName : (typeof eventOrOptions === 'string' ? eventOrOptions : '');
        const state = (eventOrOptions && typeof eventOrOptions === 'object' && 'state' in eventOrOptions) ? Boolean(eventOrOptions.state) : undefined;
        if (name) {
            const set = new Set(Array.isArray(selected_world_info) ? selected_world_info : []);
            if (state === true) set.add(name);
            else if (state === false) set.delete(name);
            else if (set.has(name)) set.delete(name);
            else set.add(name);
            selected_world_info = Array.from(set);
            if (world_info && typeof world_info === 'object') {
                world_info.globalSelect = selected_world_info.slice();
            }
        }
        await updateWorldInfoList();
    } catch (_e) { /* noop */ }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export const SORT_ORDER_KEY = 'world_info_sort_order';
export const scan_state = {
    NONE: 0,
    INITIAL: 1,
    RECURSION: 2,
    MIN_ACTIVATIONS: 3,
};
export const newWorldInfoEntryDefinition = {
    key: { default: [], type: 'array' },
    keysecondary: { default: [], type: 'array' },
    comment: { default: '', type: 'string' },
    content: { default: '', type: 'string' },
    constant: { default: false, type: 'boolean' },
    vectorized: { default: false, type: 'boolean' },
    selective: { default: true, type: 'boolean' },
    selectiveLogic: { default: world_info_logic.AND_ANY, type: 'enum' },
    addMemo: { default: false, type: 'boolean' },
    order: { default: 100, type: 'number' },
    position: { default: 0, type: 'number' },
    disable: { default: false, type: 'boolean' },
    ignoreBudget: { default: false, type: 'boolean' },
    excludeRecursion: { default: false, type: 'boolean' },
    preventRecursion: { default: false, type: 'boolean' },
    matchPersonaDescription: { default: false, type: 'boolean' },
    matchCharacterDescription: { default: false, type: 'boolean' },
    matchCharacterPersonality: { default: false, type: 'boolean' },
    matchCharacterDepthPrompt: { default: false, type: 'boolean' },
    matchScenario: { default: false, type: 'boolean' },
    matchCreatorNotes: { default: false, type: 'boolean' },
    delayUntilRecursion: { default: 0, type: 'number' },
    probability: { default: 100, type: 'number' },
    useProbability: { default: true, type: 'boolean' },
    depth: { default: DEFAULT_DEPTH, type: 'number' },
    outletName: { default: '', type: 'string' },
    group: { default: '', type: 'string' },
    groupOverride: { default: false, type: 'boolean' },
    groupWeight: { default: DEFAULT_WEIGHT, type: 'number' },
    scanDepth: { default: null, type: 'number?' },
    caseSensitive: { default: null, type: 'boolean?' },
    matchWholeWords: { default: null, type: 'boolean?' },
    useGroupScoring: { default: null, type: 'boolean?' },
    automationId: { default: '', type: 'string' },
    role: { default: 0, type: 'enum' },
    sticky: { default: null, type: 'number?' },
    cooldown: { default: null, type: 'number?' },
    delay: { default: null, type: 'number?' },
    characterFilterNames: { default: [], type: 'array', excludeFromTemplate: true },
    characterFilterTags: { default: [], type: 'array', excludeFromTemplate: true },
    characterFilterExclude: { default: false, type: 'boolean', excludeFromTemplate: true },
    triggers: { default: [], type: 'array' },
};
export const worldInfoCache = new Map();
export const worldInfoFilter = { filter: () => {} };
export let world_info_budget = 25;
export let world_info_budget_cap = 0;
export let world_info_character_strategy = world_info_insertion_strategy.character_first;
export let world_info_depth = 2;
export let world_info_min_activations = 0;
export let world_info_min_activations_depth_max = 0;
export let world_info_overflow_alert = false;
export let world_info_recursive = false;
export async function assignLorebookToChat(..._args) { return null; }
export function charSetAuxWorlds(..._args) { return null; }
export async function charUpdateAddAuxWorld(..._args) { return null; }
export function checkEmbeddedWorld(..._args) { return false; }
export function deleteWIOriginalDataValue(data, uid, key) { if (data?.originalData?.entries?.[uid]) delete data.originalData.entries[uid][key]; }
export function duplicateWorldInfoEntry(data, uid) { if (!data?.entries?.[uid]) return null; const copy = JSON.parse(JSON.stringify(data.entries[uid])); const nextUid = getFreeWorldEntryUid(data); copy.uid = nextUid; data.entries[nextUid] = copy; return copy; }
export function getFreeWorldEntryUid(data) { if (!data?.entries) return 0; const uids = Object.keys(data.entries).map(Number).filter(Number.isFinite); return uids.length ? Math.max(...uids) + 1 : 0; }
export function getFreeWorldName(baseName = 'New World') { return String(baseName || 'New World'); }
export async function getSortedEntries(data) { if (!data?.entries) return []; return Object.values(data.entries).sort((a, b) => (b.order ?? 0) - (a.order ?? 0)); }
export async function getWorldEntry(name, data, entry) { void name; void data; return entry || null; }
export async function importEmbeddedWorldInfo(..._args) { return null; }
export async function importWorldInfo(..._args) { return null; }
export function initWorldInfo(..._args) { return null; }
export async function moveWorldInfoEntry(..._args) { return null; }
export function openWorldInfoEditor(..._args) { return null; }
export function setWorldInfoSettings(settings = {}) { updateWorldInfoSettings(settings, settings?.world_info?.globalSelect); }
export async function showWorldEditor(..._args) { return null; }
export function sortWorldInfoEntries(entries = []) { return Array.isArray(entries) ? entries.slice().sort((a, b) => (b.order ?? 0) - (a.order ?? 0)) : []; }
export function splitKeywordsAndRegexes(keys = []) {
    const keywords = [];
    const regexes = [];
    for (const k of (Array.isArray(keys) ? keys : [])) {
        const rx = parseRegexFromString(k);
        if (rx) regexes.push({ regex: rx, raw: k });
        else keywords.push(k);
    }
    return { keywords, regexes };
}
