/**
 * rikkaST st-compat: tokenizers.js
 * ============================================================
 * [v234 C1] 真分词实现（替换 4字符≈1token 估算）。
 *
 * 词表数据：OpenAI 官方 .tiktoken 格式（MIT，niieani/gpt-tokenizer 仓库 data/ 目录原样拷贝），
 * 经 runtime 的 WebView fetch("/npm/../data/xxx.tiktoken") 由资产拦截器伺服；
 * BPE 合并算法为对 tiktoken 语义的独立实现（min-rank 迭代合并）。
 *
 * 导出面与 SillyTavern public/scripts/tokenizers.js 的常用子集一致：
 *   getTokenCount / getTokenCountAsync / tokenize
 *   （扩展可用 import { getTokenCount } from '../../../scripts/tokenizers.js'）
 */

const BPE_DATA_BASE = "/st-runtime/vendor/data/";
const CACHE_MAX_TEXT = 300;          // 缓存条数护栏
const CACHE_MAX_LEN = 65536;         // 超长文本不缓存（防内存膨胀）

const rankCaches = {};               // encoding -> { map: Map<tokenStr,rank>, loaded: bool }
const countCache = new Map();        // "enc\x00text" -> count（LRU 语义：超限全清重建）

function isUvd(c) {
    const v = c.codePointAt(0);
    return (v >= 0x41 && v <= 0x5A) || (v >= 0x61 && v <= 0x7A);
}
function isUvdNum(c) {
    const v = c.codePointAt(0);
    return v >= 0x30 && v <= 0x39;
}
function isUvdSpace(c) {
    return c === " " || c === "\t" || c === "\n" || c === "\r" || c === "\v" || c === "\f";
}

