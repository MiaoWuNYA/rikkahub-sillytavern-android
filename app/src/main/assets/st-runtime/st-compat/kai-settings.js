/**
 * rikkaST st-compat: kai-settings.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/kai-settings.js` 的导出面。
 */

export const kai_settings = {
    temp: 1.0,
    rep_pen: 1.1,
    rep_pen_range: 1024,
    top_p: 0.9,
    top_a: 0.0,
    top_k: 0,
    typical: 1.0,
    tfs: 1.0,
    streaming_kobold: false,
};

export function getKoboldSettings() {
    return kai_settings;
}

export function formatKoboldUrl(url) {
    return String(url || '').trim().replace(/\/+$/, '');
}

// ---- [ST 1.19.0 Full Export Parity] ----
export async function generateKoboldWithStreaming(..._args) { return null; }
export function getKoboldGenerationData(..._args) { return null; }
export async function getStatusKobold(..._args) { return null; }
export function initKoboldSettings(..._args) { return null; }
export const kai_flags = { can_use_tokenization: false, can_use_stop_sequence: false, can_use_streaming: false, can_use_default_badwordsids: false, can_use_mirostat: false, can_use_grammar: false, can_use_min_p: false };
export const koboldai_setting_names = [];
export const koboldai_settings = {};
export function loadKoboldSettings(..._args) { return null; }
export function setKoboldFlags(..._args) { return null; }
