import * as shimInject from '../shims/inject-shims.js';
import { MessageFormatter } from './message-formatter.js';
/**
 * rikkaST st-compat: script.js
 * ============================================================
 * 模拟 SillyTavern `public/script.js` 的导出面（第三方扩展 import 契约）。
 *
 * 设计（对齐 TauriTavern "资源端点 + API 表面" 哲学）：
 * - 数据类导出（chat / characters / name1 / ...）使用 `export let` + live binding：
 *   runtime 每次 refreshAll() 后广播 'rikka-st-refresh'，本模块重新快照 → 引用方始终读到最新值；
 * - 函数类导出为"转发器"：调用宿主 getContext() 上的同名实现，缺失时降级为安全默认值；
 * - 纯 DOM / 后端行为（消息节点重绘、生图等）在 P0 为 no-op（不抛错），P1 再逐步接线。
 */

const rootWin = () => (typeof window !== 'undefined' ? window : globalThis);
const st = () => {
    const w = rootWin();
    return (w && w.SillyTavern && typeof w.SillyTavern.getContext === 'function') ? w.SillyTavern.getContext() : {};
};
const host = () => {
    const w = rootWin();
    return (w && w.__rikkaSt) || {};
};

// ---------------------------------------------------------------- live 会话数据
export let chat = [];
export let characters = [];
export let name1 = 'User';
export let name2 = 'AI';
export let this_chid = 0;
export let chat_metadata = {};
export let extension_prompts = {};
export let extension_settings = {};
export let user_avatar = '';
export let online_status = 'no_connection';
export let is_send_press = false;
export let streamingProcessor = null;
export let externalAbortController = null;
export let isChatSaving = false;
export let main_api = 'openai';
export let settings = {};
export let amount_gen = 512;
export let max_context = 32768;
export let selected_group = null;
export let groups = [];

function __refresh() {
    try {
        const c = st();
        if (Array.isArray(c.chat)) chat = c.chat;
        if (Array.isArray(c.characters)) characters = c.characters;
        if (typeof c.name1 === 'string' && c.name1) name1 = c.name1;
        if (typeof c.name2 === 'string' && c.name2) name2 = c.name2;
        this_chid = (typeof c.characterId === 'number') ? c.characterId : 0;
        if (c.chatMetadata && typeof c.chatMetadata === 'object') chat_metadata = c.chatMetadata;
        if (c.extensionSettings && typeof c.extensionSettings === 'object') extension_settings = c.extensionSettings;
        if (c.streamingProcessor !== undefined) streamingProcessor = c.streamingProcessor;
    } catch (_e) { /* noop */ }
}
__refresh();
try {
    window.addEventListener('rikka-st-refresh', () => { try { __refresh(); } catch (_e) { /* noop */ } });
} catch (_e) { /* noop */ }

// ---------------------------------------------------------------- 事件总线（与 runtime 同实例）
const ES = st().eventSource || host().eventSource;
export const eventSource = ES || {
    on() {}, once() {}, off() {}, emit() {}, emitAndWait() {},
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
};
export const event_types = st().eventTypes || st().event_types || {};

// ---------------------------------------------------------------- 常量（对齐 ST1.18 popup/script）
export const extension_prompt_types = { IN_PROMPT: 0, IN_CHAT: 1, BEFORE_PROMPT: 2 };
export const extension_prompt_roles = { SYSTEM: 0, USER: 1, ASSISTANT: 2 };
export const MAX_INJECTION_DEPTH = 10000;
export const depth_prompt_depth_default = 4;
export const depth_prompt_role_default = 'system';
export const default_avatar = 'img/ai4.png';
export const system_avatar = 'img/five.png';
export const comment_avatar = 'img/quill.png';
export const default_user_avatar = 'img/user-default.png';
export const systemUserName = 'SillyTavern System';
export const neutralCharacterName = 'Assistant';
export const system_message_types = { ASSISTANT: 'assistant', USER: 'user', SYSTEM: 'system' };

