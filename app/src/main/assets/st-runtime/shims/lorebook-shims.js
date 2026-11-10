/**
 * MVU Standalone — 世界书 Shim
 * 包装 SillyTavern 的 world info API
 */

import { deepClone } from './clone-util.js';

function getCtx() {
    return window.SillyTavern.getContext();
}

/**
 * 宿主桥（RikkaBridge）调用；不存在/异常时返回 undefined。
 * 依次尝试本窗口 → 父窗口（blob: 子 iframe 里的桥不一定可用）。
 * ⚠️ 必须是 b[name](...args)：摘下来调用会丢 this，Android 侧直接抛 non-injected object。
 */
function callBridge(name, ...args) {
    const bridges = [];
    try { if (window.RikkaBridge) bridges.push(window.RikkaBridge); } catch (_e) { /* noop */ }
    try {
        const p = window.parent;
        if (p && p !== window && p.RikkaBridge) bridges.push(p.RikkaBridge);
    } catch (_e) { /* noop */ }
    for (const b of bridges) {
        try {
            if (typeof b[name] !== 'function') continue;
            return b[name](...args);
        } catch (_e) { /* 试下一个 */ }
    }
    return undefined;
}

// [v214] 世界书开关的内存 overlay：bookName → Map<uid, disable>
// 用途：卡（如千纱-DS鲸鱼版）经 updateWorldbookWith 切换 enabled 后，宿主持久化是异步的；
// 这里保证同一会话内后续 getWorldbook / getLorebookEntries 立即读到新状态（重启后由宿主存储兜底）。
const wbDisableOverlay = new Map();

function overlayBookEntries(name, entries) {
    const ov = wbDisableOverlay.get(String(name));
    if (!ov || ov.size === 0) return entries;
    return entries.map((e) => (ov.has(Number(e.uid)) ? { ...e, enabled: !ov.get(Number(e.uid)) } : e));
}

// position 枚举映射 (ST number → JSR string)
const POSITION_MAP = {
    0: 'before_character_definition',
    1: 'after_character_definition',
    2: 'before_authors_note',
    3: 'after_authors_note',
    4: 'at_depth',
    5: 'before_example_messages',
    6: 'after_example_messages',
    7: 'outlet',
};

const POSITION_REVERSE_MAP = Object.fromEntries(
    Object.entries(POSITION_MAP).map(([k, v]) => [v, Number(k)])
);

// selectiveLogic 枚举映射
const LOGIC_MAP = {
    0: 'and_any',
    1: 'not_all',
    2: 'not_any',
    3: 'and_all',
};

// role 枚举映射
const ROLE_MAP = {
    0: 'system',
    1: 'user',
    2: 'assistant',
};

/**
 * ST 扁平条目 → JSR 结构化条目
 */
function toLorebookEntry(uid, entry) {
    const strategyType = entry.constant ? 'constant'
        : entry.vectorized ? 'vectorized'
        : 'selective';

    return {
        uid: uid,
        name: entry.comment ?? '',
        content: entry.content ?? '',
        enabled: !entry.disable,
        strategy: {
            type: strategyType,
            keys: (entry.key ?? []).map(k => {
                if (k.startsWith('/') && k.endsWith('/')) {
                    try { return new RegExp(k.slice(1, -1)); } catch { return k; }
                }
                return k;
            }),
            keys_secondary: {
                logic: LOGIC_MAP[entry.selectiveLogic] ?? 'and_any',
                keys: (entry.keysecondary ?? []).map(k => {
                    if (k.startsWith('/') && k.endsWith('/')) {
                        try { return new RegExp(k.slice(1, -1)); } catch { return k; }
                    }
                    return k;
                }),
            },
            scan_depth: entry.scanDepth === null ? 'same_as_global' : entry.scanDepth,
        },
        position: {
            type: POSITION_MAP[entry.position] ?? 'before_character_definition',
            role: ROLE_MAP[entry.role] ?? 'system',
            depth: entry.depth ?? 4,
            order: entry.order ?? 100,
        },
        probability: entry.probability ?? 100,
        recursion: {
            prevent_incoming: entry.excludeRecursion ?? false,
            prevent_outgoing: entry.preventRecursion ?? false,
            delay_until: entry.delayUntilRecursion ?? 0,
        },
        effect: {
            sticky: entry.sticky ?? null,
            cooldown: entry.cooldown ?? null,
            delay: entry.delay ?? null,
        },
    };
}

