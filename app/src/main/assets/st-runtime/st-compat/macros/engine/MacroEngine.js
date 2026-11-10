/**
 * rikkaST st-compat: macros/engine/MacroEngine.js
 * 对齐 SillyTavern 1.19.0 MacroEngine，支持前置/后置处理器与 RikkaBridge.substituteParams / MacroRegistry 求值。
 */
import { MacroRegistry } from './MacroRegistry.js';

const preProcessors = [];
const postProcessors = [];

export const MacroEngine = {
    addPreProcessor(handler, { priority = 100, source = 'unknown' } = {}) {
        if (typeof handler !== 'function') return;
        preProcessors.push({ handler, priority: Number(priority) || 100, source });
        preProcessors.sort((a, b) => a.priority - b.priority);
    },
    removePreProcessor(handler) {
        const idx = preProcessors.findIndex((p) => p.handler === handler);
        if (idx >= 0) preProcessors.splice(idx, 1);
    },
    addPostProcessor(handler, { priority = 100, source = 'unknown' } = {}) {
        if (typeof handler !== 'function') return;
        postProcessors.push({ handler, priority: Number(priority) || 100, source });
        postProcessors.sort((a, b) => a.priority - b.priority);
    },
    removePostProcessor(handler) {
        const idx = postProcessors.findIndex((p) => p.handler === handler);
        if (idx >= 0) postProcessors.splice(idx, 1);
    },
    evaluate(text, env = {}) {
        let out = String(text ?? '');
        for (const p of preProcessors) {
            try { out = String(p.handler(out, env) ?? out); } catch (_e) { /* noop */ }
        }
        try {
            if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.substituteParams === 'function') {
                out = String(window.RikkaBridge.substituteParams(out, JSON.stringify(env || {})) ?? out);
            } else if (typeof window !== 'undefined' && window.SillyTavern && window.SillyTavern.getContext) {
                const st = window.SillyTavern.getContext();
                if (st && typeof st.substituteParams === 'function') {
                    out = st.substituteParams(out);
                }
            } else if (out.includes('{{')) {
                out = out.replace(/\{\{\s*([a-zA-Z0-9_/-]+)(?:::([^}]*))?\s*\}\}/g, (m, name, rawArgs) => {
                    const entry = MacroRegistry.getMacro(name) || MacroRegistry.getMacro(String(name).toLowerCase());
                    if (!entry || typeof entry.handler !== 'function') return m;
                    const unnamedArgs = rawArgs !== undefined ? String(rawArgs).split('::') : [];
                    return String(entry.handler({ name, unnamedArgs, list: unnamedArgs, env }) ?? '');
                });
            }
        } catch (_e) { /* noop */ }
        for (const p of postProcessors) {
            try { out = String(p.handler(out, env) ?? out); } catch (_e) { /* noop */ }
        }
        return out;
    },
};
export default MacroEngine;
