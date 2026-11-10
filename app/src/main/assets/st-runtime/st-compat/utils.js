/**
 * rikkaST st-compat: utils.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/utils.js` 的导出面（常用工具函数真实现）。
 */

const st = () => (typeof window !== 'undefined' && window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

// ---------------------------------------------------------------- 基础工具
export function debounce(fn, wait = 300) {
    let timer = null;
    const wrapped = (...args) => {
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => { timer = null; try { fn(...args); } catch (_e) { /* noop */ } }, wait);
    };
    wrapped.cancel = () => { if (timer) { clearTimeout(timer); timer = null; } };
    wrapped.flush = (...args) => { if (timer) { clearTimeout(timer); timer = null; } try { fn(...args); } catch (_e) { /* noop */ } };
    return wrapped;
}

export function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, Math.max(0, Number(ms) || 0)));
}

export function getSortableDelay() {
    return 0;
}

export function uuidv4() {
    try {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
        if (typeof window !== 'undefined' && window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
    } catch (_e) { /* noop */ }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

/** 与 ST 语义一致的字符串哈希（cyrb53 变体）。 */
export function getStringHash(str, seed = 0) {
    let h1 = 0xdeadbeef ^ seed;
    let h2 = 0x41c6ce57 ^ seed;
    const s = String(str == null ? '' : str);
    for (let i = 0; i < s.length; i++) {
        const ch = s.charCodeAt(i);
        h1 = Math.imul(h1 ^ ch, 2654435761);
        h2 = Math.imul(h2 ^ ch, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
}

export function isDataURL(value) {
    return /^data:/i.test(String(value == null ? '' : value));
}

export function getSanitizedFilename(filename) {
    return String(filename == null ? '' : filename).replace(/[\/\\:*?"<>|\u0000-\u001f]/g, '_').trim() || 'file';
}

export function getImageSizeFromDataURL(dataUrl) {
    return new Promise((resolve) => {
        try {
            const img = new Image();
            img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight });
            img.onerror = () => resolve({ width: 0, height: 0 });
            img.src = dataUrl;
        } catch (_e) {
            resolve({ width: 0, height: 0 });
        }
    });
}

export async function getBase64Async(file) {
    try {
        return await new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = reject;
            reader.readAsDataURL(file);
        });
    } catch (_e) {
        return '';
    }
}

export async function getFileText(file) {
    try {
        if (!file) return '';
        if (typeof file === 'string') return file;
        if (typeof file.text === 'function') return await file.text();
        if (typeof FileReader !== 'undefined') {
            return await new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = () => resolve(String(reader.result ?? ''));
                reader.onerror = reject;
                reader.readAsText(file);
            });
        }
    } catch (_e) { /* noop */ }
    return '';
}

export async function extractTextFromOffice(file) {
    return getFileText(file);
}

export function ensureImageFormatSupported(file) {
    // P0：不做格式转换，原样返回（ST 在浏览器端做 PNG 转换）。
    return file;
}

// ---------------------------------------------------------------- 文件 / 剪贴板
/** P0：宿主文件系统下载未接线 → 返回空（调用方通常忽略返回值）。 */
export async function saveBase64AsFile(base64Data, characterName, filename, extension, ...rest) {
    void base64Data; void characterName; void filename; void extension; void rest;
    return '';
}

export async function download(data, filename) {
    try {
        const blob = (data instanceof Blob) ? data : new Blob([data]);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = String(filename || 'download');
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 5000);
    } catch (_e) { /* noop */ }
}

export async function copyText(text) {
    try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            await navigator.clipboard.writeText(String(text == null ? '' : text));
            return true;
        }
    } catch (_e) { /* noop */ }
    return false;
}

// ---------------------------------------------------------------- 角色
export function getCharaFilename(chid) {
    try {
        const c = st();
        const list = c.characters || [];
        const index = (typeof chid === 'number') ? chid : (typeof c.characterId === 'number' ? c.characterId : 0);
        const ch = list[index];
        if (!ch) return '';
        const name = ch.avatar || ch.name || '';
        return String(name).replace(/\.[^.]+$/, '');
    } catch (_e) {
        return '';
    }
}