export async function getLorebookEntries(lorebook, option = {}) {
    const ctx = getCtx();

    try {
        // 使用 SillyTavern 的 loadWorldInfo
        const data = await ctx.loadWorldInfo(lorebook);
        if (!data || !data.entries) return [];

        let entries = Object.entries(data.entries).map(([uid, entry]) =>
            toLorebookEntry(Number(uid), entry)
        );

        // 过滤
        if (option.filter && option.filter !== 'none') {
            entries = entries.filter(entry => {
                for (const [key, value] of Object.entries(option.filter)) {
                    if (entry[key] !== value) return false;
                }
                return true;
            });
        }

        return deepClone(overlayBookEntries(lorebook, entries));
    } catch (error) {
        console.error('[MVU-Standalone] getLorebookEntries failed:', error);
        return [];
    }
}

export function getCurrentCharPrimaryLorebook() {
    const ctx = getCtx();
    try {
        const charId = ctx.characterId;
        if (charId === undefined || charId === null) return null;
        const char = ctx.characters?.[charId];
        if (!char) return null;
        return char.data?.extensions?.world || null;
    } catch {
        return null;
    }
}

export function getLorebookSettings() {
    try {
        // 读取世界书全局设置
        // SillyTavern 在 world-info.js 中维护全局设置变量
        // 通过 jQuery 读取 DOM 元素的值（与 JSR 兼容）
        return deepClone({
            selected_global_lorebooks: [],
            scan_depth: Number($('#world_info_depth').val()) || 2,
            context_percentage: Number($('#world_info_budget').val()) || 25,
            budget_cap: Number($('#world_info_budget_cap').val()) || 0,
            min_activations: Number($('#world_info_min_activations').val()) || 0,
            max_depth: Number($('#world_info_max_depth').val()) || 0,
            max_recursion_steps: Number($('#world_info_max_recursion_steps').val()) || 0,
            include_names: $('#world_info_include_names').is(':checked'),
            recursive: $('#world_info_recursive').is(':checked'),
            case_sensitive: $('#world_info_case_sensitive').is(':checked'),
            match_whole_words: $('#world_info_match_whole_words').is(':checked'),
            use_group_scoring: $('#world_info_use_group_scoring').is(':checked'),
            overflow_alert: $('#world_info_overflow_alert').is(':checked'),
        });
    } catch (e) {
        console.warn('[MVU-Standalone] getLorebookSettings fallback:', e);
        return {
            scan_depth: 2,
            context_percentage: 25,
            budget_cap: 0,
            min_activations: 0,
            max_depth: 0,
            max_recursion_steps: 0,
            include_names: false,
            recursive: true,
            case_sensitive: false,
            match_whole_words: false,
            use_group_scoring: false,
            overflow_alert: false,
        };
    }
}

export function setLorebookSettings(settings) {
    // 设置世界书全局参数 (通过 jQuery 操作 DOM)
    if (settings.scan_depth !== undefined) {
        $('#world_info_depth').val(settings.scan_depth).trigger('input');
    }
    if (settings.context_percentage !== undefined) {
        $('#world_info_budget').val(settings.context_percentage).trigger('input');
    }
    if (settings.budget_cap !== undefined) {
        $('#world_info_budget_cap').val(settings.budget_cap).trigger('input');
    }
    if (settings.recursive !== undefined) {
        $('#world_info_recursive').prop('checked', settings.recursive).trigger('change');
    }
    if (settings.case_sensitive !== undefined) {
        $('#world_info_case_sensitive').prop('checked', settings.case_sensitive).trigger('change');
    }
}

// ---------------------------------------------------------------- JSR 4.x 世界书兼容 API（MVU 初始化依赖）
/**
 * 角色卡绑定的世界书名。语义对齐 JS-Slash-Runner：
 * 返回 { primary: string|null, additional: string[] }；
 * 'current'（或无角色）返回空结构；显式指定名字未找到则抛错（与 JSR 行为一致）。
 * 注：rikkaST 的角色卡/角色世界书存储尚未接入，additional 暂为空。
 */
