/**
 * rikkaST st-compat: i18n.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/i18n.js` 的最小导出面（locale + t）。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export function getCurrentLocale() {
    try {
        const f = st().getCurrentLocale;
        if (typeof f === 'function') return f();
    } catch (_e) { /* noop */ }
    return 'zh-cn';
}

/** 宽容翻译函数：支持 t`模板标签` 与 t('文本', ...args) 两种调用。 */
export function t(...args) {
    try {
        const first = args[0];
        if (Array.isArray(first) && first.raw) {
            let out = '';
            first.forEach((seg, i) => {
                out += seg;
                if (i < args.length - 1) out += String(args[i + 1] == null ? '' : args[i + 1]);
            });
            return out;
        }
        if (typeof first === 'string') {
            let out = first;
            for (let i = 1; i < args.length; i++) {
                out = out.replace('{' + (i - 1) + '}', String(args[i] == null ? '' : args[i]));
            }
            return out;
        }
    } catch (_e) { /* noop */ }
    return '';
}

export const translate = t;

// ---- [ST 1.19.0 Full Export Parity] ----
export function addLocaleData(..._args) { return null; }
export function applyLocale(..._args) { return null; }
export async function initLocales(..._args) { return null; }