// ---------------------------------------------------------------- 函数转发器
function fwd(path, fallback) {
    return (...args) => {
        try {
            const c = st();
            const target = String(path).split('.').reduce((o, k) => (o == null ? o : o[k]), c);
            if (typeof target === 'function') return target.apply(c, args);
        } catch (_e) { /* noop */ }
        return typeof fallback === 'function' ? fallback(...args) : fallback;
    };
}

export const getRequestHeaders = fwd('getRequestHeaders', () => ({ 'Content-Type': 'application/json' }));
export const getCurrentChatId = fwd('getCurrentChatId', () => String(st().chatId || ''));
export const substituteParams = fwd('substituteParams', (t) => t);
export const substituteParamsExtended = fwd('substituteParamsExtended', (t) => t);
export const getCharacterCardFields = fwd('getCharacterCardFields', () => ({
    name: '', description: '', personality: '', scenario: '', first_mes: '', mes_example: '',
}));
export const reloadCurrentChat = fwd('reloadCurrentChat', () => Promise.resolve());
export const reloadMarkdownProcessor = fwd('reloadMarkdownProcessor', () => {});
export const saveSettingsDebounced = fwd('saveSettingsDebounced', () => {});
export const saveSettings = saveSettingsDebounced;
export const saveMetadataDebounced = fwd('saveMetadataDebounced', () => {});
export const saveMetadata = saveMetadataDebounced;
export const saveChat = fwd('saveChat', () => {});
export const saveChatConditional = () => {
    try {
        const f = st().saveChat;
        if (typeof f === 'function') return Promise.resolve(f());
    } catch (_e) { /* noop */ }
    return Promise.resolve();
};
export const cancelDebouncedChatSave = () => {};
export const generate = fwd('generate', () => Promise.resolve(''));
export const generateRaw = fwd('generateRaw', () => Promise.resolve(''));
export const generateQuietPrompt = fwd('generateQuietPrompt', () => Promise.resolve(''));
export const Generate = generate;
export const stopGeneration = fwd('stopGeneration', () => {
    try {
        if (externalAbortController && typeof externalAbortController.abort === 'function') {
            externalAbortController.abort();
        }
    } catch (_e) { /* noop */ }
    try {
        const w = rootWin();
        if (w && w.RikkaBridge && typeof w.RikkaBridge.stopGeneration === 'function') {
            w.RikkaBridge.stopGeneration();
        }
    } catch (_e) { /* noop */ }
    return true;
});
export const clearChat = fwd('clearChat', () => {});

// ---------------------------------------------------------------- 扩展提示（同步写本地表并转发至宿主/shimInject）
export function setExtensionPrompt(key, value, position, depth, scan, role, filter) {
    try {
        const k = String(key || '');
        extension_prompts[k] = { value, position, depth, scan, role, filter };
        const c = st();
        if (c && typeof c.setExtensionPrompt === 'function') {
            c.setExtensionPrompt(k, value, position, depth, scan, role, filter);
            return;
        }
        const posStr = position === extension_prompt_types.BEFORE_PROMPT ? 'before_prompt'
            : (position === extension_prompt_types.IN_PROMPT ? 'in_prompt' : 'in_chat');
        const roleStr = (role === extension_prompt_roles.USER || role === 'user') ? 'user'
            : ((role === extension_prompt_roles.ASSISTANT || role === 'assistant') ? 'assistant' : 'system');
        if (!value || String(value).length === 0) {
            shimInject.uninjectPrompts([k]);
        } else {
            shimInject.injectPrompts([{
                id: k,
                content: String(value),
                position: posStr,
                depth: typeof depth === 'number' ? depth : 4,
                role: roleStr,
                should_scan: !!scan,
                filter,
            }]);
        }
    } catch (_e) { /* noop */ }
}
export function getExtensionPromptByName(name) {
    const p = extension_prompts[name];
    return p ? p.value : '';
}
export function getExtensionPromptRoleByName(name) {
    const p = extension_prompts[name];
    return p ? p.role : extension_prompt_roles.SYSTEM;
}