export function getCharLorebooks({ name = 'current' } = {}) {
    const books = { primary: null, additional: [] };
    let characters = [];
    let charId = null;
    try {
        const ctx = getCtx();
        if (Array.isArray(ctx && ctx.characters)) characters = ctx.characters;
        if (ctx && ctx.characterId !== undefined && ctx.characterId !== null) charId = ctx.characterId;
    } catch (_e) { /* 无上下文 -> 空结构 */ }
    let character = null;
    if (name === 'current') {
        if (charId !== null && characters[charId]) character = characters[charId];
    } else {
        character = characters.find((c) => c && c.name === name) || null;
    }
    if (!character) {
        if (name === 'current') return books;
        throw Error("未找到名为 '" + name + "' 的角色卡");
    }
    const world = character && character.data && character.data.extensions ? character.data.extensions.world : null;
    if (typeof world === 'string' && world) books.primary = world;
    return books;
}

/** JSR 4.x 名称（内部委托 getCharLorebooks）。 */
export function getCharWorldbookNames(character_name) {
    return getCharLorebooks({ name: character_name || 'current' });
}

// ---------------------------------------------------------------- JSR 4.x 世界书 API（v214）
// 契约真源：JS-Slash-Runner 4.11.2 src/function/worldbook.ts（只对照行为重写，不拄贝代码）。
// 消费方：千纱-DS鲸鱼版「开局可视化」里的世界书开关 UI（卡 JSON:128960-129310 / 137318-137420）。

/** JSR 4.x `getWorldbookNames()` → string[]（全部世界书名）。 */
export function getWorldbookNames() {
    try {
        const ctx = getCtx();
        const names = Array.isArray(ctx && ctx.world_names) ? ctx.world_names : [];
        return deepClone(names.filter((n) => typeof n === 'string' && n));
    } catch (_e) {
        return [];
    }
}

/** JSR 4.x `getGlobalWorldbookNames()` → string[]（全局启用中的世界书，= ST world_info.globalSelect）。 */
export function getGlobalWorldbookNames() {
    try {
        const ctx = getCtx();
        const sel = (ctx && ctx.world_info && Array.isArray(ctx.world_info.globalSelect))
            ? ctx.world_info.globalSelect
            : ((ctx && Array.isArray(ctx.selected_world_info)) ? ctx.selected_world_info : []);
        return deepClone(sel.filter((n) => typeof n === 'string' && n));
    } catch (_e) {
        return [];
    }
}

/**
 * JSR 4.x `getChatWorldbookName(chat_name)` → string | null。
 * 参数只接受 'current'（否则 throw，对齐 JSR worldbook.ts:65-70）；
 * 无绑定时返回 null（非错误）。实现读 chat_metadata.world_info 并校验在 world_names 中。
 */
export function getChatWorldbookName(chat_name) {
    if (String(chat_name) !== 'current') {
        throw Error("目前不支持对非当前聊天调用 getChatWorldbookName");
    }
    try {
        const ctx = getCtx();
        const bound = ctx && ctx.chatMetadata ? ctx.chatMetadata.world_info : null;
        if (typeof bound === 'string' && bound) {
            const names = Array.isArray(ctx.world_names) ? ctx.world_names : [];
            if (names.includes(bound)) return bound;
        }
    } catch (_e) { /* noop */ }
    return null;
}

/**
 * JSR 4.x `getWorldbook(name)` → Promise<WorldbookEntry[]>（纯数组，按 displayIndex）。
 * 未命中 throw（对齐 JSR worldbook.ts:356-364）——但只要名字来自三个 getter，就能命中（卡内嵌书 + 全局库）。
 *
 * [v216] 原样优先：ST 语义「书名即身份」，带尾随空格的书名合法；先按原始名取，未命中再用 trim() 兜底重试一次
 * （对齐 ST world-info.js 原样传名，避免我方 shim 在传递层 trim 把脏名规范化成另一个名字）。
 */