/** CL100K 分割（对齐 CL100K_TOKEN_SPLIT_PATTERN 语义，Astral 类别以 ASCII 近似 + Unicode 感知回退） */
function splitTextCl100k(text) {
    const out = [];
    let i = 0;
    const n = text.length;
    const contr = /^(?:'[sS]|'[dD]|'[mM]|'[tT]|'[lL][lL]|'[vV][eE]|'[rR][eE])/;
    while (i < n) {
        const rest = text.slice(i);
        let m = contr.exec(rest);
        if (m) { out.push(m[0]); i += m[0].length; continue; }
        const c = text[i];
        const cp = text.codePointAt(i);
        const isLetter = /[\p{L}\p{M}]/u.test(c);
        const isNum = /\p{N}/u.test(c);
        // [^\r\n\p{L}\p{N}]?\p{L}+
        if (isLetter) {
            let j = i;
            while (j < n && /[\p{L}\p{M}]/u.test(text[j])) j++;
            out.push(text.slice(i, j));
            i = j;
            continue;
        }
        if (!c || /[\p{L}\p{N}]/u.test(c) === false && c !== "\r" && c !== "\n") {
            // 可选前置非字母数字符 + 字母串
            if (c !== "\r" && c !== "\n" && !isNum) {
                let j = i + 1;
                while (j < n && /[\p{L}\p{M}]/u.test(text[j])) j++;
                if (j > i + 1) { out.push(text.slice(i, j)); i = j; continue; }
            }
        }
        // \p{N}{1,3}
        if (isNum) {
            let j = i;
            while (j < n && /\p{N}/u.test(text[j]) && j - i < 3) j++;
            out.push(text.slice(i, j));
            i = j;
            continue;
        }
        // " ?[^\s\p{L}\p{N}]+[\r\n]*"
        if (isUvdSpace(c) === false && !isLetter && !isNum) {
            let j = i;
            while (j < n && !isUvdSpace(text[j]) && !/[\p{L}\p{N}]/u.test(text[j])) j++;
            while (j < n && (text[j] === "\r" || text[j] === "\n")) j++;
            out.push(text.slice(i, j));
            i = j;
            continue;
        }
        // 空白族：\s+$ / \s*[\r\n] / \s+(?!\S) / \s
        if (isUvdSpace(c)) {
            let j = i;
            while (j < n && isUvdSpace(text[j])) j++;
            const run = text.slice(i, j);
            const hasNl = /[\r\n]/.test(run);
            if (hasNl) {
                // 按换行切分：\s*[\r\n] 优先
                let k = 0;
                while (k < run.length) {
                    let k2 = k;
                    while (k2 < run.length && isUvdSpace(run[k2]) && run[k2] !== "\r" && run[k2] !== "\n") k2++;
                    if (k2 < run.length && (run[k2] === "\r" || run[k2] === "\n")) {
                        if (run[k2] === "\r" && k2 + 1 < run.length && run[k2 + 1] === "\n") { out.push(run.slice(k, k2 + 2)); k = k2 + 2; }
                        else { out.push(run.slice(k, k2 + 1)); k = k2 + 1; }
                    } else {
                        // 尾部空白块：一次性交给 \s+ 分支
                        out.push(run.slice(k));
                        k = run.length;
                    }
                }
                i = j;
                continue;
            }
            // 纯空白（无换行）
            if (j === n) { out.push(run); i = j; continue; }         // \s+$
            if (j - i === 1) { out.push(run); i = j; continue; }     // \s（单个空白 + 后随非空白）
            out.push(run.slice(0, run.length - 1));                  // \s+(?!\S) → 留 1 个空白给下段
            out.push(run.slice(run.length - 1));
            i = j;
            continue;
        }
        // 兜底：单字符
        out.push(c);
        i += 1;
    }
    return out;
}

/** O200K 分割（对齐 O200K_TOKEN_SPLIT_PATTERN 语义的近似实现） */
function splitTextO200k(text) {
    const out = [];
    let i = 0;
    const n = text.length;
    const contr = /^(?:'[sS]|'[dD]|'[mM]|'[tT]|'[lL][lL]|'[vV][eE]|'[rR][eE])/;
    const isLowerLike = (c) => /[\p{Ll}\p{Lm}\p{Lo}\p{M}]/u.test(c);
    const isUpperLike = (c) => /[\p{Lu}\p{Lt}]/u.test(c);
    while (i < n) {
        const c = text[i];
        if (/[\p{L}]/u.test(c)) {
            // [^\r\n\p{L}\p{N}]? 前缀（可选单字符引导，含小写引导的大写词头）
            let start = i;
            if (i > 0 && out.length && text[i - 1] !== " " && /[^\\r\\n\p{L}\p{N}]/u.test(text[i - 1])) {
                const prev = out[out.length - 1];
                if (prev.length === 1 && /[^\\r\\n\p{L}\p{N}]/u.test(prev)) {
                    out.pop();
                    start = i - 1;
                }
            }
            let j = i;
            let sawUpper = false;
            let sawLower = false;
            while (j < n && /[\p{L}\p{M}]/u.test(text[j])) {
                if (isUpperLike(text[j])) sawUpper = true; else sawLower = true;
                j++;
            }
            // 大写词头后跟小写体，或全小写体；吞后缀缩写
            let end = j;
            const cm = contr.exec(text.slice(end));
            if (cm && (sawLower || (sawUpper && isLowerLike(text[j - 1])))) end += cm[0].length;
            out.push(text.slice(start, end));
            i = end;
            continue;
        }
        if (/\p{N}/u.test(c)) {
            let j = i;
            while (j < n && /\p{N}/u.test(text[j]) && j - i < 3) j++;
            out.push(text.slice(i, j));
            i = j;
            continue;
        }
        if (!isUvdSpace(c)) {
            let j = i;
            while (j < n && !isUvdSpace(text[j]) && !/[\p{L}\p{N}]/u.test(text[j])) j++;
            while (j < n && (text[j] === "\r" || text[j] === "\n" || text[j] === "/")) j++;
            out.push(text.slice(i, j));
            i = j;
            continue;
        }
        if (c === "\r" || c === "\n") {
            let j = i;
            while (j < n && (text[j] === "\r" || text[j] === "\n")) j++;
            out.push(text.slice(i, j));
            i = j;
            continue;
        }
        let j = i;
        while (j < n && isUvdSpace(text[j]) && text[j] !== "\r" && text[j] !== "\n") j++;
        if (j === n) { out.push(text.slice(i, j)); i = j; continue; }
        if (j - i === 1) { out.push(text.slice(i, j)); i = j; continue; }
        out.push(text.slice(i, j - 1));
        out.push(text[j - 1]);
        i = j;
    }
    return out;
}

/** 字符串 → UTF-8 字节数组 */
function utf8Bytes(str) {
    return Array.from(new TextEncoder().encode(str));
}

/** 对单个分割块做 BPE 最小秩合并（tiktoken 语义） */
function bpeChunk(bytes, rankOf) {
    if (bytes.length <= 1) return [bytes];
    let parts = bytes.map((b) => [b]);
    while (parts.length > 1) {
        let bestRank = -1;
        let bestIdx = -1;
        for (let i = 0; i < parts.length - 1; i++) {
            const key = String.fromCharCode(parts[i][0]) + "|" + String.fromCharCode(parts[i + 1][0]);
            // 两段都可能多字节：用字节序列作键
            const k2 = parts[i].concat(parts[i + 1]).join(",");
            const r = rankOf(k2, parts[i].length + parts[i + 1].length);
            if (r !== -1 && (bestRank === -1 || r < bestRank)) {
                bestRank = r;
                bestIdx = i;
            }
        }
        if (bestIdx === -1) break;
        parts = parts.slice(0, bestIdx)
            .concat([parts[bestIdx].concat(parts[bestIdx + 1])])
            .concat(parts.slice(bestIdx + 2));
    }
    return parts;
}

/** 加载 .tiktoken 词表（base64 token → rank）。key = 字节序列逗号串。 */
async function loadRanks(encoding) {
    let cache = rankCaches[encoding];
    if (cache && cache.loaded) return cache;
    if (!cache) {
        cache = { map: new Map(), loaded: false, pending: null };
        rankCaches[encoding] = cache;
    }
    if (cache.pending) return cache.pending;
    cache.pending = (async () => {
        const res = await fetch(BPE_DATA_BASE + encoding + ".tiktoken");
        if (!res.ok) throw new Error("tokenizer data " + encoding + " -> HTTP " + res.status);
        const text = await res.text();
        for (const line of text.split("\n")) {
            if (!line) continue;
            const sep = line.lastIndexOf(" ");
            if (sep <= 0) continue;
            const b64 = line.slice(0, sep).trim();
            const rank = parseInt(line.slice(sep + 1).trim(), 10);
            if (!b64 || Number.isNaN(rank)) continue;
            try {
                const bin = atob(b64);
                const key = Array.from(bin, (ch) => ch.charCodeAt(0)).join(",");
                if (!cache.map.has(key)) cache.map.set(key, rank);
            } catch (_e) { /* 跳过坏行 */ }
        }
        cache.loaded = true;
        return cache;
    })();
    try {
        return await cache.pending;
    } finally {
        cache.pending = null;
    }
}

/** 分块计数（小缓存护栏：条数与长度双上限） */
function cacheGet(key) { return countCache.get(key); }
function cachePut(key, val, textLen) {
    if (textLen > CACHE_MAX_LEN) return;
    if (countCache.size >= CACHE_MAX_TEXT) countCache.clear();
    countCache.set(key, val);
}

/**
 * 真分词计数。默认 cl100k_base（GPT-4/3.5 词表；ST OPENAI tokenizer 的本地方案）。
 * @param {string} text
 * @param {string} [encoding] cl100k_base | o200k_base
 * @returns {number} token 数（词表加载失败时回退 4字符≈1token 估算）
 */
export async function getTokenCountAsync(text, encoding) {
    const s = String(text == null ? "" : text);
    if (!s) return 0;
    const enc = encoding === "o200k_base" ? "o200k_base" : "cl100k_base";
    const key = enc + "\x00" + s;
    const hit = cacheGet(key);
    if (hit !== undefined) return hit;
    let ranks;
    try {
        ranks = await loadRanks(enc);
    } catch (_e) {
        return Math.max(1, Math.ceil(s.length / 4));
    }
    const rankOf = (bytesKey) => {
        const r = ranks.map.get(bytesKey);
        return r === undefined ? -1 : r;
    };
    const chunks = enc === "o200k_base" ? splitTextO200k(s) : splitTextCl100k(s);
    let count = 0;
    for (const chunk of chunks) {
        const bytes = utf8Bytes(chunk);
        const parts = bpeChunk(bytes, rankOf);
        count += parts.length;
    }
    cachePut(key, count, s.length);
    return count;
}

/** 同步计数：数据未就绪时回退估算（扩展首次调用即异步预热，后续准同步命中） */
let syncRanks = null;
export function primeTokenizers(encoding) {
    return loadRanks(encoding === "o200k_base" ? "o200k_base" : "cl100k_base").then((r) => {
        if (encoding !== "o200k_base" || !syncRanks) syncRanks = r;
        if (encoding === "cl100k_base") syncRanks = r;
        return r;
    });
}

export function getTokenCount(text) {
    const s = String(text == null ? "" : text);
    if (!s) return 0;
    if (syncRanks && syncRanks.loaded) {
        const rankOf = (bytesKey) => {
            const r = syncRanks.map.get(bytesKey);
            return r === undefined ? -1 : r;
        };
        const chunks = splitTextCl100k(s);
        let count = 0;
        for (const chunk of chunks) {
            const parts = bpeChunk(utf8Bytes(chunk), rankOf);
            count += parts.length;
        }
        return count;
    }
    return Math.max(0, Math.ceil(s.length / 4));
}

/** tokenize：返回 token 字符串近似（ST 返回数组；词表数据上不去字节级解码，此处按块近似） */
export function tokenize(text) {
    const s = String(text == null ? "" : text);
    return splitTextCl100k(s);
}

export default {
    getTokenCount,
    getTokenCountAsync,
    tokenize,
    primeTokenizers,
};

// ---- [ST 1.19.0 Full Export Parity] ----
export const BYTES_PER_TOKEN = 3.35;
export const CHARACTERS_PER_TOKEN_RATIO = 3.35;
export const TOKENIZER_SUPPORTED_KEY = 'tokenizationSupported';
export const TOKENIZER_WARNING_KEY = 'tokenizationWarningShown';
export const tokenizers = {
    NONE: 0,
    GPT2: 1,
    OPENAI: 2,
    LLAMA: 3,
    NERD: 4,
    NERD2: 5,
    API_CURRENT: 6,
    MISTRAL: 7,
    YI: 8,
    API_TEXTGENERATIONWEBUI: 9,
    API_KOBOLD: 10,
    CLAUDE: 11,
    LLAMA3: 12,
    GEMMA: 13,
    JAMBA: 14,
    QWEN2: 15,
    COMMAND_R: 16,
    NEMO: 17,
    DEEPSEEK: 18,
    COMMAND_A: 19,
    BEST_MATCH: 99,
};
export const ENCODE_TOKENIZERS = [
    tokenizers.LLAMA,
    tokenizers.MISTRAL,
    tokenizers.YI,
    tokenizers.LLAMA3,
    tokenizers.GEMMA,
    tokenizers.JAMBA,
    tokenizers.QWEN2,
    tokenizers.COMMAND_R,
    tokenizers.COMMAND_A,
    tokenizers.NEMO,
    tokenizers.DEEPSEEK,
];
export const TEXTGEN_TOKENIZERS = [];
export function guesstimate(text) { return Math.ceil(String(text ?? '').length / BYTES_PER_TOKEN); }
export function countTokensOpenAI(messages, full = false) {
    void full;
    if (typeof messages === 'string') return getTokenCount(messages);
    if (Array.isArray(messages)) return messages.reduce((acc, m) => acc + getTokenCount(m?.content || ''), 0);
    if (messages && typeof messages === 'object') return getTokenCount(messages.content || JSON.stringify(messages));
    return 0;
}
export async function countTokensOpenAIAsync(messages, full = false) {
    void full;
    if (typeof messages === 'string') return await getTokenCountAsync(messages);
    if (Array.isArray(messages)) {
        let total = 0;
        for (const m of messages) total += await getTokenCountAsync(m?.content || '');
        return total;
    }
    if (messages && typeof messages === 'object') return await getTokenCountAsync(messages.content || JSON.stringify(messages));
    return 0;
}
export function getTextTokens(tokenizerType, text) { void tokenizerType; return tokenize(text); }
export function decodeTextTokens(tokenizerType, tokens) { void tokenizerType; return { text: Array.isArray(tokens) ? tokens.join('') : '' }; }
export function getAvailableTokenizers() { return Object.entries(tokenizers).map(([k, v]) => ({ key: k, value: v })); }
export function getFriendlyTokenizerName(tokenizerType) { return Object.keys(tokenizers).find(k => tokenizers[k] === tokenizerType) || 'OPENAI'; }
export function getTokenizerBestMatch() { return tokenizers.OPENAI; }
export function getTokenizerModel() { return 'cl100k_base'; }
export async function initTokenizers() {}
export async function saveTokenCache() {}
export function selectTokenizer() {}
