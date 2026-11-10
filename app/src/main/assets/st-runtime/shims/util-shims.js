/**
 * MVU Standalone — 工具函数 Shim
 */

import { eventEmit, eventOn } from './event-shims.js';

// 伪装版本号（>= 4.0.14 以启用所有 MVU 功能）
const SPOOFED_VERSION = '4.7.7';

export function getTavernHelperVersion() {
    return SPOOFED_VERSION;
}

export function getTavernVersion() {
    try {
        return window.SillyTavern.getContext().getVersion?.() ?? '1.13.4';
    } catch {
        return '1.13.4';
    }
}

// 注意: 保留原始拼写错误 "substitude"
export function substitudeMacros(text) {
    try {
        const ctx = window.SillyTavern.getContext();
        return (ctx.substituteParams || ctx.substituteParamsExtended || (t => t))(text);
    } catch {
        return text;
    }
}

export function getLastMessageId() {
    try {
        const chat = window.SillyTavern.getContext().chat;
        return chat.length - 1;
    } catch {
        return -1;
    }
}

// 批次十三：面板 WebView 也要知道「我是哪条消息」。
// Kotlin 侧（MessageHtmlBlock）在挂 HTML 前把对应 MessageNode.id 写进
// window.__rikkaPanelMessageKey；runtime.js 把它换算成 chat 数组下标后存到
// window.__rikkaPanelMessageKeyIndex。这里返回该下标，而不是写死的 -1。
// msg_id=-1 在 JSR 里的含义是「最后一条非系统消息」，对单条消息的面板没意义。
export function getCurrentMessageId() {
    try {
        const k = window.__rikkaPanelMessageKey;
        if (k) {
            const chat = (window.SillyTavern.getContext().chat) || [];
            for (let i = 0; i < chat.length; i++) {
                if (chat[i] && String(chat[i].id) === String(k)) {
                    window.__rikkaPanelMessageKeyIndex = i;
                    return i;
                }
            }
            if (typeof window.__rikkaPanelMessageKeyIndex === 'number') {
                return window.__rikkaPanelMessageKeyIndex;
            }
        }
    } catch (_e) { /* noop */ }
    return getLastMessageId();
}

export function getScriptId() {
    return 'mvu-standalone';
}

export function getScriptName() {
    return 'MVU变量框架';
}

export function getScriptInfo() {
    return 'MVU Standalone Extension';
}

export function replaceScriptInfo(_info) {
    // no-op
}

export function getIframeName() {
    return 'TH-script--MVU变量框架--mvu-standalone';
}

export function reloadIframe() {
    // 触发 MVU iframe 重新加载
    console.warn('[MVU-Standalone] reloadIframe called — not supported in standalone mode');
}

export function errorCatched(fn) {
    return function (...args) {
        try {
            return fn.apply(this, args);
        } catch (e) {
            console.error('[MVU-Standalone] Error:', e);
            if (window.toastr) {
                toastr.error(e.message, '[MVU] 错误');
            }
        }
    };
}

// Global 共享机制
const _globalValues = new Map();
const _globalWaiters = new Map();

export function initializeGlobal(name, value) {
    _globalValues.set(name, value);
    // 设置到 iframe 自身
    window[name] = value;
    // 设置到 parent（SillyTavern 主窗口）
    try {
        window.parent[name] = value;
    } catch { /* cross-origin fallback */ }

    eventEmit(`global_${name}_initialized`);

    // 解决等待者
    const waiters = _globalWaiters.get(name);
    if (waiters) {
        for (const resolve of waiters) resolve(value);
        _globalWaiters.delete(name);
    }
}

export function waitGlobalInitialized(name) {
    // 检查 iframe 自身
    if (window[name] !== undefined) return Promise.resolve(window[name]);
    // 检查 parent
    try {
        if (window.parent[name] !== undefined) return Promise.resolve(window.parent[name]);
    } catch { /* cross-origin */ }
    // 检查已注册
    if (_globalValues.has(name)) return Promise.resolve(_globalValues.get(name));

    return new Promise(resolve => {
        if (!_globalWaiters.has(name)) _globalWaiters.set(name, []);
        _globalWaiters.get(name).push(resolve);

        // 也监听事件
        eventOn(`global_${name}_initialized`, () => {
            resolve(window[name] ?? window.parent?.[name]);
        });
    });
}

export function registerMacroLike(_name, _fn) {
    // MVU 不使用这个，但提供空实现避免报错
    console.debug('[MVU-Standalone] registerMacroLike called (no-op)');
}