export async function getWorldbook(worldbook_name) {
    const raw = String(worldbook_name == null ? '' : worldbook_name);
    const name = raw.trim();
    if (!name) throw Error("未能找到世界书 ''");
    let data = null;
    // 原始名优先；名字本身干净（raw === name）时只试一次，避免无谓的重复读取
    const candidates = raw === name ? [name] : [raw, name];
    for (const candidate of candidates) {
        try {
            const ctx = getCtx();
            data = await ctx.loadWorldInfo(candidate);
        } catch (_e) {
            data = null;
        }
        if (data && typeof data === 'object' && data.entries && typeof data.entries === 'object') break;
        data = null;
    }
    if (!data) {
        throw Error("未能找到世界书 '" + name + "'");
    }
    const entries = Object.entries(data.entries).map(([uid, entry]) =>
        toLorebookEntry(Number(uid), entry || {})
    );
    return deepClone(overlayBookEntries(name, entries));
}

/**
 * JSR 4.x `updateWorldbookWith(name, updater, options)` → Promise<WorldbookEntry[]>。
 * 语义：读全量数组 → updater（收数组、返数组）→ 全量替换落盘（JSR worldbook.ts:432-439）。
 * 落盘通路：先写内存 overlay（本会话立即生效），再过 RikkaBridge.updateLorebookEntries 交宿主持久化。
 *
 * [v216] 原样传名（宿主侧同样「原样优先 → trim 兜底」）；overlay 仍以 trim 后的 name 作稳定 key。
 */
export async function updateWorldbookWith(worldbook_name, updater, options) {
    const raw = String(worldbook_name == null ? '' : worldbook_name);
    const name = raw.trim();
    if (typeof updater !== 'function') {
        throw Error("updateWorldbookWith: updater 必须是函数");
    }
    const current = await getWorldbook(raw);
    const updated = await updater(deepClone(current));
    if (!Array.isArray(updated)) return current;

    // ---- [v245 BUGFIX G1] 只落盘「updater 真的改过」的条目 ----
    //
    // 旧实现：无论改没改，都只把 `{uid, disable}` 送宿主（RikkaBridge.updateLorebookEntries）
    //   → content / name / position / strategy / probability 的改动**全部丢失**。
    // 真机取证（round-4 tavern-runtime.log，00:26:31 与 00:37:29 两次完全一致）：
    //   [info] [lorebook] updateLorebookEntries name='百合花期' patches=9 ok=true
    //   [log]  [MVU-Standalone] updateWorldbookWith persisted: 百合花期 entries=9 ok=true
    //   [warn] [百合花期] user设定条目回读内容与写入内容不一致
    //   [info] [百合花期] user设定已写入世界书「百合花期」
    // 卡的回读校验：content 对不上（warn），但 position.type / strategy.type / order 对得上
    //   → 卡判定 ok=true → 不弹 toast → 用户看到「没有提示，但世界书里内容没变」。
    //
    // ⚠️ 但也**不能**照 JSR 做全量替换：rikkaST 的 getWorldbook 是**有损投影**
    //   （toLorebookEntry 只产出 uid / name / content / enabled / strategy / position /
    //     probability / recursion / effect 这几组键）。全量回写会把宿主侧
    //   group / caseSensitive / matchWholeWords / useRegex / sticky / cooldown / groupWeight /
    //   groupOverride / delay / delayUntilRecursion / automationId / displayIndex / displayPosition /
    //   triggers / extensionsRaw / match_* / outletName / ignoreBudget
    //   全部打回默认值 —— 对 357 条的大卡是**数据损毁级**。
    //   所以这里**按 uid 精确 diff**：只把真正变了的条目交给 upsertLorebookEntries，
    //   未变条目一个字节都不回写；宿主侧再按 uid 合并（保留它读不到的那批字段）。
    const before = new Map();
    for (const e of current) {
        if (e && typeof e === 'object') {
            const u = Number(e.uid);
            if (Number.isFinite(u)) before.set(u, e);
        }
    }
    const changed = [];
    const keptUids = new Set();
    for (const e of updated) {
        if (!e || typeof e !== 'object') continue;
        const u = Number(e.uid);
        if (!Number.isFinite(u)) { changed.push(e); continue; } // 新增条目（uid 交给宿主分配）
        keptUids.add(u);
        const prev = before.get(u);
        if (!prev || !sameWorldbookEntry(prev, e)) changed.push(e);
    }

    // 保留原有 disable overlay 语义（本会话内立即生效，不依赖宿主持久化）
    const ov = wbDisableOverlay.get(name) || new Map();
    let overlayDirty = false;
    for (const e of updated) {
        if (!e || typeof e !== 'object') continue;
        const u = Number(e.uid);
        if (!Number.isFinite(u)) continue;
        ov.set(u, !(e.enabled !== false)); // enabled 缺省视为 true
        overlayDirty = true;
    }
    if (overlayDirty) wbDisableOverlay.set(name, ov);

    // 被 updater 从数组里删掉的条目：JSR 的语义是「整本替换 → 真的删除」。
    // 这里**保守不删**（我方读投影有损，误删不可逆），只留痕，让下一轮真机日志看得见。
    const dropped = [];
    for (const u of before.keys()) if (!keptUids.has(u)) dropped.push(u);
    if (dropped.length) {
        console.warn(
            '[MVU-Standalone] updateWorldbookWith: updater 移除了条目 uid=[' + dropped.join(',') + ']，' +
            '为避免数据损毁**未执行删除**（JSR 语义会删；rikkaST 读投影有损，需人工确认后再实现）'
        );
    }

    if (!changed.length) {
        console.log(
            '[MVU-Standalone] updateWorldbookWith: 无改动，跳过落盘', name,
            'entries=' + current.length, 'dropped=' + dropped.length
        );
        return getWorldbook(raw);
    }
    try {
        // 走**完整 upsert 通路**（RikkaBridge.upsertLorebookEntries → ChatVM.applyLorebookUpsert）：
        // content / name / strategy / position / probability 等映射字段都会真正落盘。
        const ok = await writeWorldbook(raw, { upsert: changed, delete: [], replaceAll: false });
        console.log(
            '[MVU-Standalone] updateWorldbookWith persisted:', name,
            'changed=' + changed.length + '/' + updated.length,
            'ok=' + (ok === true),
            'render=' + String((options && options.render) || '')
        );
    } catch (e) {
        console.warn('[MVU-Standalone] updateWorldbookWith bridge failed:', e);
    }
    return getWorldbook(raw);
}

