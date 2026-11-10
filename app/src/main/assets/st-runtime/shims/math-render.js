/**
 * Tavern Runtime — 数学公式渲染（KaTeX 离线，R2）
 * ============================================================
 * 目标：把消息文本里的 `$$...$$`（块级）与 `\(...\)`（行内，另兼容 `\[...\]`）渲染为
 *       KaTeX HTML，供 stMessageFormatting / 面板内渲染复用。
 * 离线：katex 由 vendor/katex/katex.min.js 提供（MIT，本地 vendored，无外网依赖）。
 * 保护语义：与宿主 mathBlock 一致 —— 围栏代码块 / 行内代码里的分隔符一律不解析。
 */

let _katex = null;

/** 注入 KaTeX 实例（返回是否可用）。 */
export function setKatex(k) {
    _katex = (k && typeof k.renderToString === 'function') ? k : null;
    return !!_katex;
}

export function isKatexAvailable() { return !!_katex; }
export function getKatex() { return _katex; }

const FENCED_CODE = /```[\s\S]*?```/g;
const INLINE_CODE = /`[^`\n]*`/g;
const TOKEN_PREFIX = 'RIKKAMATHTOKEN';

function renderOne(tex, display, options) {
    const opts = Object.assign({
        displayMode: !!display,
        throwOnError: false,
        output: 'html',
    }, options || {});
    try {
        return _katex.renderToString(String(tex), opts);
    } catch (_e) {
        return display ? ('$$' + tex + '$$') : ('\\(' + tex + '\\)');
    }
}

/**
 * 把数学片段替换为占位 token（Markdown 之前调用）。
 * @returns {{ text: string, restore: (html: string) => string }}
 */
export function stashMath(text, options) {
    const src = String(text == null ? '' : text);
    const stash = [];
    const identity = (h) => String(h == null ? '' : h);
    if (!_katex) return { text: src, restore: identity };

    const hold = (value) => {
        stash.push(value);
        return TOKEN_PREFIX + (stash.length - 1) + 'X';
    };

    let work = src.replace(FENCED_CODE, hold).replace(INLINE_CODE, hold);

    work = work.replace(/\$\$([\s\S]+?)\$\$/g, (m, tex) => hold(renderOne(tex, true, options)));
    work = work.replace(/\\\[([\s\S]+?)\\\]/g, (m, tex) => hold(renderOne(tex, true, options)));
    work = work.replace(/\\\(([\s\S]+?)\\\)/g, (m, tex) => hold(renderOne(tex, false, options)));

    const restore = (html) => {
        let out = String(html == null ? '' : html);
        for (let i = 0; i < stash.length; i++) {
            out = out.split(TOKEN_PREFIX + i + 'X').join(stash[i]);
        }
        return out;
    };
    return { text: work, restore: restore };
}

/** 直接渲染文本里的数学（不做 Markdown 转换）。 */
export function renderMathInText(text, options) {
    const s = stashMath(text, options);
    return s.restore(s.text);
}

/** 文本里是否含受支持的数学分隔符（快速探测）。 */
export function containsMath(text) {
    const s = String(text == null ? '' : text);
    return s.indexOf('$$') >= 0 || s.indexOf('\\(') >= 0 || s.indexOf('\\[') >= 0;
}
