/**
 * rikkaST st-compat: macros/engine/MacroCstWalker.js
 */
import { MacroEngine } from './MacroEngine.js';

export const MacroCstWalker = {
    walk(_cst, env = {}) {
        const raw = String((env && env.content) ?? '');
        return MacroEngine.evaluate(raw, env);
    },
};
export default MacroCstWalker;