/**
 * JSR `triggerSlash`：在宿主执行一行 STscript，返回管道结果（Promise<string>）。
 *
 * [v243 BUGFIX A1] 必须以**方法形式**调用宿主桥，并允许回退到父窗口。
 *
 * 真机取证（tavern-runtime.log 2026-10-13 09:59:57；MiniMax 版 18:52:43 同一现场）：
 *   [warn] [RikkaTavern] triggerSlash failed: Error: Error invoking triggerSlash:
 *          Java bridge method can't be invoked on a non-injected object
 *       at https://appassets.androidplatform.net/st-runtime/shims/util-shims.js:161:28
 *       at async mt (<anonymous>:1:13320)
 *
 * 旧写法 `const fn = window.RikkaBridge.triggerSlash; fn(cmd)` 把方法从注入对象上摘下来再调用，
 * this 丢失。Android WebView 的 @JavascriptInterface 代理对象要求 this 就是被注入的那个对象，
 * 本文件是 ES module（严格模式）→ this === undefined → Java 层直接抛
 * "can't be invoked on a non-injected object"。旧实现 catch 后返回 ''，
 * 于是这条 STscript 静默不执行 —— 卡片「点击类回复 / 该自动发送的内容」就是这么没的。
 *
 * 修法：保留接收者（b.triggerSlash(cmd)）；本窗口桥不可用时回退 window.parent（blob/子 iframe 场景）。
 * 两次都失败才返回 ''。
 */
export function triggerSlash(command) {
    return Promise.resolve().then(() => {
        const cmd = String(command ?? '');
        const bridges = [];
        try { if (window.RikkaBridge) bridges.push(window.RikkaBridge); } catch (_e) { /* noop */ }
        try {
            const p = window.parent;
            if (p && p !== window && p.RikkaBridge) bridges.push(p.RikkaBridge);
        } catch (_e) { /* noop */ }
        for (const b of bridges) {
            try {
                if (typeof b.triggerSlash !== 'function') continue;
                // 严禁改回 `const fn = b.triggerSlash; fn(cmd)`（丢 this = 必现 non-injected object）
                const result = b.triggerSlash(cmd);
                return result === undefined || result === null ? '' : String(result);
            } catch (e) {
                console.warn('[RikkaTavern] triggerSlash failed on bridge:', e);
            }
        }
        if (bridges.length === 0) console.warn('[RikkaTavern] triggerSlash: RikkaBridge 不可用');
        return '';
    });
}
// ================================================================
// [v243 BUGFIX C2] 扩展安装状态 / 角色信息 / 常用别名
//
// 为什么补这些：卡脚本判断「酒馆助手装没装」除了 getExtensionManifest()，
// 还常用 JSR 的 `isInstalledExtension()` / `getExtensionType()`（见
// JS-Slash-Runner/src/function/extension.ts:24-46）。rikkaST 之前一个都没有 →
// 卡直接 ReferenceError 或者静默判成「未安装」，正是用户报的
// 「明明装了插件却检测不到」。
// 这里只做**真实可查**的那部分：查已加载的第三方扩展清单；
// 需要联网/管理员的安装卸载一律明确拒绝，不伪造成功。
// ================================================================

/** 取已加载的第三方扩展清单（本窗口优先，其次父窗口 —— 面板/脚本 iframe 都能查到）。 */
function __rikkaExtensionList() {
    const pick = (w) => {
        try {
            const st = w && w.__rikkaSt;
            return (st && Array.isArray(st.extensions)) ? st.extensions : null;
        } catch (_e) { return null; }
    };
    return pick(window) || pick(window.parent) || [];
}

/** JSR `getTavernHelperExtensionId()`。 */
export function getTavernHelperExtensionId() {
    return 'JS-Slash-Runner';
}

/**
 * JSR `getExtensionType(extension_id)` → 'local' | 'global' | 'system' | null。
 * rikkaST 目前只区分「已加载的第三方扩展」（local）；系统扩展无对应概念，返回 null。
 * 匹配规则对齐 JSR：完全相等，或 folder 以给定 id 结尾（JSR 用 `key.endsWith(id)`）。
 */
export function getExtensionType(extension_id) {
    const want = String(extension_id == null ? '' : extension_id).trim().toLowerCase();
    if (!want) return null;
    const list = __rikkaExtensionList();
    for (const e of list) {
        if (!e) continue;
        const folder = String(e.folder || '').trim().toLowerCase();
        const name = String(e.displayName || e.name || '').trim().toLowerCase();
        if (folder === want || name === want || (folder && folder.endsWith(want))) return 'local';
    }
    return null;
}