export function findChar(avatar, data) {
    try {
        const list = (data && Array.isArray(data)) ? data : (st().characters || []);
        return list.find((c) => c && (c.avatar === avatar || c.name === avatar)) || null;
    } catch (_e) {
        return null;
    }
}

// ---------------------------------------------------------------- 正则与安全辅助
/**
 * 提取模式开头的一个或多个 PCRE 内联标志组（如 `(?i)`, `(?is)`, `(?i)(?m)`），返回 `{ body, flags }`。
 */
export function extractInlineRegexFlags(rawBody) {
    let body = String(rawBody ?? '');
    let flags = '';
    while (true) {
        const m = body.match(/^\(\?([imsug]+)\)/i);
        if (!m) break;
        body = body.slice(m[0].length);
        flags += m[1].toLowerCase();
    }
    return { body, flags };
}

export const MIN_CATASTROPHIC_INPUT_CHARS = 16;
export const MAX_CATASTROPHIC_INPUT_CHARS = 4096;

function hasRepeatableQuantifierAt(str, idx) {
    if (idx >= str.length) return false;
    const ch = str[idx];
    if (ch === '+' || ch === '*') return true;
    if (ch === '{') {
        const close = str.indexOf('}', idx + 1);
        if (close > idx + 1) {
            const inner = str.slice(idx + 1, close);
            const m = inner.match(/^(\d+)(?:,(\d*))?$/);
            if (m) {
                if (m[2] === undefined) return Number(m[1]) >= 2;
                if (m[2] === '') return true;
                return Number(m[2]) >= 2;
            }
        }
    }
    return false;
}

function hasInnerRepeatingToken(groupBody) {
    for (let i = 0; i < groupBody.length; i++) {
        const c = groupBody[i];
        if (c === '\\') {
            i++;
            continue;
        }
        if (c === '+' || c === '*') return true;
        if (c === '{') {
            const close = groupBody.indexOf('}', i + 1);
            if (close > i + 1) {
                const inner = groupBody.slice(i + 1, close);
                const m = inner.match(/^(\d+)(?:,(\d*))?$/);
                if (m) {
                    const minVal = Number(m[1]);
                    if (m[2] === '') return true;
                    if (m[2] !== undefined) {
                        const maxVal = Number(m[2]);
                        if (maxVal >= 2 && (minVal !== maxVal || maxVal >= 10)) return true;
                    } else if (minVal >= 10) {
                        return true;
                    }
                }
            }
        }
    }
    return false;
}

function splitTopLevelAlternation(groupBody) {
    const branches = [];
    let depth = 0;
    let inCharClass = false;
    let start = 0;
    for (let i = 0; i < groupBody.length; i++) {
        const c = groupBody[i];
        if (c === '\\') {
            i++;
            continue;
        }
        if (inCharClass) {
            if (c === ']') inCharClass = false;
            continue;
        }
        if (c === '[') {
            inCharClass = true;
            continue;
        }
        if (c === '(') depth++;
        else if (c === ')') depth = Math.max(0, depth - 1);
        else if (c === '|' && depth === 0) {
            branches.push(groupBody.slice(start, i));
            start = i + 1;
        }
    }
    branches.push(groupBody.slice(start));
    return branches;
}

