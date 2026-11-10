/**
 * rikkaST st-compat: reasoning.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/reasoning.js` 的导出面。
 *
 * 对齐 ST1.18 导出：reasoning_templates / DEFAULT_REASONING_TEMPLATE /
 * ReasoningType / ReasoningState / ReasoningHandler / PromptReasoning /
 * extractReasoningFromData / extractReasoningSignatureFromData /
 * isHiddenReasoningModel / updateReasoningUI / removeReasoningFromString /
 * getReasoningTemplateByName / parseReasoningFromString / formatReasoning /
 * parseReasoningInSwipes / loadReasoningTemplates / initReasoning
 *
 * 行为策略：
 * - 模板数据：与 ST 默认预设（default/content/presets/reasoning/*.json）一致；
 * - parse/format/remove：纯函数本地实现，语义对齐 ST1.18；
 * - updateReasoningUI：转发宿主 getContext()，缺失时 no-op；
 * - ReasoningHandler / PromptReasoning：最小占位（扩展极少直接实例化）。
 */

import { power_user } from './power-user.js';

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export const DEFAULT_REASONING_TEMPLATE = 'Think XML';

/**
 * @type {Array<{name:string,prefix:string,suffix:string,separator:string}>}
 * 与 ST default/content/presets/reasoning/ 的五个预设一致。
 */
export let reasoning_templates = [
    { name: 'Blank', prefix: '', suffix: '', separator: '' },
    { name: 'DeepSeek', prefix: '<think>\n', suffix: '\n</think>', separator: '\n\n' },
    { name: 'Gemma 4', prefix: '<|channel>thought\n', suffix: '<channel|>', separator: '\n\n' },
    { name: 'OpenAI Harmony', prefix: '<|start|>assistant<|channel|>analysis<|message|>', suffix: '<|start|>assistant<|channel|>final<|message|>', separator: '' },
    { name: 'Think XML', prefix: '<think>', suffix: '</think>', separator: '\n' },
];

/** 枚举：推理块来源（对齐 ST1.18）。 */
export const ReasoningType = {
    Model: 'model',
    Parsed: 'parsed',
    Manual: 'manual',
    Edited: 'edited',
};

/** 枚举：推理状态（对齐 ST1.18）。 */
export const ReasoningState = {
    None: 'none',
    Thinking: 'thinking',
    Done: 'done',
    Hidden: 'hidden',
};