// ---------------------------------------------------------------- 角色 / 消息读取
export const getCharacters = () => Promise.resolve(characters || []);
export function getOneCharacter(avatar) {
    const list = characters || [];
    return list.find((c) => c && (c.avatar === avatar || c.name === avatar)) || list[0] || {};
}
export function getThumbnailUrl(type = 'avatar', file = '') {
    try {
        const c = st();
        if (c && typeof c.getThumbnailUrl === 'function') {
            return c.getThumbnailUrl(type, file);
        }
        const f = String(file || '').trim();
        if (f.startsWith('http://') || f.startsWith('https://') || f.startsWith('data:') || f.startsWith('blob:')) {
            return f;
        }
        const cleanType = encodeURIComponent(String(type || 'avatar'));
        const effectiveFile = f || (characters && characters[this_chid] && characters[this_chid].avatar) || '';
        if (!effectiveFile) return default_avatar;
        return `https://appassets.androidplatform.net/thumbnail?type=${cleanType}&file=${encodeURIComponent(effectiveFile)}`;
    } catch (_e) {
        return '';
    }
}
export const getPastCharacterChats = () => Promise.resolve([]);
export const getUserAvatar = () => user_avatar || '';
export const getMaxContextSize = () => max_context;
export const isGenerating = () => !!is_send_press;
export const isOdd = (n) => (Number(n) % 2) !== 0;
export function countOccurrences(str, needle) {
    try {
        if (!needle) return 0;
        return String(str).split(String(needle)).length - 1;
    } catch (_e) { return 0; }
}

// ---------------------------------------------------------------- 显示 / DOM 类（P0 no-op）
export const messageFormatter = MessageFormatter;
export function messageFormatting(mes, ch_name, isSystem, isUser, messageId, sanitizerOverrides = {}, isReasoning = false) {
    try {
        const c = st();
        if (c && typeof c.messageFormatting === 'function') {
            return c.messageFormatting(mes, ch_name, isSystem, isUser, messageId, sanitizerOverrides, isReasoning);
        }
    } catch (_e) { /* noop */ }
    return MessageFormatter.format(mes, ch_name, isSystem, isUser, messageId, sanitizerOverrides, isReasoning);
}
export const updateMessageBlock = fwd('updateMessageBlock', () => {});
export const addOneMessage = () => {};
export const printMessages = () => {};
export const printCharacters = () => {};
export const scrollChatToBottom = () => {};
export const deleteLastMessage = async () => {
    try {
        const c = st();
        if (c && typeof c.deleteLastMessage === 'function') {
            return await c.deleteLastMessage();
        }
        if (window.RikkaBridge && typeof window.RikkaBridge.triggerSlash === 'function') {
            return window.RikkaBridge.triggerSlash('/del 1');
        }
    } catch (_e) { /* noop */ }
};
export const syncMesToSwipe = () => {};
export const activateSendButtons = () => {};
export const deactivateSendButtons = () => {};
export const showSwipeButtons = () => {};
export const setGenerationProgress = () => {};
export const setUserName = () => {};
export const select_selected_character = () => {};
export const selectCharacterById = () => Promise.resolve();
export const deleteCharacter = () => Promise.resolve();
export const unshallowCharacter = () => Promise.resolve();
export const saveCharacterDebounced = () => {};
export const parseMesExamples = (text) => text;
export const baseChatReplace = (text) => text;
export const cleanUpMessage = (mes) => String(mes == null ? '' : mes);
export const getBiasStrings = () => ({});
export const appendMediaToMessage = fwd('appendMediaToMessage', () => {});
export const addCopyToCodeBlocks = fwd('addCopyToCodeBlocks', () => {});
export const nai_settings = {};
export const GenerateOptions = {};

// ---------------------------------------------------------------- 兼容测试钩子（部分第三方扩展/工具链 import）
export function __setChatMetadata(meta) {
    try {
        chat_metadata = (meta && typeof meta === 'object') ? meta : {};
    } catch (_e) { /* noop */ }
}