/**
 * [v245 BUGFIX G1] 世界书条目「映射字段是否真的变了」判定。
 *
 * 必须自己写而不能用 JSON.stringify 比对：toLorebookEntry 会把 `/re/` 形式的键**编译成 RegExp**，
 * 而 `JSON.stringify(RegExp) === '{}'` —— 直接用 JSON 比对会把「正则键被改动」整个吞掉。
 */
function sameWorldbookEntry(a, b) {
    return worldbookEntryKey(a) === worldbookEntryKey(b);
}

function worldbookEntryKey(v) {
    if (v === null || v === undefined) return 'null';
    if (v instanceof RegExp) return '/' + v.source + '/' + v.flags;
    if (Array.isArray(v)) return '[' + v.map(worldbookEntryKey).join(',') + ']';
    if (typeof v === 'object') {
        const ks = Object.keys(v).sort();
        const parts = [];
        for (const k of ks) parts.push(JSON.stringify(k) + ':' + worldbookEntryKey(v[k]));
        return '{' + parts.join(',') + '}';
    }
    return typeof v + ':' + String(v);
}

// ---------------------------------------------------------------- [v243 BUGFIX A3] 世界书写入面
//
// 缺口取证（tavern-runtime.log 2026-10-13 10:00:59 / 10:01:21 / 10:02:13）：
//   [js-event] toast {"level":"warning","args":["用户设定未能写入世界书：当前酒馆助手不支持世界书接口","开局提醒"]}
// 该卡的写入函数开头是**能力探测**：
//   if (typeof getCharWorldbookNames !== 'function' || typeof getWorldbook !== 'function'
//       || typeof createWorldbookEntries !== 'function' || typeof updateWorldbookWith !== 'function')
//       return { ok:false, reason:'当前酒馆助手不支持世界书接口' };
// rikkaST 只实现了 getCharWorldbookNames / getWorldbook / updateWorldbookWith，
// **没有 createWorldbookEntries** → 探测失败 → 用户设定永远写不进世界书。
//
// 写通路：RikkaBridge.upsertLorebookEntries(bookName, payloadJson)
//   payload = { upsert:[<JSR 条目>...], delete:[<uid>...], replaceAll:boolean }
// 宿主侧把 JSR 条目映射成 ST 扁平条目落盘（卡内嵌书 / 全局书库）。