function escapeRegex(str) {
    return String(str).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function trimSpaces(str) {
    return String(str == null ? '' : str).replace(/^\s+|\s+$/g, '');
}

function substituteParamsSafe(text) {
    try {
        const c = st();
        if (typeof c.substituteParams === 'function') return c.substituteParams(text);
    } catch (_e) { /* noop */ }
    return text;
}

/** 按名取模板。宽松实现：未找到时回退默认模板（避免扩展因名称缺失崩溃）。 */
export function getReasoningTemplateByName(name) {
    const found = reasoning_templates.find((p) => p && p.name === name);
    if (found) return found;
    const fallback = reasoning_templates.find((p) => p && p.name === DEFAULT_REASONING_TEMPLATE);
    if (fallback) return fallback;
    return { name: String(name || DEFAULT_REASONING_TEMPLATE), prefix: '', suffix: '', separator: '' };
}

/** 从字符串中解析推理块（语义对齐 ST1.18；strict=开头必须命中）。 */
export function parseReasoningFromString(str, { strict = true } = {}, template = null) {
    template = template || power_user.reasoning || null;
    if (!template || !template.prefix || !template.suffix) {
        return null;
    }
    try {
        const regex = new RegExp(`${strict ? '^\\s*?' : ''}${escapeRegex(template.prefix)}(.*?)${escapeRegex(template.suffix)}`, 's');
        let didReplace = false;
        let reasoning = '';
        const content = String(str).replace(regex, (_match, capture) => {
            didReplace = true;
            reasoning = capture;
            return '';
        });
        if (didReplace) {
            reasoning = trimSpaces(reasoning);
        }
        return { reasoning, content: didReplace ? trimSpaces(content) : String(str) };
    } catch (error) {
        console.error('[Reasoning] Error parsing reasoning block', error);
        return null;
    }
}

/** 组装 推理块 + 正文（parseReasoningFromString 的逆操作）。 */
export function formatReasoning(reasoning, content, template = null) {
    template = template || power_user.reasoning || null;
    if (!reasoning || !template || !template.prefix || !template.suffix) {
        return { formatted: content, contentOnly: content };
    }
    const prefix = substituteParamsSafe(template.prefix || '');
    const suffix = substituteParamsSafe(template.suffix || '');
    const separator = substituteParamsSafe(template.separator || '');
    const formatted = `${prefix}${reasoning}${suffix}${separator}${content}`;
    return { formatted, contentOnly: content };
}

/** 移除消息中的推理块（受 power_user.reasoning.auto_parse 门控，对齐 ST）。 */
export function removeReasoningFromString(str) {
    try {
        if (!power_user.reasoning || !power_user.reasoning.auto_parse) {
            return str;
        }
    } catch (_e) {
        return str;
    }
    const parsedReasoning = parseReasoningFromString(str);
    return parsedReasoning ? parsedReasoning.content : str;
}

/** 解析 swipes 数组中的推理块并写入 swipeInfoArray（受 auto_parse 门控）。 */
export function parseReasoningInSwipes(swipes, swipeInfoArray, duration) {
    try {
        if (!power_user.reasoning || !power_user.reasoning.auto_parse) return;
        if (!Array.isArray(swipes) || !Array.isArray(swipeInfoArray) || swipes.length !== swipeInfoArray.length) return;
        for (let index = 0; index < swipes.length; index++) {
            const parsedReasoning = parseReasoningFromString(swipes[index]);
            if (parsedReasoning) {
                swipes[index] = parsedReasoning.content;
                if (swipeInfoArray[index]) {
                    if (!swipeInfoArray[index].extra) swipeInfoArray[index].extra = {};
                    swipeInfoArray[index].extra.reasoning = parsedReasoning.reasoning;
                    swipeInfoArray[index].extra.reasoning_duration = duration;
                    swipeInfoArray[index].extra.reasoning_type = ReasoningType.Parsed;
                }
            }
        }
    } catch (_e) { /* noop */ }
}

/** 从响应数据提取推理文本（兼容 DeepSeek/OpenRouter/Gemini 等常见形态）。 */
export function extractReasoningFromData(data, { mainApi = null, ignoreShowThoughts = false, textGenType = null, chatCompletionSource = null } = {}) {
    try {
        return (
            data?.choices?.[0]?.message?.reasoning_content
            ?? data?.choices?.[0]?.message?.reasoning
            ?? data?.choices?.[0]?.reasoning
            ?? data?.thinking
            ?? data?.reasoning
            ?? ''
        );
    } catch (_e) {
        return '';
    }
}

/** 提取加密推理签名（Gemini thoughtSignature / OpenRouter reasoning.encrypted）。 */
export function extractReasoningSignatureFromData(data, { mainApi = null, chatCompletionSource = null } = {}) {
    try {
        if (Array.isArray(data?.choices?.[0]?.message?.reasoning_details)) {
            for (const detail of data.choices[0].message.reasoning_details) {
                if (detail && !/^tool_/.test(detail.id || '') && detail.type === 'reasoning.encrypted' && detail.data) {
                    return detail.data;
                }
            }
        }
        if (Array.isArray(data?.responseContent?.parts)) {
            for (const part of data.responseContent.parts) {
                if (part && part.thoughtSignature && typeof part.text === 'string') {
                    return part.thoughtSignature;
                }
            }
        }
    } catch (_e) { /* noop */ }
    return null;
}

/** 是否为隐藏推理模型（对齐 ST 的模型前缀匹配清单）。 */
export function isHiddenReasoningModel() {
    try {
        const c = st();
        const model = typeof c.getChatCompletionModel === 'function' ? String(c.getChatCompletionModel() || '') : '';
        return ['gpt-4.5', 'o1', 'o3', 'gemini-2.0-flash-thinking-exp', 'gemini-2.0-pro-exp'].some((n) => model.startsWith(n));
    } catch (_e) {
        return false;
    }
}

/** 刷新消息推理 UI：转发宿主 getContext()，缺失时 no-op。 */
export function updateReasoningUI(messageIdOrElement, { reset = false } = {}) {
    try {
        const c = st();
        if (typeof c.updateReasoningUI === 'function') {
            return c.updateReasoningUI(messageIdOrElement, { reset });
        }
    } catch (_e) { /* noop */ }
    return undefined;
}

/** 加载模板数据（来自设置导入时）。 */
export async function loadReasoningTemplates(data) {
    try {
        if (data && Array.isArray(data.reasoning) && data.reasoning.length > 0) {
            reasoning_templates = data.reasoning;
        }
    } catch (_e) { /* noop */ }
}

/** 初始化（DOM 事件绑定等在 rikkaST 运行时由宿主负责，此处 no-op）。 */
export function initReasoning() {}

/** 最小占位：推理状态处理器（ST 内部 StreamingProcessor 使用；扩展极少实例化）。 */
export class ReasoningHandler {
    constructor() {
        this.state = ReasoningState.None;
        this.reasoning = '';
    }
    setState(state) { this.state = state; return this; }
    process() { return null; }
    reset() { this.state = ReasoningState.None; this.reasoning = ''; }
    render() {}
}

/** 最小占位：提示级推理（静态方法返回空前缀）。 */
export class PromptReasoning {
    static getLatestPrefix() { return ''; }
    static getLatestReasoning() { return ''; }
}