// ── [batch16] getSlideToggleOptions ──────────────────────────────
// 依据：refdeps\st-memory-enhancement\core\manager.js:328 —— `getSlideToggleOptions: APP.getSlideToggleOptions`
//   （APP 来自 `import * as APP from '/script.js'`，缺名字会让该 import 整条失败）
// ST 语义：reduce_motion 时动画时长 0；否则默认。
export function getSlideToggleOptions() {
    try {
        const pu = (window.__rikkaSt && window.__rikkaSt.powerUser) || null;
        if (pu && pu.reduce_motion) return { duration: 0 };
    } catch (err) { /* noop */ }
    return {};
}

// ================================================================
// [v243 BUGFIX C3] SillyTavern 1.18.0 原生面补齐（第一批：会话/消息/元数据）
//
// 依据：把 D:\SillyTavern-1.18.0.zip 的 public/script.js 导出面（249 个）与
// rikkaST 本文件（91 个）做集合差，挑出**卡脚本/扩展真的会调用、且能在现有宿主能力上
// 真实实现**的那批补上。其余（swipe 导航 / 删除楼层 / quiet 生成 / 角色卡编辑）
// 需要新增宿主桥，登记在 docs/ST-FEATURE-MATRIX.md，本轮不伪造。
// ================================================================

/** ST `CLIENT_VERSION`。rikkaST 的 RikkaBridge.getVersion() 就是 "1.18.0"，保持一致。 */
export const CLIENT_VERSION = '1.19.0';

export function extractMessageFromData(data, activeApi = main_api) {
    void activeApi;
    if (!data || typeof data !== 'object') return '';
    if (Array.isArray(data.choices) && data.choices[0]) {
        const ch = data.choices[0];
        if (ch.message && typeof ch.message.content === 'string') return ch.message.content;
        if (typeof ch.text === 'string') return ch.text;
        if (ch.delta && typeof ch.delta.content === 'string') return ch.delta.content;
    }
    if (Array.isArray(data.content)) {
        return data.content.map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('');
    }
    if (Array.isArray(data.candidates) && data.candidates[0]?.content?.parts) {
        return data.candidates[0].content.parts.filter((p) => p && !p.thought).map((p) => p.text || '').join('');
    }
    return String(data.mes ?? data.content ?? data.response ?? '');
}

export async function deleteMessage(messageId, swipeId = undefined, confirm = false, { deleteToolCalls = false } = {}) {
    void swipeId; void confirm;
    try {
        const c = st();
        if (c && typeof c.deleteMessage === 'function') {
            return await c.deleteMessage(messageId, swipeId, confirm, { deleteToolCalls });
        }
        const arr = c.chat || chat;
        const idx = Number(messageId);
        if (!Array.isArray(arr) || !Number.isInteger(idx) || idx < 0 || idx >= arr.length) return;
        const target = arr[idx];
        arr.splice(idx, 1);
        if (deleteToolCalls && target && Array.isArray(target.extra?.tool_invocations)) {
            const ids = new Set(target.extra.tool_invocations.map((t) => t?.id).filter(Boolean));
            if (ids.size > 0) {
                for (let i = arr.length - 1; i >= 0; i--) {
                    if (arr[i]?.extra?.tool_call_id && ids.has(arr[i].extra.tool_call_id)) {
                        arr.splice(i, 1);
                    }
                }
            }
        }
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.updateChatMessages === 'function') {
            window.RikkaBridge.updateChatMessages(JSON.stringify(arr));
        }
        if (c.eventSource && typeof c.eventSource.emit === 'function') {
            await c.eventSource.emit(event_types.MESSAGE_DELETED || 'message_deleted', idx);
        }
    } catch (_e) { /* noop */ }
}

/** ST `settingsReady`：ST 里是「设置加载完成」的 Promise；rikkaST 启动即已就绪。 */
export const settingsReady = Promise.resolve();

/** ST `getChat()`：返回聊天数组（最常用的原生读取入口之一）。 */
export function getChat() {
    try { return st().chat || chat || []; } catch (_e) { return chat || []; }
}

/** ST `getNextMessageId()`：下一条消息的 id（= 当前条数）。 */
export function getNextMessageId() {
    try { return (st().chat || chat || []).length; } catch (_e) { return 0; }
}