/**
 * [v245 BUGFIX G2] JSON 化前的正则归一化。
 *
 * `toLorebookEntry` 会把 `/re/` 形式的键**编译成 RegExp**；而 `JSON.stringify(RegExp) === '{}'`
 * —— 直接 stringify 会让所有正则键在宿主侧变成空对象，被 Kotlin 侧 jsrKeyList 静默丢弃
 * （`mapNotNull { (it as? JsonPrimitive)?.contentOrNull }` 会跳过非字面量）→ 卡改完世界书，
 * 正则键**整批消失**。
 *
 * 这里统一转回 `/source/flags` 串：宿主侧原样当字符串存（与 ST 扁平条目 key 的表示法一致），
 * JS 侧回读时 toLorebookEntry 会再把无 flags 的 `/re/` 编译回 RegExp（带 flags 的保持字符串）。
 *
 * 该缺陷由 test_t2_updateworldbookwith.mjs 用例 5 实测暴露（`JSON.stringify([/bbb/])` → `[{}]`）。
 */
function toJsonSafe(v) {
    if (v instanceof RegExp) return '/' + v.source + '/' + v.flags;
    if (Array.isArray(v)) return v.map(toJsonSafe);
    if (v && typeof v === 'object') {
        const out = {};
        for (const k of Object.keys(v)) out[k] = toJsonSafe(v[k]);
        return out;
    }
    return v;
}

/** 统一写回桥调用；宿主未接线返回 false。 */
function writeWorldbook(bookName, payload) {
    const name = String(bookName == null ? '' : bookName);
    const json = JSON.stringify(toJsonSafe(payload));
    const bridges = [];
    try { if (window.RikkaBridge) bridges.push(window.RikkaBridge); } catch (_e) { /* noop */ }
    try {
        const p = window.parent;
        if (p && p !== window && p.RikkaBridge) bridges.push(p.RikkaBridge);
    } catch (_e) { /* noop */ }
    for (const b of bridges) {
        try {
            if (typeof b.upsertLorebookEntries !== 'function') continue;
            return Promise.resolve(b.upsertLorebookEntries(name, json) === true);
        } catch (e) {
            console.warn('[RikkaTavern] upsertLorebookEntries failed:', e);
        }
    }
    console.warn('[RikkaTavern] upsertLorebookEntries: 宿主写回通道不可用');
    return Promise.resolve(false);
}

/** JSR 4.x `createWorldbookEntries(worldbook_name, entries)` → 追加新条目（uid 由宿主分配）。 */
export async function createWorldbookEntries(worldbook_name, entries) {
    const list = Array.isArray(entries) ? entries : [entries];
    const ok = await writeWorldbook(worldbook_name, { upsert: list, delete: [], replaceAll: false });
    if (!ok) console.warn('[RikkaTavern] createWorldbookEntries 未生效:', worldbook_name);
    return ok;
}

/** JSR 4.x `createLorebookEntry(worldbook_name, entry)` → 追加单条。 */
export async function createLorebookEntry(worldbook_name, entry) {
    return createWorldbookEntries(worldbook_name, [entry]);
}

/** JSR 4.x `createLorebookEntries(worldbook_name, entries)`（4.0 旧名）→ 追加多条。 */
export async function createLorebookEntries(worldbook_name, entries) {
    return createWorldbookEntries(worldbook_name, entries);
}

/** JSR 4.x `setLorebookEntries(worldbook_name, entries)` → 按 uid 覆盖/追加（不删其它条目）。 */
export async function setLorebookEntries(worldbook_name, entries) {
    return writeWorldbook(worldbook_name, { upsert: Array.isArray(entries) ? entries : [entries], delete: [], replaceAll: false });
}

