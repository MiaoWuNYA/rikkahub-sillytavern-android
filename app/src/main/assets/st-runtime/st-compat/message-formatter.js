/**
 * SillyTavern 1.19.0 MessageFormatter compatibility module
 * Aligned with SillyTavern 1.19.0 public/scripts/message-formatter.js
 */

export const formatting_stage = Object.freeze({
    BEFORE_REGEX: 'beforeRegex',
    AFTER_REGEX: 'afterRegex',
    AFTER_MARKDOWN: 'afterMarkdown',
});

export const hook_order = Object.freeze({
    EARLIEST: 0,
    EARLY: 10,
    NORMAL: 50,
    LATE: 90,
    LATEST: 100,
});

const STAGE_ALIASES = {
    beforeRegex: 'beforeRegex',
    before_regex: 'beforeRegex',
    afterRegex: 'afterRegex',
    after_regex: 'afterRegex',
    afterMarkdown: 'afterMarkdown',
    after_markdown: 'afterMarkdown',
};

function normalizeStage(stage) {
    return STAGE_ALIASES[String(stage || '')] || null;
}

class MessageFormatterImpl {
    static #instance;

    static get instance() {
        return MessageFormatterImpl.#instance ?? (MessageFormatterImpl.#instance = new MessageFormatterImpl());
    }

    #hooks = new Map();

    stage = formatting_stage;
    order = hook_order;
    BEFORE_REGEX = formatting_stage.BEFORE_REGEX;
    AFTER_REGEX = formatting_stage.AFTER_REGEX;
    AFTER_MARKDOWN = formatting_stage.AFTER_MARKDOWN;
    hook_order = hook_order;

    constructor() {
        this.#hooks.set(formatting_stage.BEFORE_REGEX, []);
        this.#hooks.set(formatting_stage.AFTER_REGEX, []);
        this.#hooks.set(formatting_stage.AFTER_MARKDOWN, []);
    }