/**
 * ST `updateChatMetadata(metadata, save)`：合并写入 `chat_metadata`，默认触发保存。
 * 对齐 ST 语义：只做浅合并（调用方传什么字段就覆盖什么字段）。
 */
export function updateChatMetadata(metadata, save = true) {
    try {
        if (metadata && typeof metadata === 'object') {
            Object.assign(chat_metadata, metadata);
            const c = st();
            if (c.chatMetadata && typeof c.chatMetadata === 'object') {
                Object.assign(c.chatMetadata, metadata);
            }
        }
        if (save) saveMetadataDebounced();
    } catch (_e) { /* noop */ }
}

/** ST `getCurrentChatDetails()`：当前会话的标识信息（rikkaST 用 conversationId 作为 id/file_name）。 */
export function getCurrentChatDetails() {
    try {
        const id = String(st().chatId || '');
        return { id, file_name: id, chat_metadata: chat_metadata };
    } catch (_e) {
        return { id: '', file_name: '', chat_metadata: {} };
    }
}

/** ST `redisplayChat()`：整聊天重新渲染。rikkaST 的等价动作是 reloadCurrentChat()。 */
export function redisplayChat() {
    try {
        const f = st().reloadCurrentChat;
        if (typeof f === 'function') return Promise.resolve(f());
    } catch (_e) { /* noop */ }
    return Promise.resolve();
}

/** ST `saveChatDebounced()`（= saveChat 的去抖版）。 */
export const saveChatDebounced = saveChat;

/** ST `substituteParamsLegacy()`：旧签名版本，rikkaST 与 substituteParams 同实现。 */
export const substituteParamsLegacy = substituteParams;

/** ST `getStoppingStrings()`：rikkaST 目前无自定义停止串。 */
export const getStoppingStrings = () => [];

/** ST `isStreamingEnabled()`。 */
export const isStreamingEnabled = () => true;

/** ST 上下文长度便捷函数（rikkaST 用 live 变量 max_context）。 */
export const getMaxContextTokens = () => max_context;
export const getMaxPromptTokens = () => max_context;
export const getMaxResponseTokens = () => amount_gen;

/**
 * ST `ensureSwipes(message)`：保证 message.swipes 是「字符串数组」且与 variables 对齐。
 * 与 runtime.js pullChat() 的归一化规则保持一致（MVU 会对 swipe 条目调用 matchAll，
 * 严禁对象占位 —— 这是真机上炸过的点）。
 */
export function ensureSwipes(message) {
    try {
        if (!message || typeof message !== 'object') return message;
        const mesText = String(message.mes == null ? '' : message.mes);
        const vars = Array.isArray(message.variables) ? message.variables : [{}];
        if (!Array.isArray(message.swipes) || message.swipes.length === 0) {
            message.swipes = new Array(Math.max(1, vars.length)).fill(mesText);
        } else {
            message.swipes = message.swipes.map((s) => (typeof s === 'string' ? s : mesText));
        }
        if (typeof message.swipe_id !== 'number') message.swipe_id = 0;
        return message;
    } catch (_e) { return message; }
}

/** ST `syncSwipeToMes(message)`：把当前 swipe 的文本同步回 mes。 */
export function syncSwipeToMes(message) {
    try {
        if (!message || !Array.isArray(message.swipes)) return message;
        const idx = (typeof message.swipe_id === 'number' && message.swipe_id >= 0) ? message.swipe_id : 0;
        message.mes = String(message.swipes[idx] == null ? '' : message.swipes[idx]);
        return message;
    } catch (_e) { return message; }
}

// ---- 纯 UI 类：rikkaST 的滑动/消息节点由宿主 Compose 原生渲染，这些调用保持 no-op（不抛错）
export const refreshSwipeButtons = () => {};
export const updateSwipeCounter = () => {};
export const updateViewMessageIds = () => {};
export const printCharactersDebounced = () => {};
export const hideSwipeButtons = () => {};
export const updateEditArrowClasses = () => {};
export const scrollOnMediaLoad = () => {};
export const isMessageSwipeable = () => true;
export const isSwipingAllowed = () => true;