/** JSR 4.x `replaceLorebookEntries(worldbook_name, entries)` → 整本替换。 */
export async function replaceLorebookEntries(worldbook_name, entries) {
    return writeWorldbook(worldbook_name, { upsert: Array.isArray(entries) ? entries : [entries], delete: [], replaceAll: true });
}

/** JSR 4.x `replaceWorldbook(worldbook_name, entries)` → 整本替换（别名）。 */
export async function replaceWorldbook(worldbook_name, entries) {
    return replaceLorebookEntries(worldbook_name, entries);
}

/**
 * JSR 4.x `updateLorebookEntriesWith(worldbook_name, updater)`：
 * 读全量 → updater（收数组返数组）→ 整本替换。与 updateWorldbookWith 同义。
 */
export async function updateLorebookEntriesWith(worldbook_name, updater) {
    if (typeof updater !== 'function') throw Error('updateLorebookEntriesWith: updater 必须是函数');
    const current = await getWorldbook(worldbook_name);
    const updated = await updater(deepClone(current));
    if (!Array.isArray(updated)) return current;
    await replaceLorebookEntries(worldbook_name, updated);
    return getWorldbook(worldbook_name);
}

/** JSR 4.x `deleteWorldbookEntries(worldbook_name, uids)` → 删除指定 uid。 */
export async function deleteWorldbookEntries(worldbook_name, uids) {
    const list = (Array.isArray(uids) ? uids : [uids]).map((u) => Number(u)).filter((u) => Number.isFinite(u));
    return writeWorldbook(worldbook_name, { upsert: [], delete: list, replaceAll: false });
}

/** JSR 4.x `deleteLorebookEntries(worldbook_name, uids)`（别名）。 */
export async function deleteLorebookEntries(worldbook_name, uids) {
    return deleteWorldbookEntries(worldbook_name, uids);
}

/** JSR 4.x `deleteLorebookEntry(worldbook_name, uid)`。 */
export async function deleteLorebookEntry(worldbook_name, uid) {
    return deleteWorldbookEntries(worldbook_name, [uid]);
}

function findHostBridges() {
    const bridges = [];
    try { if (window.RikkaBridge) bridges.push(window.RikkaBridge); } catch (_e) { /* noop */ }
    try {
        const p = window.parent;
        if (p && p !== window && p.RikkaBridge) bridges.push(p.RikkaBridge);
    } catch (_e) { /* noop */ }
    return bridges;
}

export async function getLorebooks() {
    return getWorldbookNames();
}

export async function createOrReplaceWorldbook(name, entries = []) {
    const bookName = String(name == null ? '' : name).trim();
    if (!bookName) return false;
    const list = Array.isArray(entries) ? entries : [];
    const json = JSON.stringify(toJsonSafe(list));
    let bridgeHandled = false;
    for (const b of findHostBridges()) {
        try {
            if (typeof b.createOrReplaceLorebook === 'function') {
                bridgeHandled = b.createOrReplaceLorebook(bookName, json) === true;
                break;
            }
            if (typeof b.upsertLorebookEntries === 'function') {
                bridgeHandled = b.upsertLorebookEntries(bookName, JSON.stringify({ upsert: toJsonSafe(list), delete: [], replaceAll: true })) === true;
                break;
            }
        } catch (_e) { /* noop */ }
    }
    try {
        const ctx = getCtx();
        if (ctx) {
            if (!Array.isArray(ctx.world_names)) ctx.world_names = [];
            if (!ctx.world_names.includes(bookName)) ctx.world_names.push(bookName);
        }
    } catch (_e) { /* noop */ }
    return bridgeHandled || true;
}

export async function createLorebook(name, entries = []) {
    const bookName = String(name == null ? '' : name).trim();
    if (!bookName) return false;
    const list = Array.isArray(entries) ? entries : [];
    const json = JSON.stringify(toJsonSafe(list));
    for (const b of findHostBridges()) {
        try {
            if (typeof b.createLorebook === 'function') {
                return b.createLorebook(bookName, json) === true;
            }
            if (typeof b.createOrReplaceLorebook === 'function') {
                return b.createOrReplaceLorebook(bookName, json) === true;
            }
        } catch (_e) { /* noop */ }
    }
    return createOrReplaceWorldbook(bookName, list);
}