/** JSR `isInstalledExtension(extension_id)` → boolean。 */
export function isInstalledExtension(extension_id) {
    return getExtensionType(extension_id) !== null;
}

/**
 * JSR `getExtensionStatus`（= getExtensionInstallationInfo）需要向酒馆后端查 git 版本信息，
 * rikkaST 没有这条通路 → 明确返回 null（调用方一般按「查不到就不提示更新」处理）。
 */
export async function getExtensionStatus(extension_id) {
    console.debug('[RikkaTavern] getExtensionStatus: 宿主无扩展版本查询通道，返回 null', extension_id);
    return null;
}

/** 安装/卸载/重装/更新扩展需要酒馆后端与管理权限 —— rikkaST 不支持，明确拒绝而不是伪造成功。 */
function __rikkaUnsupportedExtensionOp(name) {
    console.warn('[RikkaTavern] ' + name + ': rikkaST 不支持从脚本安装/卸载扩展，请在 App 的扩展设置里操作');
    return Promise.resolve(null);
}
export function installExtension() { return __rikkaUnsupportedExtensionOp('installExtension'); }
export function uninstallExtension() { return __rikkaUnsupportedExtensionOp('uninstallExtension'); }
export function reinstallExtension() { return __rikkaUnsupportedExtensionOp('reinstallExtension'); }
export function updateExtension() { return __rikkaUnsupportedExtensionOp('updateExtension'); }
export function isAdmin() { return true; }

// ---------------------------------------------------------------- 角色信息（JSR raw_character / character 的最小子集）

function __rikkaCtx() {
    try { return window.SillyTavern.getContext() || {}; } catch (_e) { return {}; }
}

/** JSR `getCurrentCharacterName()`。 */
export function getCurrentCharacterName() {
    const ctx = __rikkaCtx();
    const list = Array.isArray(ctx.characters) ? ctx.characters : [];
    const id = ctx.characterId;
    const c = (id !== undefined && id !== null) ? list[id] : list[0];
    return (c && c.name) ? String(c.name) : null;
}

/** JSR `getCurrentCharacterId()`（ST 里是下标）。 */
export function getCurrentCharacterId() {
    const ctx = __rikkaCtx();
    if (ctx.characterId === undefined || ctx.characterId === null) return 0;
    return ctx.characterId;
}

/** JSR `getCharacterNames()`。 */
export function getCharacterNames() {
    const ctx = __rikkaCtx();
    const list = Array.isArray(ctx.characters) ? ctx.characters : [];
    return list.map((c) => (c && c.name) ? String(c.name) : '');
}

/** JSR `getCharacterIds()`。 */
export function getCharacterIds() {
    const ctx = __rikkaCtx();
    const list = Array.isArray(ctx.characters) ? ctx.characters : [];
    return list.map((_, i) => i);
}

/** JSR `getCharData()`：返回当前角色卡的 ST 形态（宿主 buildCharacterJson 已产出完整形态）。 */
export function getCharData() {
    const ctx = __rikkaCtx();
    const list = Array.isArray(ctx.characters) ? ctx.characters : [];
    const id = ctx.characterId;
    const c = (id !== undefined && id !== null) ? list[id] : list[0];
    return c || null;
}

// ---------------------------------------------------------------- 常用别名（JSR 里是同一份实现，这里保持同义）

/** JSR `getMessageId` === `getCurrentMessageId`（见 JSR function/index.ts 的 _getCurrentMessageId 绑定）。 */
export function getMessageId() {
    return getCurrentMessageId();
}

/** JSR `triggerSlashWithResult` 与 `triggerSlash` 在 JSR 里是同一个函数。 */
export function triggerSlashWithResult(command) {
    return triggerSlash(command);
}

/** JSR 版本别名（`getFrontendVersion` / `updateFrontendVersion` / `updateTavernHelper`）。 */
export function getFrontendVersion() {
    return getTavernHelperVersion();
}

export async function updateFrontendVersion() {
    console.warn('[RikkaTavern] updateFrontendVersion: rikkaST 不支持脚本自更新，请用 App 内的检查更新');
    return null;
}

export async function updateTavernHelper() {
    return updateFrontendVersion();
}

/** JSR `registerVariableSchema` / `unregisterMacroLike`：rikkaST 无对应机制，空实现 + 明确告警。 */
export function registerVariableSchema() {
    console.debug('[RikkaTavern] registerVariableSchema: 宿主无 schema 校验，已忽略');
}

export function unregisterMacroLike() {
    console.debug('[RikkaTavern] unregisterMacroLike: 宿主无自定义宏注册，已忽略');
}