export function setCharacterId(id) {
    this_chid = (id === undefined || id === null) ? undefined : Number(id);
    try {
        const c = st();
        if (c && typeof c === 'object') c.characterId = this_chid;
    } catch (_e) { /* noop */ }
}

export function setCharacterName(name) {
    name2 = String(name ?? '');
    try {
        const c = st();
        if (c && typeof c === 'object') c.name2 = name2;
    } catch (_e) { /* noop */ }
}

export function setExternalAbortController(controller) {
    externalAbortController = controller || null;
    try {
        const c = st();
        if (c && typeof c.setExternalAbortController === 'function') c.setExternalAbortController(controller);
    } catch (_e) { /* noop */ }
}

export function setSendButtonState(value) {
    is_send_press = Boolean(value);
    try {
        const c = st();
        if (c && typeof c === 'object') c.is_send_press = is_send_press;
    } catch (_e) { /* noop */ }
}

export async function callPopup(text, type = 'text', inputValue = '', popupOptions = {}) {
    try {
        const c = st();
        if (c && typeof c.callPopup === 'function') return await c.callPopup(text, type, inputValue, popupOptions);
        if (c && typeof c.callGenericPopup === 'function') return await c.callGenericPopup(text, type, inputValue, popupOptions);
    } catch (_e) { /* noop */ }
    return type === 'input' ? String(inputValue ?? '') : true;
}

export async function processCommands(commandText) {
    const cmd = String(commandText ?? '').trim();
    if (!cmd) return '';
    try {
        const c = st();
        if (c && typeof c.executeSlashCommandsWithOptions === 'function') {
            return await c.executeSlashCommandsWithOptions(cmd);
        }
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.triggerSlash === 'function') {
            return window.RikkaBridge.triggerSlash(cmd);
        }
    } catch (_e) { /* noop */ }
    return '';
}

export const doNavbarIconClick = () => {};

