// rikkaST st-compat: scripts/slash-commands/SlashCommandClosure.js
export class SlashCommandClosure {
    constructor(scope) {
        this.scope = scope || null;
        this.argumentList = [];
        this.executor = [];
        this.rawText = '';
        this.providedArgumentList = [];
    }

    substituteWithMacroEngine(text = '') {
        const s = String(text ?? '');
        try {
            if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.substituteParams === 'function') {
                return String(window.RikkaBridge.substituteParams(s, '{}') ?? s);
            }
        } catch (_e) { /* noop */ }
        return s;
    }

    substituteNamedArguments(namedArgs = {}) {
        const out = {};
        if (!namedArgs || typeof namedArgs !== 'object') return out;
        for (const [k, v] of Object.entries(namedArgs)) {
            out[k] = typeof v === 'string' ? this.substituteWithMacroEngine(v) : v;
        }
        return out;
    }

    substituteUnnamedArgument(unnamedArg = '') {
        if (typeof unnamedArg === 'string') {
            return this.substituteWithMacroEngine(unnamedArg);
        }
        return unnamedArg;
    }

    toString() { return String(this.rawText || ''); }

    async execute() {
        const raw = String(this.rawText || '').trim();
        if (raw && typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.triggerSlash === 'function') {
            try {
                const pipe = String(window.RikkaBridge.triggerSlash(raw) ?? '');
                return { pipe, isError: false, isAborted: false };
            } catch (_e) { /* fallthrough */ }
        }
        return { pipe: '' };
    }
}
export default SlashCommandClosure;