function firstAtomSignature(branch) {
    const b = branch.replace(/^\(\?:|\(\?<[^>]+>|\(\?[imsug]+:/gi, '').trim();
    if (!b) return '*';
    if (b.startsWith('.') || b.startsWith('\\w') || b.startsWith('\\S') || b.startsWith('[\\s\\S]')) return 'ANY_WORD';
    if (b.startsWith('\\d')) return 'DIGIT';
    if (b.startsWith('[')) {
        const close = b.indexOf(']');
        if (close > 1) {
            const cls = b.slice(1, close);
            if (cls.includes('a-z') || cls.includes('A-Z') || cls.includes('\\w')) return 'ALPHA_OR_WORD';
            if (cls.includes('0-9') || cls.includes('\\d')) return 'DIGIT';
            return 'CLASS:' + cls[0];
        }
    }
    return 'LIT:' + b[0];
}

function hasOverlappingAlternation(groupBody) {
    const branches = splitTopLevelAlternation(groupBody);
    if (branches.length < 2) return false;
    for (const rawBranch of branches) {
        const strippedBranch = rawBranch.replace(/\\./g, 'X').replace(/\[(?:\\.|[^\]\\])*\]/g, 'C');
        if (/[+*?]/.test(strippedBranch)) return true;
    }
    const sigs = branches.map(firstAtomSignature);
    for (let i = 0; i < sigs.length; i++) {
        for (let j = i + 1; j < sigs.length; j++) {
            const a = sigs[i];
            const b = sigs[j];
            if (a === b || a === '*' || b === '*') return true;
            if ((a === 'ANY_WORD' || a === 'ALPHA_OR_WORD') && (b === 'ANY_WORD' || b === 'ALPHA_OR_WORD' || b === 'DIGIT')) {
                return true;
            }
        }
    }
    return false;
}

/**
 * 结构化检测可能导致指数级回溯（ReDoS）的病态正则模式：
 * 1. 任意嵌套层级的重复分组内含重复量词（如 `/(a+)+b/`, `/((a+))+b/`, `/(?:a|(?:a+))+b/`, `/(a+){1,50}b/`）；
 * 2. 重复分组内含重叠分支交替（如 `/(a|aa)+b/`, `/(a|a?)+b/`, `/([a-z]+|[a-z0-9]+)+!/`）。
 */
export function isLikelyCatastrophicRegex(pattern) {
    if (typeof pattern !== 'string' || pattern.length < 4) return false;
    const body = pattern.startsWith('/') && pattern.lastIndexOf('/') > 0
        ? pattern.slice(1, pattern.lastIndexOf('/'))
        : pattern;
    const stack = [];
    let inCharClass = false;
    for (let i = 0; i < body.length; i++) {
        const c = body[i];
        if (c === '\\') {
            i++;
            continue;
        }
        if (inCharClass) {
            if (c === ']') inCharClass = false;
            continue;
        }
        if (c === '[') {
            inCharClass = true;
            continue;
        }
        if (c === '(') {
            stack.push(i);
        } else if (c === ')' && stack.length > 0) {
            const openIdx = stack.pop();
            let afterClose = i + 1;
            while (afterClose < body.length && body[afterClose] === ' ') afterClose++;
            if (hasRepeatableQuantifierAt(body, afterClose)) {
                let inner = body.slice(openIdx + 1, i);
                if (inner.startsWith('?:')) inner = inner.slice(2);
                else if (inner.startsWith('?<') && inner.indexOf('>') > 0) inner = inner.slice(inner.indexOf('>') + 1);
                if (hasInnerRepeatingToken(inner) || hasOverlappingAlternation(inner)) {
                    return true;
                }
            }
        }
    }
    return false;
}

/**
 * 剥离消息首尾未配对的 `〔`/`〕`、`〖`/`〗` 边界包装符，同时完整保留句内正常成对使用的括号（如“她翻开〔世界史〕笔记本”）。
 */
export function stripUnmatchedBoundaryWrappers(input) {
    let out = String(input ?? '');
    for (const [openCh, closeCh] of [['〔', '〕'], ['〖', '〗']]) {
        let openCount = 0;
        let closeCount = 0;
        for (let i = 0; i < out.length; i++) {
            if (out[i] === openCh) openCount++;
            else if (out[i] === closeCh) closeCount++;
        }
        if (openCount === closeCount) continue;
        const trimmed = out.trim();
        if (openCount === 1 && closeCount === 0 && trimmed.startsWith(openCh)) {
            const idx = out.indexOf(openCh);
            if (idx >= 0) {
                out = (out.slice(0, idx) + out.slice(idx + 1)).replace(/^[\r\n]+/, '');
            }
        } else if (closeCount === 1 && openCount === 0 && trimmed.endsWith(closeCh)) {
            const idx = out.lastIndexOf(closeCh);
            if (idx >= 0) {
                out = (out.slice(0, idx) + out.slice(idx + 1)).replace(/[\r\n]+$/, '');
            }
        }
    }
    return out;
}

/**
 * 执行与 ST 及 Kotlin `RegexScriptEngine.buildReplacement` 100% 对齐的单趟替换（支持 `$$`, `$&`, `{{match}}`, `$0..$99`, `$<name>` 与 `trimStrings`）。
 */
export const isCatastrophicRegexPattern = isLikelyCatastrophicRegex;

export function replaceCompatString(rawString, findRegex, replaceString, trimStrings = [], substituteFn = (s) => s) {
    const src = String(rawString ?? '');
    if (!src) return src;
    const rx = findRegex instanceof RegExp ? findRegex : regexFromString(findRegex);
    if (!(rx instanceof RegExp)) return src;
    const patStr = findRegex instanceof RegExp ? `/${findRegex.source}/${findRegex.flags}` : String(findRegex ?? '');
    if (isLikelyCatastrophicRegex(patStr) && src.length >= MIN_CATASTROPHIC_INPUT_CHARS) {
        return src;
    }
    const rawReplace = String(replaceString == null ? '' : replaceString);
    const trims = Array.isArray(trimStrings) ? trimStrings : [];
    const applyTrim = (val) => {
        let out = String(val ?? '');
        for (const t of trims) {
            const sub = substituteFn(String(t ?? ''));
            if (sub) out = out.replaceAll(sub, '');
        }
        return out;
    };
    return src.replace(rx, function (fullMatch) {
        const args = [...arguments];
        const hasNamedGroups = typeof args[args.length - 1] === 'object' && args[args.length - 1] !== null;
        const offsetArgIdx = hasNamedGroups ? args.length - 3 : args.length - 2;
        const groupsObj = hasNamedGroups ? args[args.length - 1] : null;
        const replaced = rawReplace.replaceAll(
            /\{\{match\}\}|\$\$|\$&|\$(\d+)|\$<([^>]+)>/gi,
            (token, num, groupName) => {
                if (token === '$$') return '$';
                let target = undefined;
                if (token === '$&' || token.toLowerCase() === '{{match}}') {
                    target = fullMatch;
                } else if (num !== undefined) {
                    const idx = Number(num);
                    target = idx === 0 ? fullMatch : (idx >= 1 && idx < offsetArgIdx ? args[idx] : undefined);
                } else if (groupName !== undefined) {
                    target = groupsObj ? groupsObj[groupName] : undefined;
                }
                if (target === undefined || target === null || target === '') return '';
                return applyTrim(target);
            },
        );
        return substituteFn(replaced);
    });
}

// ---------------------------------------------------------------- 其他
/** 解析 `/pattern/flags` 形式的正则字符串（对齐 ST utils.regexFromString）。 */
export function regexFromString(input, extraFlags = '') {
    try {
        const raw = String(input ?? '');
        if (!raw) return undefined;
        let body = raw;
        let flags = '';
        const slashMatch = raw.match(/^\/([\s\S]*)\/([a-zA-Z]*)$/);
        if (slashMatch) {
            body = slashMatch[1];
            flags = slashMatch[2] || '';
        } else {
            // Un-delimited regex strings default to global matching for parity with Kotlin RegexScriptEngine.isGlobalPattern
            flags = 'g';
        }
        // Extract leading PCRE-style inline flags e.g. (?i), (?is), (?ims) for JS RegExp compatibility
        const extracted = extractInlineRegexFlags(body);
        body = extracted.body;
        flags += extracted.flags;
        if (extraFlags) {
            flags += String(extraFlags);
        }
        const validFlags = Array.from(new Set(
            flags.split('').filter((c) => 'gimsuy'.includes(c))
        )).join('');
        return new RegExp(body, validFlags);
    } catch (_e) {
        return undefined;
    }
}

export class Stopwatch {
    constructor() { this.startTime = performance.now(); }
    get elapsed() { return performance.now() - this.startTime; }
    reset() { this.startTime = performance.now(); }
}

export function showFontAwesomePicker() {}

// ---- [ST 1.19.0 Full Export Parity] ----
export const PAGINATION_TEMPLATE = '<%= rangeStart %>-<%= rangeEnd %> .. <%= totalNumber %>';
export class RateLimiter { constructor(limit = 10, intervalMs = 1000) { this.limit = limit; this.intervalMs = intervalMs; } async throttle() {} }
export function addLongPressEvent(..._args) { return null; }
export function arraysEqual(a, b) { if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false; return a.every((v, i) => v === b[i]); }
export async function bufferToBase64(..._args) { return null; }
export function calculateThumbnailSize(..._args) { return null; }
export function canUseNegativeLookbehind() { return true; }
export function cancelDebounce(..._args) { return null; }
export async function checkOverwriteExistingData(..._args) { return null; }
export function clamp(val, min, max) { return Math.min(Math.max(Number(val) || 0, Number(min) || 0), Number(max) || 0); }
export function clearInfoBlock(..._args) { return null; }
export function collapseSpaces(s) { return String(s ?? '').replace(/\s+/g, ' ').trim(); }
export function compareIgnoreCaseAndAccents(a, b) { return String(a ?? '').localeCompare(String(b ?? ''), undefined, { sensitivity: 'base' }); }
export async function convertImageFile(..._args) { return null; }
export function convertTextToBase64(..._args) { return null; }
export function convertValueType(..._args) { return null; }
export function countOccurrences(..._args) { return null; }
export function createThumbnail(..._args) { return null; }
export function createTimeout(..._args) { return null; }
export function debounceAsync(fn) { return typeof fn === 'function' ? fn : (async () => {}); }
export function debouncedThrottle(fn) { return typeof fn === 'function' ? fn : (() => {}); }
export function deepMerge(target = {}, source = {}) { const out = Object.assign({}, target); if (source && typeof source === 'object') { for (const [k, v] of Object.entries(source)) { if (v && typeof v === 'object' && !Array.isArray(v) && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k])) out[k] = deepMerge(out[k], v); else out[k] = v; } } return out; }
export function deleteValueByPath(..._args) { return null; }
export function dynamicSelect2DataViaAjax(..._args) { return null; }
export function ensurePlainObject(x) { return (x && typeof x === 'object' && !Array.isArray(x)) ? x : {}; }
export function equalsIgnoreCaseAndAccents(a, b) { return String(a ?? '').localeCompare(String(b ?? ''), undefined, { sensitivity: 'base' }) === 0; }
export function escapeHtml(s) { return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;'); }
export function escapeRegex(s) { return String(s ?? '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
export function extractAllWords(..._args) { return null; }
export function extractDataFromPng(..._args) { return null; }
export async function extractTextFromEpub(..._args) { return null; }
export async function extractTextFromHTML(..._args) { return null; }
export async function extractTextFromMarkdown(..._args) { return null; }
export async function extractTextFromPDF(..._args) { return null; }
export async function fetchFa(..._args) { return null; }
export async function fetchFaFile(..._args) { return null; }
export function findPersona(..._args) { return null; }
export function flashHighlight(..._args) { return null; }
export function formatTime(..._args) { return null; }
export function getAudioDurationFromDataURL(..._args) { return null; }
export function getCharIndex(..._args) { return null; }
export function getFileBuffer(..._args) { return null; }
export function getFileExtension(..._args) { return null; }
export function getFreeName(..._args) { return null; }
export async function getReadableText(..._args) { return null; }
export function getSelect2OptionId(..._args) { return null; }
export function getUniqueName(..._args) { return null; }
export function getVideoDurationFromDataURL(..._args) { return null; }
export function getVideoThumbnail(..._args) { return null; }
export function hasAnimation(..._args) { return null; }
export function highlightRegex(..._args) { return null; }
export function humanFileSize(..._args) { return null; }
export async function importFromExternalUrl(..._args) { return null; }
export function includesIgnoreCaseAndAccents(haystack, needle) { const norm = (s) => String(s ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(); return norm(haystack).includes(norm(needle)); }
export function incrementString(..._args) { return null; }
export async function initScrollHeight(..._args) { return null; }
export function isDigitsOnly(s) { return /^\d+$/.test(String(s ?? '')); }
export function isElementInViewport(..._args) { return null; }
export function isExternalUrl(s) { return /^https?:\/\//i.test(String(s ?? '')); }
export function isFalseBoolean(v) { const s = String(v ?? '').trim().toLowerCase(); return s === 'false' || s === '0' || s === 'off' || s === 'no'; }
export function isObject(x) { return Boolean(x && typeof x === 'object' && !Array.isArray(x)); }
export function isOdd(n) { return Math.abs(Number(n) % 2) === 1; }
export function isSameFile(..._args) { return null; }
export function isSelect2ChoiceElement(..._args) { return null; }
export function isSubsetOf(..._args) { return null; }
export function isTrueBoolean(v) { const s = String(v ?? '').trim().toLowerCase(); return s === 'true' || s === '1' || s === 'on' || s === 'yes'; }
export function isUuid(s) { return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(String(s ?? '')); }
export function isValidUrl(s) { try { new URL(String(s)); return true; } catch (_e) { return false; } }
export function loadFileToDocument(..._args) { return null; }
export const localizePagination = function (container) { void container; };
export function logSlashCommandWarn(..._args) { return null; }
export const navigation_option = { none: -2000, previous: -1000 };
export function normalizeArray(..._args) { return null; }
export function onlyUnique(value, index, array) { return Array.isArray(array) ? array.indexOf(value) === index : true; }
export function onlyUniqueJson(value, index, array) { if (!Array.isArray(array)) return true; const s = JSON.stringify(value); return array.findIndex((x) => JSON.stringify(x) === s) === index; }
export const paginationDropdownChangeHandler = function (event, size) { void event; void size; };
export async function parseJsonFile(..._args) { return null; }
export function parseStringArray(..._args) { return null; }
export async function promptForAvatarFile(..._args) { return null; }
export function removeFromArray(arr, item) { if (!Array.isArray(arr)) return -1; const i = arr.indexOf(item); if (i >= 0) arr.splice(i, 1); return i; }
export const renderPaginationDropdown = function (pageSize, sizeChangerOptions) { void pageSize; void sizeChangerOptions; return ''; };
export async function resetScrollHeight(..._args) { return null; }
export async function resolveAvatarData(..._args) { return null; }
export function restoreCaretPosition(..._args) { return null; }
export function runAfterAnimation(..._args) { return null; }
export function sanitizeSelector(..._args) { return null; }
export function saveCaretPosition(..._args) { return null; }
export function select2ChoiceClickSubscribe(..._args) { return null; }
export function select2ModifyOptions(..._args) { return null; }
export function setDatasetProperty(..._args) { return null; }
export function setInfoBlock(..._args) { return null; }
export function setValueByPath(..._args) { return null; }
export function setupScrollToTop(..._args) { return null; }
export function shakeElement(..._args) { return null; }
export const shiftDownByOne = (e, i, a) => a[i] = e - 1;
export const shiftUpByOne = (e, i, a) => a[i] = e + 1;
export function shuffle(..._args) { return null; }
export function sortByCssOrder(..._args) { return null; }
export function sortIgnoreCaseAndAccents(a, b) { return compareIgnoreCaseAndAccents(a, b); }
export function sortMoments(..._args) { return null; }
export function splitRecursive(..._args) { return null; }
export function stringFormat(..._args) { return null; }
export function stringToRange(..._args) { return null; }
export const supportedImageMimeTypes = Object.freeze(['image/jpeg', 'image/png', 'image/bmp', 'image/tiff', 'image/gif', 'image/apng', 'image/webp', 'image/avif']);
export function textValueMatcher(..._args) { return null; }
export function throttle(fn) { return typeof fn === 'function' ? fn : (() => {}); }
export function timestampToMoment(..._args) { return null; }
export function toggleDrawer(..._args) { return null; }
export function trimSpaces(s) { return String(s ?? '').trim(); }
export function trimToEndSentence(..._args) { return null; }
export function trimToStartSentence(..._args) { return null; }
export async function urlContentToDataUri(..._args) { return null; }
export function versionCompare(a = '0.0.0', b = '0.0.0') { const pa = String(a).split('.').map((x) => parseInt(x, 10) || 0); const pb = String(b).split('.').map((x) => parseInt(x, 10) || 0); for (let i = 0; i < Math.max(pa.length, pb.length); i++) { const d = (pa[i] || 0) - (pb[i] || 0); if (d !== 0) return d > 0 ? 1 : -1; } return 0; }
export async function waitUntilCondition(condFn, timeout = 1000, interval = 50) { const start = Date.now(); while (Date.now() - start < timeout) { if (await condFn()) return true; await new Promise((r) => setTimeout(r, interval)); } return Boolean(await condFn()); }