// ---- [ST 1.19.0 Full Export Parity] ----
export const ANIMATION_DURATION_DEFAULT = 125;
export const CONNECT_API_MAP = {};
export const DEFAULT_PRINT_TIMEOUT = 100;
export const DEFAULT_SAVE_EDIT_TIMEOUT = 1000;
export const UNIQUE_APIS = [];
export let abortStatusCheck = typeof AbortController !== 'undefined' ? new AbortController() : { abort() {}, signal: {} };
export let active_character = '';
export let active_group = '';
export let animation_duration = 125;
export let animation_easing = 'ease-in-out';
export function appendImageToMessage(..._args) { return null; }
export function buildAvatarList(..._args) { return null; }
export function cancelStatusCheck(..._args) { return null; }
export function cancelTtsPlay(..._args) { return null; }
export function changeMainAPI(..._args) { return null; }
export const charDragDropHandler = {};
export const characterGroupOverlay = {};
export function characterToEntity(..._args) { return null; }
export const chatDragDropHandler = {};
export const chatElement = {};
export function clearItemizedPrompts() {}
export async function closeCurrentChat(..._args) { return null; }
export function closeMessageEditor(..._args) { return null; }
export const converter = {};
export function createLazyFields(..._args) { return null; }
export function createModelIcon(..._args) { return null; }
export async function createOrEditCharacter(..._args) { return null; }
export function createRawPrompt(..._args) { return null; }
export const create_save = {};
export async function deleteCharacterChatByName(..._args) { return null; }
export async function deleteItemizedPrompts() {}
export async function deleteSwipe(..._args) { return null; }
export function displayOnlineStatus(..._args) { return null; }
export async function displayPastChats(..._args) { return null; }
export let displayVersion = 'SillyTavern 1.19.0';
export async function doNewChat(..._args) { return null; }
export async function duplicateCharacter(..._args) { return null; }
export function ensureMessageMediaIsArray(..._args) { return null; }
export const entitiesFilter = {};
export function extractJsonFromData(..._args) { return null; }
export function extractMessageBias(..._args) { return null; }
export function findItemizedPromptSet() { return null; }
export function formatCharacterAvatar(..._args) { return null; }
export async function generateRawData(..._args) { return null; }
export function getCharacterAvatar(..._args) { return null; }
export function getCharacterCardFieldsLazy(..._args) { return null; }
export function getCharacterSource(..._args) { return null; }
export async function getChatsFromFiles(..._args) { return null; }
export function getEntitiesList(..._args) { return null; }
export async function getExtensionPrompt(..._args) { return null; }
export function getExtensionPromptMaxDepth(..._args) { return null; }
export function getFirstDisplayedMessageId(..._args) { return null; }
export function getGenerateUrl(..._args) { return null; }
export function getGeneratingApi(..._args) { return null; }
export function getGeneratingModel(..._args) { return null; }
export function getMediaDisplay(..._args) { return null; }
export function getMediaIndex(..._args) { return null; }
export function getOverswipeBehavior(..._args) { return null; }
export async function getSettings(..._args) { return null; }
export function getSystemMessageByType() { return null; }
export async function getUserAvatars() { return []; }
export function groupToEntity(..._args) { return null; }
export async function handleDeleteCharacter(..._args) { return null; }
export async function importCharacterChat(..._args) { return null; }
export const itemizedParams = {};
export const itemizedPrompts = [];
export const koboldai_setting_names = [];
export const koboldai_settings = {};
export const lastSwipeInfo = {};
export async function loadItemizedPrompts() {}
export let menu_type = '';
export const mesForShowdownParse = {};
export async function messageEdit(..._args) { return null; }
export async function newAssistantChat(..._args) { return null; }
export const novelai_setting_names = [];
export const novelai_settings = {};
export async function openCharacterChat(..._args) { return null; }
export async function pingServer(..._args) { return null; }
export async function processDroppedFiles(..._args) { return null; }
export async function promptItemize() {}
export let recentSwipes = 0;
export const reloadChatMutex = {};
export async function reloadCurrentChatUnsafe(..._args) { return null; }
export function removeDepthPrompts(..._args) { return null; }
export function removeMacros(..._args) { return null; }
export async function renameCharacter(..._args) { return null; }
export async function renameChat(..._args) { return null; }
export async function renameGroupOrCharacterChat(..._args) { return null; }
export function renderTemplate(tpl = '') { return String(tpl ?? ''); }
export async function replaceCurrentChat(..._args) { return null; }
export function replaceItemizedPromptText() {}
export function resetChatState(..._args) { return null; }
export function resultCheckStatus(..._args) { return null; }
export async function saveItemizedPrompts() {}
export async function saveReply(..._args) { return null; }
export function selectRightMenuWithAnimation(..._args) { return null; }
export function select_rm_info(..._args) { return null; }
export let selected_button = '';
export async function sendGenerationRequest(..._args) { return null; }
export async function sendMessageAsUser(..._args) { return null; }
export async function sendStreamingRequest(..._args) { return null; }
export function sendSystemMessage(type, text, extra = {}) { void type; void extra; return text || ''; }
export async function sendTextareaMessage(..._args) { return null; }
export function setActiveCharacter(..._args) { return null; }
export function setActiveGroup(..._args) { return null; }
export function setAnimationDuration(..._args) { return null; }
export async function setCharacterSettingsOverrides(..._args) { return null; }
export function setEditedMessageId(..._args) { return null; }
export function setGenerationParamsFromPreset(..._args) { return null; }
export function setMenuType(..._args) { return null; }
export function setOnlineStatus(..._args) { return null; }
export async function setScenarioOverride(..._args) { return null; }
export async function setUserAvatar() {}
export function shouldAutoContinue(..._args) { return null; }
export async function showMoreMessages(..._args) { return null; }
export function startStatusLoading(..._args) { return null; }
export function stopStatusLoading(..._args) { return null; }
export async function swipe(..._args) { return null; }
export let swipeState = 'none';
export async function swipe_left(..._args) { return null; }
export async function swipe_right(..._args) { return null; }
export let swipesHidden = false;
export const system_messages = {};
export function tagToEntity(..._args) { return null; }
export const talkativeness_default = 0.5;
export const token = {};
export function triggerAutoContinue(..._args) { return null; }
export function updateMessageElement(..._args) { return null; }
export async function updateRemoteChatName(..._args) { return null; }
