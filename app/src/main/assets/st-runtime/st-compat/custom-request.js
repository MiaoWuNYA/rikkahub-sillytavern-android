/**
 * rikkaST st-compat: custom-request.js
 * ============================================================
 * 对齐 SillyTavern `public/scripts/custom-request.js`，通过宿主 Chat Completions 桥接发送请求。
 */

const st = () => (typeof window !== 'undefined' && window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export class ChatCompletionService {
    static async presetToGeneratePayload(preset = {}, customParams = {}, overrides = {}) {
        const merged = Object.assign({}, preset, customParams, overrides);
        return {
            messages: Array.isArray(merged.messages) ? merged.messages : [],
            model: merged.model || 'unknown',
            temperature: Number(merged.temperature ?? merged.temp_openai ?? 1.0),
            frequency_penalty: Number(merged.frequency_penalty ?? merged.freq_pen_openai ?? 0.0),
            presence_penalty: Number(merged.presence_penalty ?? merged.pres_pen_openai ?? 0.0),
            top_p: Number(merged.top_p ?? merged.top_p_openai ?? 1.0),
            max_tokens: Number(merged.max_tokens ?? merged.openai_max_tokens ?? 1024),
            stream: Boolean(merged.stream ?? merged.stream_openai ?? false),
            chat_completion_source: merged.chat_completion_source || 'openai',
            ...customParams,
            ...overrides,
        };
    }

    static async sendRequest(payload = {}) {
        return ChatCompletionService.processRequest(payload);
    }

    static async processRequest(payload = {}) {
        try {
            if (typeof window !== 'undefined' && typeof window.fetch === 'function') {
                try {
                    const resp = await window.fetch('/api/backends/chat-completions/generate', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload || {}),
                    });
                    if (resp && resp.ok) {
                        return await resp.json();
                    }
                } catch (_fetchErr) {
                    // Fallback to ctx.generateRaw below
                }
            }
            const ctx = st();
            if (ctx && typeof ctx.generateRaw === 'function') {
                const messages = Array.isArray(payload.messages) ? payload.messages : [];
                const prompt = messages.map((m) => (m && m.content) || '').join('\n');
                const text = await ctx.generateRaw({ prompt });
                return { choices: [{ message: { role: 'assistant', content: String(text || '') } }] };
            }
        } catch (_e) { /* noop */ }
        return { choices: [{ message: { role: 'assistant', content: '' } }] };
    }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export class TextCompletionService { constructor(...args) { this._args = args; } }
