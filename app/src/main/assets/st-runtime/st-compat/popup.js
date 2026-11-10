/**
 * rikkaST st-compat: popup.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/popup.js` 的导出面。
 * POPUP_TYPE / POPUP_RESULT 优先取 runtime 内定义（第一手对齐）；callGenericPopup 转发。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export const POPUP_TYPE = st().POPUP_TYPE || {
    TEXT: 1,
    CONFIRM: 2,
    INPUT: 3,
    DISPLAY: 4,
};

export const POPUP_RESULT = st().POPUP_RESULT || {
    AFFIRMATIVE: 1,
    NEGATIVE: 0,
    CANCELLED: null,
};

export function callGenericPopup(content, type, inputValue, options) {
    try {
        const f = st().callGenericPopup;
        if (typeof f === 'function') return f(content, type, inputValue, options);
    } catch (_e) { /* noop */ }
    return Promise.resolve(null);
}

export function callPopup(text, type, ...rest) {
    return callGenericPopup(text, type, ...rest);
}

// ── [batch16] Popup 类 ────────────────────────────────────────
// 依据：记忆增强插件 8+ 处 `new EDITOR.Popup(content, EDITOR.POPUP_TYPE.X, inputValue, options)`
// 用法固定为 `await popup.show(); if (popup.result) {…}`
//   （pluginSetting.js:113 / chatSheetsDataView.js:104 / cellHistory.js:131）。
export class Popup {
    constructor(content = '', type = POPUP_TYPE.TEXT, inputValue = '', options = {}) {
        this.content = content;
        this.type = type;
        this.inputValue = inputValue;
        this.options = options || {};
        this.result = null;
        this.value = null;
        this.inputResults = null;
    }

    /** 转发给宿主 callGenericPopup；宿主缺失时返回 null（= 取消，扩展走 else 分支，不抛错）。 */
    async show() {
        let ret = null;
        try {
            const c = (window.SillyTavern && window.SillyTavern.getContext) ? window.SillyTavern.getContext() : {};
            ret = (typeof c.callGenericPopup === 'function')
                ? await c.callGenericPopup(this.content, this.type, this.inputValue, this.options)
                : await callGenericPopup(this.content, this.type, this.inputValue, this.options);
        } catch (err) {
            console.warn('[popup.js] Popup.show failed:', err);
            ret = null;
        }
        this.value = (ret === undefined) ? null : ret;
        // ST 语义：CONFIRM 时 result 为 AFFIRMATIVE(1) / CANCELLED(null)；INPUT 时 result 为输入文本。
        // 扩展只做真值判断，这里保持真值等价。
        this.result = (ret === true) ? POPUP_RESULT.AFFIRMATIVE
            : ((ret === false || ret === undefined) ? POPUP_RESULT.CANCELLED : ret);
        return this.result;
    }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export class PopupUtils { constructor(...args) { this._args = args; } }
export function fixToastrForDialogs(..._args) { return null; }
export function getTopmostModalLayer(..._args) { return null; }
