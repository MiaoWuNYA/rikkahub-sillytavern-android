/**
 * rikkaST st-compat: macros/engine/MacroEnvBuilder.js
 */
export const env_provider_order = {
    EARLIEST: 0,
    EARLY: 10,
    NORMAL: 50,
    LATE: 90,
    LATEST: 100,
};

const providers = [];

export const MacroEnvBuilder = {
    providerOrder: env_provider_order,
    registerProvider(provider, order = env_provider_order.NORMAL) {
        if (typeof provider !== 'function') return;
        providers.push({ fn: provider, order: Number(order) || 50 });
        providers.sort((a, b) => a.order - b.order);
    },
    unregisterProvider(provider) {
        const idx = providers.findIndex((p) => p.fn === provider);
        if (idx >= 0) providers.splice(idx, 1);
    },
    build(rawContext = {}) {
        const st = (typeof window !== 'undefined' && window.SillyTavern && window.SillyTavern.getContext)
            ? window.SillyTavern.getContext()
            : {};
        const env = {
            content: String(rawContext.content ?? ''),
            names: {
                user: rawContext.name1Override ?? st.name1 ?? 'User',
                char: rawContext.name2Override ?? st.name2 ?? 'Assistant',
                group: rawContext.groupOverride ?? '',
            },
            character: {},
            system: {},
            state: {},
            chat: {},
            functions: {
                postProcess: typeof rawContext.postProcessFn === 'function' ? rawContext.postProcessFn : (x) => x,
            },
            dynamicMacros: rawContext.dynamicMacros || {},
            extra: {},
        };
        for (const p of providers) {
            try { p.fn(env, rawContext); } catch (_e) { /* noop */ }
        }
        return env;
    },
};
export default MacroEnvBuilder;
