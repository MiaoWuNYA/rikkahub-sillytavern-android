/**
 * Tavern Runtime — EJS 模板渲染（ST-Prompt-Template 语义）
 * ============================================================
 * 复用 ST-Prompt-Template 内嵌 browserify EJS（Apache-2.0）：
 *   vendor/lib/ejs.umd.js → window.ejs
 * 核心流程对齐其 src/function/ejs.ts evalTemplate()：
 *   ejs.compile(content, { async: true, outputFunctionName: 'print', _with: true, localsName: 'locals', client: true })
 *   → func.call(data, data, escaper, includer, rethrow)
 * 上下文 API 对齐其 prepareContext()（按 rikkaST 能力面映射：变量/世界书/宏）。
 */
import * as shimVariable from './variable-shims.js';
import * as shimLorebook from './lorebook-shims.js';
import * as shimUtil from './util-shims.js';

const ejsLib = (typeof window !== 'undefined' && window.ejs) ? window.ejs : null;

/** XML 转义（对齐 EJS escapeXML；带纯 JS fallback）。 */
const escapeXml = (ejsLib && typeof ejsLib.escapeXML === 'function')
    ? ejsLib.escapeXML
    : (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '"', "'": '&#39;',
    }[c]));

/** include 暂不支持（rikkaST 无文件系统视图）；给出明确报错而非静默。 */
function includer() {
    throw new Error('[EJS] include is not supported in rikkaST runtime');
}
function rethrow(err) { throw err; }

/** EJS 模板快速探测：仅含 `<%` 的文本才需要渲染。 */
export function containsEjs(text) {
    return typeof text === 'string' && text.includes('<%');
}

/** 是否已装载 EJS 库。 */
export function isEjsAvailable() { return !!ejsLib; }

// ── 变量 API（ST-Prompt-Template 风格 scope 语义：local=聊天 / global / message）──

function _scopeOption(scope, messageId) {
    switch (scope) {
        case 'global': return { type: 'global' };
        case 'message': return { type: 'message', message_id: messageId };
        default: return { type: 'chat' };
    }
}

function _getByPath(obj, path) {
    try {
        if (typeof window._ !== 'undefined' && window._.get) return window._.get(obj, path);
        return obj ? obj[path] : undefined;
    } catch (_e) { return undefined; }
}

function _setByPath(obj, path, value) {
    try {
        if (typeof window._ !== 'undefined' && window._.set) { window._.set(obj, path, value); return obj; }
        obj[path] = value; return obj;
    } catch (_e) { return obj; }
}

export function getVar(key, opts = {}) {
    try {
        const store = shimVariable.getVariables(_scopeOption(opts.scope || 'local', opts.message_id));
        let v = _getByPath(store, key);
        if (v === undefined && opts.defaults !== undefined) v = opts.defaults;
        return v;
    } catch (_e) { return opts.defaults; }
}

export function setVar(key, value, opts = {}) {
    try {
        shimVariable.updateVariablesWith(v => _setByPath(v, key, value), _scopeOption(opts.scope || 'local', opts.message_id));
    } catch (_e) { /* noop */ }
    return value;
}

export function incVar(key, delta, opts = {}) {
    const cur = Number(getVar(key, { ...opts, defaults: 0 }));
    const next = (isNaN(cur) ? 0 : cur) + (delta === undefined ? 1 : (Number(delta) || 0));
    return setVar(key, next, opts);
}

/** 构造 EJS 渲染上下文（extras 由 runtime 注入：角色/环境/最后消息等）。 */
export function createEjsApi(extras = {}) {
    const api = {
        // 工具库
        _: (typeof window !== 'undefined') ? window._ : undefined,
        $: (typeof window !== 'undefined') ? window.$ : undefined,
        toastr: (typeof window !== 'undefined') ? window.toastr : undefined,
        console: console,
        // 变量对象式访问：<%- variables.好感度 %>
        get variables() {
            try { return shimVariable.getAllVariables(); } catch (_e) { return {}; }
        },
        // 变量函数式（ST-Prompt-Template 同名 API）
        getvar: getVar,
        setvar: setVar,
        getLocalVar: (k, o = {}) => getVar(k, { ...o, scope: 'local' }),
        setLocalVar: (k, v, o = {}) => setVar(k, v, { ...o, scope: 'local' }),
        getGlobalVar: (k, o = {}) => getVar(k, { ...o, scope: 'global' }),
        setGlobalVar: (k, v, o = {}) => setVar(k, v, { ...o, scope: 'global' }),
        getMessageVar: (k, o = {}) => getVar(k, { ...o, scope: 'message' }),
        setMessageVar: (k, v, o = {}) => setVar(k, v, { ...o, scope: 'message' }),
        incvar: incVar,
        incLocalVar: (k, v, o = {}) => incVar(k, v, { ...o, scope: 'local' }),
        incGlobalVar: (k, v, o = {}) => incVar(k, v, { ...o, scope: 'global' }),
        decvar: (k, v, o = {}) => incVar(k, -(v === undefined ? 1 : (Number(v) || 0)), o),
        decLocalVar: (k, v, o = {}) => incVar(k, -(v === undefined ? 1 : (Number(v) || 0)), { ...o, scope: 'local' }),
        decGlobalVar: (k, v, o = {}) => incVar(k, -(v === undefined ? 1 : (Number(v) || 0)), { ...o, scope: 'global' }),
        // 宏替换
        substitudeMacros: (s) => {
            try { return shimUtil.substitudeMacros ? String(shimUtil.substitudeMacros(String(s))) : String(s); }
            catch (_e) { return String(s); }
        },
        // 斜杠命令（对接宿主 STscript 执行器）
        execute: async (cmd) => {
            try {
                return shimUtil.triggerSlash ? await shimUtil.triggerSlash(String(cmd == null ? '' : cmd)) : '';
            } catch (_e) { return ''; }
        },
        // 世界书条目读取（兼容 getwi(key) 单参数与 ST-PT getwi(book, key) 双参数签名）
        getwi: async (bookOrKey, maybeKey) => {
            try {
                const hasBook = maybeKey !== undefined;
                const bookName = hasBook ? (bookOrKey ? String(bookOrKey) : undefined) : undefined;
                const nameKey = String(hasBook ? maybeKey : bookOrKey);
                const entries = await shimLorebook.getLorebookEntries(bookName, {});
                const list = Array.isArray(entries) ? entries : [];
                const hit = list.find(e => e && (e.name === nameKey
                    || String(e.uid) === nameKey
                    || (Array.isArray(e.strategy && e.strategy.keys) && e.strategy.keys.includes(nameKey))))
                    || list.find(e => e && typeof e.content === 'string' && e.content.includes(nameKey));
                return hit ? hit.content : '';
            } catch (_e) { return ''; }
        },
    };
    // 合并 extras（getchr / 角色名 / 最后消息等由 runtime 注入，可覆盖）
    Object.assign(api, extras || {});
    return api;
}

/**
 * 渲染单个 EJS 模板（对齐 ST-Prompt-Template evalTemplate 核心）。
 * @param {string} content 模板文本
 * @param {Record<string, unknown>} data 上下文
 * @returns {Promise<string>} 渲染结果
 */
export async function renderEjsText(content, data) {
    if (!ejsLib) return content;
    if (!containsEjs(content)) return content;
    const options = {
        async: true,
        outputFunctionName: 'print',
        _with: true,
        localsName: 'locals',
        client: true,
    };
    const func = ejsLib.compile(content, options);
    const result = await func.call(data, data, escapeXml, includer, rethrow);
    return (result === undefined || result === null) ? '' : String(result);
}
