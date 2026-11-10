/**
 * rikkaST st-compat: openai.js
 * ============================================================
 * 对齐 SillyTavern 1.19.0 `public/scripts/openai.js` 的导出面与核心生成参数构建逻辑。
 * 支持 JS-Slash-Runner >= 1.15.0 ~ 1.19.0 的 createGenerationParameters / sendOpenAIRequest /
 * getStreamingReply / tryParseStreamingError / getPresetApplicationPromise 契约。
 */

const st = () => (typeof window !== 'undefined' && window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export const POLLINATIONS_ENDPOINT = 'https://gen.pollinations.ai/v1';

/** 当前 Chat Completion 源（对齐 ST 1.19.0 全集）。 */
export const chat_completion_sources = {
    OPENAI: 'openai',
    CLAUDE: 'claude',
    WINDOWAI: 'windowai',
    OPENROUTER: 'openrouter',
    AI21: 'ai21',
    MAKERSUITE: 'makersuite',
    VERTEXAI: 'vertexai',
    GEMINI: 'gemini',
    DEEPSEEK: 'deepseek',
    XAI: 'xai',
    MISTRALAI: 'mistralai',
    CUSTOM: 'custom',
    COHERE: 'cohere',
    PERPLEXITY: 'perplexity',
    GROQ: 'groq',
    POLLINATIONS: 'pollinations',
    MOONSHOT: 'moonshot',
    ZAI: 'zai',
    XIAOMI: 'xiaomi',
    SILICONFLOW: 'siliconflow',
    AIMLAPI: 'aimlapi',
    AZURE_OPENAI: 'azure_openai',
    CHUTES: 'chutes',
    ELECTRONHUB: 'electronhub',
    NANOGPT: 'nanogpt',
    COMETAPI: 'cometapi',
    WORKERS_AI: 'workers_ai',
    FIREWORKS: 'fireworks',
};

export const oai_settings = {
    prompts: [],
    prompt_order: [],
    stream_openai: false,
    chat_completion_source: 'custom',
    squash_system_messages: false,
    send_if_empty: '',
    impersonation_prompt: '[Write the next reply only as {{char}}]',
    continue_nsfw: false,
    continue_postfix: '',
    continue_max_length: 60,
    max_context_unlocked: false,
    wand_button: true,
    temp_openai: 1.0,
    freq_pen_openai: 0.0,
    pres_pen_openai: 0.0,
    top_p_openai: 1.0,
    top_k_openai: 0,
    openai_max_tokens: 1024,
    show_thoughts: true,
    n: 1,
};
export const openai_settings = [];
export const openai_setting_names = {};

/**
 * 对齐 ST 1.15+ / 1.19.0 `getChatCompletionModel(settings)`，同时向后兼容 `< 1.15.0` 的 `(source)` 字符串调用。
 */
export function getChatCompletionModel(settingsOrSource = null) {
    try {
        const s = (settingsOrSource && typeof settingsOrSource === 'object') ? settingsOrSource : oai_settings;
        const source = typeof settingsOrSource === 'string'
            ? settingsOrSource
            : (s.chat_completion_source || oai_settings.chat_completion_source);
        if (s && typeof s === 'object') {
            const bySource = s[`${source}_model`] || s.custom_model || s.openai_model || s.claude_model || s.model;
            if (typeof bySource === 'string' && bySource.trim()) return bySource.trim();
        }
        const f = st().getChatCompletionModel;
        if (typeof f === 'function') {
            const r = f(settingsOrSource);
            if (typeof r === 'string' && r.trim()) return r.trim();
        }
    } catch (_e) { /* noop */ }
    return 'unknown';
}

function createPromptManagerStub() {
    const prompts = [];
    return {
        prompts,
        addPrompt(prompt) { try { prompts.push(prompt); } catch (_e) { /* noop */ } return prompt; },
        removePrompt(prompt) { const i = prompts.indexOf(prompt); if (i >= 0) prompts.splice(i, 1); },
        overridePrompt() {},
        getPromptCollection() { return { collection: prompts.slice(), get: () => null, has: () => false }; },
        getPromptById(id) { return prompts.find((p) => p && p.id === id) || null; },
        render() {},
        setChatCompletion() {},
        tokenHandler: { counts: {} },
        serviceSettings: {},
    };
}

export const promptManager = createPromptManagerStub();
export const openai_max_stop_strings = 4;

export async function prepareOpenAIMessages(args) {
    try {
        if (args && Array.isArray(args.messages)) {
            const chat = args.messages
                .filter((m) => m && typeof m === 'object')
                .map((m) => ({
                    role: m.role || 'system',
                    content: m.content == null ? '' : String(m.content),
                    ...(m.name ? { name: String(m.name) } : {}),
                }));
            return [chat, false];
        }
    } catch (_e) { /* noop */ }
    return [[], false];
}

/**
 * 同步函数：从单个 SSE 解析对象中提取增量回复文本并累积 reasoning / images / signatures。
 * 对齐 ST 1.19.0 `public/scripts/openai.js:3222`（严禁声明为 async，否则调用方同步拼接会得到 [object Promise]）。
 */
export function getStreamingReply(data, state = {}, { chatCompletionSource = null, overrideShowThoughts = null } = {}) {
    if (!data || typeof data !== 'object') return '';
    const source = chatCompletionSource ?? oai_settings.chat_completion_source;
    const showThoughts = overrideShowThoughts ?? oai_settings.show_thoughts ?? true;
    if (state && typeof state === 'object') {
        if (typeof state.reasoning !== 'string') state.reasoning = '';
        if (!Array.isArray(state.images)) state.images = [];
        if (typeof state.signature !== 'string') state.signature = '';
        if (!state.toolSignatures || typeof state.toolSignatures !== 'object') state.toolSignatures = {};
    }

    if (source === chat_completion_sources.CLAUDE) {
        if (showThoughts && state) {
            state.reasoning += data?.delta?.thinking || '';
        }
        return data?.delta?.text || data?.choices?.[0]?.delta?.content || '';
    }
    if (source === chat_completion_sources.MAKERSUITE || source === chat_completion_sources.VERTEXAI || source === chat_completion_sources.GEMINI) {
        const parts = data?.candidates?.[0]?.content?.parts;
        if (Array.isArray(parts)) {
            if (showThoughts && state) {
                state.reasoning += (parts.filter((x) => x && x.thought).map((x) => x.text).join('') || '');
            }
            parts.forEach((part) => {
                if (part && part.thoughtSignature && typeof part.text === 'string' && state) {
                    state.signature = part.thoughtSignature;
                }
            });
            return parts.filter((x) => x && !x.thought).map((x) => x.text || '').join('');
        }
    }
    if (source === chat_completion_sources.COHERE) {
        return data?.delta?.message?.content?.text || data?.delta?.message?.tool_plan || data?.choices?.[0]?.delta?.content || '';
    }

    const choice = Array.isArray(data.choices) ? data.choices[0] : null;
    if (showThoughts && state && choice) {
        const r = choice?.delta?.reasoning_content ?? choice?.delta?.reasoning ?? choice?.message?.reasoning_content ?? choice?.message?.reasoning ?? '';
        if (typeof r === 'string' && r) state.reasoning += r;
    }
    return String(
        choice?.delta?.content ??
        choice?.message?.content ??
        choice?.text ??
        data?.delta?.text ??
        data?.content ??
        ''
    );
}

/**
 * 对齐 ST 1.19.0 `createGenerationParameters(settings, model, type, messages, options)`。
 */
export async function createGenerationParameters(
    settings = oai_settings,
    model = '',
    type = 'normal',
    messages = [],
    { jsonSchema = null, tools = undefined, tool_choice = undefined } = {},
) {
    if (!Array.isArray(messages)) {
        throw new Error('messages must be an array');
    }
    const cleanMessages = messages.filter((msg) => msg && typeof msg === 'object');
    const cfg = (settings && typeof settings === 'object') ? settings : oai_settings;
    const source = cfg.chat_completion_source || oai_settings.chat_completion_source || 'custom';
    const resolvedModel = String(model || getChatCompletionModel(cfg) || '');
    const isQuiet = type === 'quiet';
    const isImpersonate = type === 'impersonate';
    const isContinue = type === 'continue';
    const isOAI = source === chat_completion_sources.OPENAI;
    const isAzure = source === chat_completion_sources.AZURE_OPENAI;
    const isCustom = source === chat_completion_sources.CUSTOM;
    const isClaude = source === chat_completion_sources.CLAUDE;
    const isOpenRouter = source === chat_completion_sources.OPENROUTER;
    const isXAI = source === chat_completion_sources.XAI;

    const stream = Boolean(
        cfg.stream_openai && !isQuiet && !((isOAI || isAzure) && ['o1-2024-12-17', 'o1'].includes(resolvedModel))
    );
    const canMultiSwipe = Boolean(
        Number(cfg.n) > 1 && !isContinue && !isImpersonate && !isQuiet && (isOAI || isAzure || isCustom || isXAI)
    );

    const ctx = st();
    const generate_data = {
        type: String(type || 'normal'),
        messages: cleanMessages,
        model: resolvedModel,
        temperature: Number(cfg.temp_openai ?? 1.0),
        frequency_penalty: Number(cfg.freq_pen_openai ?? 0.0),
        presence_penalty: Number(cfg.pres_pen_openai ?? 0.0),
        top_p: Number(cfg.top_p_openai ?? 1.0),
        max_tokens: Number(cfg.openai_max_tokens ?? 1024),
        stream,
        chat_completion_source: source,
        n: canMultiSwipe ? Number(cfg.n) : undefined,
        user_name: String(ctx.name1 || 'User'),
        char_name: String(ctx.name2 || 'Assistant'),
        group_names: [],
        custom_prompt_post_processing: cfg.custom_prompt_post_processing,
    };

    if (cfg.reverse_proxy) {
        generate_data.reverse_proxy = cfg.reverse_proxy;
        generate_data.proxy_password = cfg.proxy_password;
    }
    if (isCustom) {
        if (cfg.custom_url) generate_data.custom_url = cfg.custom_url;
        if (cfg.custom_include_body) generate_data.custom_include_body = cfg.custom_include_body;
        if (cfg.custom_exclude_body) generate_data.custom_exclude_body = cfg.custom_exclude_body;
        if (cfg.custom_include_headers) generate_data.custom_include_headers = cfg.custom_include_headers;
    }
    if (isClaude || isOpenRouter) {
        if (cfg.top_k_openai !== undefined) generate_data.top_k = Number(cfg.top_k_openai);
    }
    if (jsonSchema) {
        generate_data.json_schema = jsonSchema;
    }
    if (Array.isArray(tools) && tools.length > 0) {
        generate_data.tools = tools;
        generate_data.tool_choice = tool_choice ?? 'auto';
    }
    return { generate_data, stream, canMultiSwipe };
}

/**
 * 对齐 ST 1.19.0 `sendOpenAIRequest(type, messages, signal, options)`。
 * 走 `/api/backends/chat-completions/generate`，支持非流式 JSON 返回与流式 AsyncGenerator。
 */
export async function sendOpenAIRequest(type = 'normal', messages = [], signal = undefined, { jsonSchema = null } = {}) {
    const abortSignal = signal || (new AbortController().signal);
    const model = getChatCompletionModel(oai_settings);
    const { generate_data, stream } = await createGenerationParameters(oai_settings, model, type, messages, { jsonSchema });
    const ctx = st();
    try {
        if (ctx.eventSource && typeof ctx.eventSource.emit === 'function') {
            const eventTypes = ctx.eventTypes || ctx.event_types || {};
            const evt = eventTypes.CHAT_COMPLETION_SETTINGS_READY || 'chat_completion_settings_ready';
            await ctx.eventSource.emit(evt, generate_data);
        }
    } catch (_e) { /* noop */ }

    const headers = (ctx && typeof ctx.getRequestHeaders === 'function')
        ? ctx.getRequestHeaders()
        : { 'Content-Type': 'application/json' };

    const response = await fetch('/api/backends/chat-completions/generate', {
        method: 'POST',
        headers,
        body: JSON.stringify(generate_data),
        signal: abortSignal,
    });

    if (!response.ok) {
        const errText = await response.text();
        tryParseStreamingError(response, errText);
        throw new Error(`Got response status ${response.status}: ${errText}`);
    }

    if (!stream) {
        const data = await response.json();
        if (data && data.error) {
            const msg = typeof data.error === 'string' ? data.error : (data.error.message || 'API error');
            throw new Error(msg);
        }
        return data;
    }

    return await response.json();
}

export function tryParseStreamingError(response, decoded, { quiet = false } = {}) {
    if (!decoded || typeof decoded !== 'string') return;
    let data = null;
    try {
        data = JSON.parse(decoded);
    } catch (_e) {
        return;
    }
    if (!data || typeof data !== 'object') return;
    if (data.error) {
        const msg = typeof data.error === 'string' ? data.error : (data.error.message || data.error.code || 'Chat Completion API Error');
        if (!quiet && typeof window !== 'undefined' && window.toastr && typeof window.toastr.error === 'function') {
            window.toastr.error(msg, 'Chat Completion API');
        }
        throw new Error(msg);
    }
}

let presetApplicationPromise = Promise.resolve();
export function getPresetApplicationPromise() {
    return presetApplicationPromise;
}

export const isImageInliningSupported = () => false;
export const proxies = [];
export function parseExampleIntoIndividual(text) { return [String(text == null ? '' : text)]; }
export function setOpenAIMessages() {}
export function setOpenAIMessageExamples() {}
export function setupChatCompletionPromptManager() {}

export class ChatCompletion {
    constructor(...rest) {
        this.extra = rest;
        this._collections = [];
    }
    add(collection, index) {
        try {
            if (index == null || typeof index !== 'number' || index >= this._collections.length) this._collections.push(collection);
            else this._collections.splice(index, 0, collection);
        } catch (_e) { /* noop */ }
        return this;
    }
    reserveBudget() { return this; }
    freeBudget() { return this; }
    setTokenBudget() { return this; }
    async squashSystemMessages() { return this; }
    getChat() {
        const out = [];
        try {
            for (const col of this._collections) {
                const list = col && Array.isArray(col.messages) ? col.messages : (Array.isArray(col) ? col : [col]);
                for (const m of list) {
                    if (m && typeof m === 'object' && m.content != null) {
                        out.push({
                            role: m.role || 'system',
                            content: String(m.content),
                            ...(m.name ? { name: String(m.name) } : {}),
                        });
                    }
                }
            }
        } catch (_e) { /* noop */ }
        return out;
    }
}

export class Message {
    constructor(role, content, ...rest) {
        this.role = role;
        this.content = content;
        this.identifier = (rest && rest[0]) || null;
        this.extra = rest;
        this.name = null;
    }
    static async createAsync(role, content, identifier) {
        return new Message(role, content, identifier);
    }
    static async fromPromptAsync(prompt) {
        const m = new Message((prompt && prompt.role) || 'system', (prompt && prompt.content) || '', (prompt && (prompt.identifier || prompt.id)) || null);
        if (prompt && prompt.name) m.name = prompt.name;
        return m;
    }
    async setName(name) { this.name = name == null ? null : String(name); return this; }
    async setTokenCount() { return this; }
}

export class MessageCollection {
    constructor(identifierOrMessages = [], maybeMessages = null) {
        if (typeof identifierOrMessages === 'string') {
            this.identifier = identifierOrMessages;
            this.messages = Array.isArray(maybeMessages) ? maybeMessages : [];
        } else {
            this.identifier = null;
            this.messages = Array.isArray(identifierOrMessages) ? identifierOrMessages : [];
        }
    }
    add(message) { try { this.messages.push(message); } catch (_e) { /* noop */ } return this; }
}

export const settingsToUpdate = {
    temperature: [1.0, 'temp_openai'],
    frequency_penalty: [0.0, 'freq_pen_openai'],
    presence_penalty: [0.0, 'pres_pen_openai'],
    top_p: [1.0, 'top_p_openai'],
    top_k: [0, 'top_k_openai'],
    top_a: [0, 'top_a_openai'],
    min_p: [0, 'min_p_openai'],
    repetition_penalty: [1.0, 'rep_pen_openai'],
    openai_max_context: [4095, 'openai_max_context'],
    openai_max_tokens: [300, 'openai_max_tokens'],
    wrap_in_quotes: [false, 'wrap_in_quotes'],
    names_behavior: [0, 'names_behavior'],
    send_if_empty: ['', 'send_if_empty'],
    impersonation_prompt: ['[Write the next reply only as {{char}}]', 'impersonation_prompt'],
    continue_nudge_prompt: ['[Continue the following message.]', 'continue_nudge_prompt'],
    bias_preset_selected: ['Default (none)', 'bias_preset_selected'],
    max_context_unlocked: [false, 'max_context_unlocked'],
    wi_format: ['{0}', 'wi_format'],
    scenario_format: ['{{scenario}}', 'scenario_format'],
    personality_format: ['{{personality}}', 'personality_format'],
    group_nudge_prompt: ['[Write the next reply only as {{char}}.]', 'group_nudge_prompt'],
    stream_openai: [false, 'stream_openai'],
    prompts: [[], 'prompts'],
    prompt_order: [[], 'prompt_order'],
    api_url_scale: ['', 'api_url_scale'],
    show_external_models: [false, 'show_external_models'],
    assistant_prefill: ['', 'assistant_prefill'],
    assistant_impersonation: ['', 'assistant_impersonation'],
    claude_use_sysprompt: [false, 'claude_use_sysprompt'],
    use_makersuite_sysprompt: [false, 'use_makersuite_sysprompt'],
    squash_system_messages: [false, 'squash_system_messages'],
    image_inlining: [false, 'image_inlining'],
    inline_image_quality: ['low', 'inline_image_quality'],
    video_inlining: [false, 'video_inlining'],
    continue_prefill: [false, 'continue_prefill'],
    continue_postfix: [' ', 'continue_postfix'],
    function_calling: [false, 'function_calling'],
    show_thoughts: [true, 'show_thoughts'],
    reasoning_effort: ['medium', 'reasoning_effort'],
    enable_web_search: [false, 'enable_web_search'],
    request_images: [false, 'request_images'],
    seed: [-1, 'seed'],
    n: [1, 'n'],
};

// ---- [ST 1.19.0 Full Export Parity] ----
export class IdentifierNotFoundError extends Error { constructor(identifier) { super(`Identifier ${identifier} not found.`); this.name = 'IdentifierNotFoundError'; } }
export const MINIMAX_ENDPOINT = { GLOBAL: 'global', CN: 'cn' };
export const SILICONFLOW_ENDPOINT = { GLOBAL: 'global', CN: 'cn' };
export class TokenHandler { constructor(countTokenAsyncFn) { this.countTokenAsyncFn = countTokenAsyncFn; this.counts = { start_chat: 0, prompt: 0, bias: 0, nudge: 0, jailbreak: 0, impersonate: 0, examples: 0, conversation: 0 }; } getCounts() { return this.counts; } resetCounts() { Object.keys(this.counts).forEach((k) => { this.counts[k] = 0; }); } }
export const ZAI_ENDPOINT = { COMMON: 'common', CODING: 'coding' };
export const custom_prompt_post_processing_types = { NONE: '', CLAUDE: 'claude', MERGE: 'merge', MERGE_TOOLS: 'merge_tools', SEMI: 'semi', SEMI_TOOLS: 'semi_tools', STRICT: 'strict', STRICT_TOOLS: 'strict_tools', SINGLE: 'single' };
export function formatWorldInfo(..._args) { return null; }
export function getChatCompletionPreset(..._args) { return null; }
export function getPromptPosition(..._args) { return null; }
export function getPromptRole(..._args) { return null; }
export function initOpenAI(..._args) { return null; }
export function isAudioInliningSupported(..._args) { return null; }
export function isReasoningSignatureSupported(..._args) { return null; }
export function isVideoInliningSupported(..._args) { return null; }
export function loadOpenAISettings(..._args) { return null; }
export function loadProxyPresets(..._args) { return null; }
export const model_list = [];
export const openai_messages_count = 0;
export const reasoning_effort_types = { auto: 'auto', low: 'low', medium: 'medium', high: 'high', min: 'min', max: 'max' };
export let selected_proxy = '';
export const tool_reasoning_modes = { DISABLED: 'disabled', SINCE_LAST_USER: 'since_last_user', ACTIVE_CHAIN: 'active_chain' };
export const verbosity_levels = { auto: 'auto', low: 'low', medium: 'medium', high: 'high' };