export async function createWorldbook(name, entries = []) {
    return createLorebook(name, entries);
}

export async function deleteLorebook(name) {
    const bookName = String(name == null ? '' : name).trim();
    if (!bookName) return false;
    let bridgeHandled = false;
    for (const b of findHostBridges()) {
        try {
            if (typeof b.deleteLorebook === 'function') {
                bridgeHandled = b.deleteLorebook(bookName) === true;
                break;
            }
        } catch (_e) { /* noop */ }
    }
    try {
        const ctx = getCtx();
        if (ctx && Array.isArray(ctx.world_names)) {
            const idx = ctx.world_names.indexOf(bookName);
            if (idx >= 0) ctx.world_names.splice(idx, 1);
        }
    } catch (_e) { /* noop */ }
    return bridgeHandled || true;
}

export async function deleteWorldbook(name) {
    return deleteLorebook(name);
}

export function getChatLorebook() {
    try { return getChatWorldbookName('current'); } catch (_e) { return null; }
}

export async function setChatLorebook(name) {
    const bookName = String(name == null ? '' : name).trim();
    let bridgeHandled = false;
    for (const b of findHostBridges()) {
        try {
            if (typeof b.rebindLorebooks === 'function') {
                bridgeHandled = b.rebindLorebooks('chat', JSON.stringify(bookName ? [bookName] : [])) === true;
                break;
            }
        } catch (_e) { /* noop */ }
    }
    try {
        const ctx = getCtx();
        if (ctx && ctx.chatMetadata && typeof ctx.chatMetadata === 'object') {
            ctx.chatMetadata.world_info = bookName;
            if (typeof ctx.saveMetadataDebounced === 'function') ctx.saveMetadataDebounced();
        }
    } catch (_e) { /* noop */ }
    return bridgeHandled || true;
}

export async function getOrCreateChatWorldbook(name) {
    const existing = getChatLorebook();
    if (existing) return existing;
    const target = String(name || 'ChatWorldbook').trim();
    await createOrReplaceWorldbook(target, []);
    await setChatLorebook(target);
    return target;
}

export async function setCurrentCharLorebooks(books) {
    let names = [];
    if (typeof books === 'string') {
        names = [books];
    } else if (Array.isArray(books)) {
        names = books.map(String).filter(Boolean);
    } else if (books && typeof books === 'object') {
        if (books.primary) names.push(String(books.primary));
        if (Array.isArray(books.additional)) names.push(...books.additional.map(String).filter(Boolean));
    }
    let bridgeHandled = false;
    for (const b of findHostBridges()) {
        try {
            if (typeof b.rebindLorebooks === 'function') {
                bridgeHandled = b.rebindLorebooks('char', JSON.stringify(names)) === true;
                break;
            }
        } catch (_e) { /* noop */ }
    }
    try {
        const ctx = getCtx();
        if (ctx && ctx.world_info && typeof ctx.world_info === 'object') {
            ctx.world_info.charLore = names;
        }
    } catch (_e) { /* noop */ }
    return bridgeHandled || true;
}

export async function rebindCharWorldbooks(...args) {
    return setCurrentCharLorebooks(args[0]);
}

export async function rebindChatWorldbook(...args) {
    return setChatLorebook(args[0]);
}

export async function rebindGlobalWorldbooks(...args) {
    const first = args[0];
    const names = Array.isArray(first) ? first.map(String).filter(Boolean) : (typeof first === 'string' && first ? [first] : []);
    let bridgeHandled = false;
    for (const b of findHostBridges()) {
        try {
            if (typeof b.rebindLorebooks === 'function') {
                bridgeHandled = b.rebindLorebooks('global', JSON.stringify(names)) === true;
                break;
            }
        } catch (_e) { /* noop */ }
    }
    try {
        const ctx = getCtx();
        if (ctx) {
            ctx.selected_world_info = names.slice();
            if (ctx.world_info && typeof ctx.world_info === 'object') {
                ctx.world_info.globalSelect = names.slice();
            }
        }
    } catch (_e) { /* noop */ }
    return bridgeHandled || true;
}
