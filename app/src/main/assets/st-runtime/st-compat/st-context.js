/**
 * rikkaST st-compat: st-context.js
 * ============================================================
 * 对齐 ST1.18 `public/scripts/st-context.js`：导出 getContext()（命名 + default）。
 * rikkaST 直接复用运行时 stCtx（其自身即对齐 150+ 键契约）。
 */

export function getContext() {
    if (window.SillyTavern && typeof window.SillyTavern.getContext === 'function') {
        return window.SillyTavern.getContext();
    }
    return {};
}

export default getContext;