    #notifyBridge() {
        try {
            if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.onMessageFormatterHooksChanged === 'function') {
                window.RikkaBridge.onMessageFormatterHooksChanged(Boolean(this.hasHooks()));
            }
        } catch (_e) { /* noop */ }
    }

    /**
     * Registers a hook function to run at a specific pipeline stage.
     * Supports both official ST 1.19.0 signature `addHook(fn, { stage, order, owner })`
     * and legacy `(stage, fn, options)`.
     */
    addHook(fnOrStage, optionsOrFn = {}, maybeOptions = {}) {
        let fn;
        let opts;
        if (typeof fnOrStage === 'string' && typeof optionsOrFn === 'function') {
            fn = optionsOrFn;
            opts = Object.assign({}, maybeOptions, { stage: fnOrStage });
        } else {
            fn = fnOrStage;
            opts = optionsOrFn || {};
        }

        const rawStage = opts.stage !== undefined ? opts.stage : formatting_stage.AFTER_MARKDOWN;
        const stage = normalizeStage(rawStage);
        if (typeof fn !== 'function') {
            throw new TypeError('MessageFormatter: hook must be a function');
        }
        if (fn.constructor && fn.constructor.name === 'AsyncFunction') {
            throw new TypeError(`MessageFormatter: hook registered for stage '${rawStage}' must be synchronous — async functions are not supported`);
        }
        if (!stage || !this.#hooks.has(stage)) {
            throw new RangeError(`MessageFormatter: unknown stage '${rawStage}'`);
        }
        const order = Number.isFinite(opts.order)
            ? Number(opts.order)
            : (Number.isFinite(opts.priority) ? Number(opts.priority) : hook_order.NORMAL);
        const owner = opts.owner ? String(opts.owner) : null;
        this.#hooks.get(stage).push({ fn, order, owner });
        this.#notifyBridge();
    }

    removeHook(stageOrFn, maybeFn) {
        if (typeof stageOrFn === 'function') {
            let removed = false;
            for (const bucket of this.#hooks.values()) {
                const idx = bucket.findIndex((h) => h.fn === stageOrFn);
                if (idx !== -1) {
                    bucket.splice(idx, 1);
                    removed = true;
                }
            }
            if (removed) this.#notifyBridge();
            return removed;
        }
        const stage = normalizeStage(stageOrFn);
        if (!stage || !this.#hooks.has(stage)) return false;
        const bucket = this.#hooks.get(stage);
        const idx = bucket.findIndex((h) => h.fn === maybeFn);
        if (idx === -1) return false;
        bucket.splice(idx, 1);
        this.#notifyBridge();
        return true;
    }

    clearHooksByOwner(owner) {
        if (!owner) return 0;
        let removed = 0;
        for (const bucket of this.#hooks.values()) {
            for (let i = bucket.length - 1; i >= 0; i--) {
                if (bucket[i].owner === owner) {
                    bucket.splice(i, 1);
                    removed++;
                }
            }
        }
        if (removed > 0) this.#notifyBridge();
        return removed;
    }

    clearAllHooks() {
        for (const bucket of this.#hooks.values()) {
            bucket.length = 0;
        }
        this.#notifyBridge();
    }

    hasHooks(stage) {
        if (stage !== undefined) {
            const norm = normalizeStage(stage);
            if (!norm || !this.#hooks.has(norm)) return false;
            return this.#hooks.get(norm).length > 0;
        }
        for (const bucket of this.#hooks.values()) {
            if (bucket.length > 0) return true;
        }
        return false;
    }

    runStage(rawStage, mes, base = {}) {
        const stage = normalizeStage(rawStage);
        if (!stage) return String(mes ?? '');
        const bucket = this.#hooks.get(stage);
        let current = String(mes ?? '');
        if (!bucket || bucket.length === 0) return current;
        const baseObj = (base && typeof base === 'object') ? base : {};
        const characterName = baseObj.characterName ?? baseObj.ch_name ?? '';
        const ch_name = baseObj.ch_name ?? baseObj.characterName ?? '';
        const ctx = Object.freeze({
            ...baseObj,
            characterName,
            ch_name,
            stage,
        });
        const sorted = bucket.slice().sort((a, b) => a.order - b.order);
        for (const { fn } of sorted) {
            try {
                const result = fn(current, ctx);
                if (typeof result !== 'string') {
                    const isProm = result && (result instanceof Promise || typeof result.then === 'function');
                    console.warn(`[MessageFormatter] Hook at stage '${stage}' returned ${isProm ? 'a Promise (hook may be async)' : typeof result} instead of a string. The hook's return value has been ignored.`);
                } else {
                    current = result;
                }
            } catch (e) {
                console.error(`[MessageFormatter] Hook error at stage '${stage}':`, e);
            }
        }
        return current;
    }

    format(mes, characterName, isSystem, isUser, messageId, sanitizerOverrides = {}, isReasoning = false) {
        if (typeof window !== 'undefined' && window.SillyTavern && typeof window.SillyTavern.getContext === 'function') {
            const ctx = window.SillyTavern.getContext();
            if (ctx && typeof ctx.messageFormatting === 'function') {
                return ctx.messageFormatting(mes, characterName, isSystem, isUser, messageId, sanitizerOverrides, isReasoning);
            }
        }
        let text = String(mes ?? '');
        const base = {
            characterName: characterName ?? '',
            ch_name: characterName ?? '',
            isSystem: Boolean(isSystem),
            isUser: Boolean(isUser),
            messageId: typeof messageId === 'number' ? messageId : -1,
            isReasoning: Boolean(isReasoning),
        };
        text = this.runStage(formatting_stage.BEFORE_REGEX, text, base);
        text = this.runStage(formatting_stage.AFTER_REGEX, text, base);
        text = this.runStage(formatting_stage.AFTER_MARKDOWN, text, base);
        return text;
    }

    sanitize(text) {
        return String(text ?? '');
    }

    generate(text) {
        return String(text ?? '');
    }
}

export const MessageFormatter = MessageFormatterImpl.instance;

if (typeof window !== 'undefined') {
    window.__rikkaMessageFormatter = MessageFormatter;
}

export default MessageFormatter;
