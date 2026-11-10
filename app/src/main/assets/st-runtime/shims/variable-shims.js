/**
 * MVU Standalone — 变量系统 Shim
 * 精确复制 JS-Slash-Runner variables.ts 的存储布局
 *
 * 存储位置:
 *   type:'message' → chat[msg_index].variables[swipe_id]
 *   type:'chat'    → chat_metadata.variables
 *   type:'global'  → extension_settings.variables.global
 */

import { deepClone } from './clone-util.js';
import { getCurrentMessageId } from './util-shims.js';


const _scopedVarMemory = {};

function resolveScopeKey(option) {
    const t = String((option && option.type) || 'chat');
    const ctx = getCtx() || {};
    if (t === 'character') {
        const cid = option.character_id ?? option.character ?? ctx.characterId ?? ctx.name2 ?? 'default';
        return 'char:' + String(cid);
    }
    if (t === 'preset') {
        const pid = option.preset_id ?? option.preset_name ?? option.preset ?? 'default';
        return 'preset:' + String(pid);
    }
    if (t === 'script') {
        let sid = option.script_id;
        if (!sid && typeof window.getScriptId === 'function') {
            try { sid = window.getScriptId(); } catch (_e) { /* noop */ }
        }
        return 'script:' + String(sid || 'default');
    }
    return t + ':default';
}

function getScopedVariablesMap(option) {
    const key = resolveScopeKey(option);
    try {
        const b = window.RikkaBridge || (window.parent && window.parent.RikkaBridge);
        if (b && typeof b.getScopedVariablesJson === 'function') {
            const raw = b.getScopedVariablesJson(key);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                    _scopedVarMemory[key] = parsed;
                    return parsed;
                }
            }
        }
    } catch (_e) { /* noop */ }
    if (!_scopedVarMemory[key] || typeof _scopedVarMemory[key] !== 'object') {
        _scopedVarMemory[key] = {};
    }
    return _scopedVarMemory[key];
}

function setScopedVariablesMap(option, variables) {
    const key = resolveScopeKey(option);
    const clean = (variables && typeof variables === 'object' && !Array.isArray(variables)) ? deepClone(variables) : {};
    _scopedVarMemory[key] = clean;
    try {
        const b = window.RikkaBridge || (window.parent && window.parent.RikkaBridge);
        if (b && typeof b.replaceScopedVariables === 'function') {
            b.replaceScopedVariables(key, JSON.stringify(clean));
        }
    } catch (_e) { /* noop */ }
}

function getCtx() {
    return window.SillyTavern.getContext();
}

function get_variables_without_clone(option) {
    const ctx = getCtx();
    const chat = ctx.chat;

    switch (option.type) {
        case 'message': {
            // 批次十三：面板模式下，「message 变量」语义上就是本条消息的变量。
            // 本条消息有变量时优先用自己的；没有才回落 JSR 原语义（最后一条非系统消息）。
            if (option.message_id === undefined || option.message_id === 'latest') {
                const selfId = getCurrentMessageId();
                const selfMsg = (selfId >= 0 && selfId < chat.length) ? chat.at(selfId) : null;
                const selfVars = selfMsg?.variables?.[selfMsg?.swipe_id ?? 0];
                if (selfVars && typeof selfVars === 'object' && Object.keys(selfVars).length > 0) {
                    return selfVars;
                }
            }
            const normalizedId = (option.message_id === undefined || option.message_id === 'latest') ? -1 : option.message_id;
            if (!_.inRange(normalizedId, -chat.length, chat.length)) {
                throw Error(`提供的消息楼层号 '${option.message_id}' 超出了范围 [${-chat.length}, ${chat.length})`);
            }
            let chatMessage;
            if (option.message_id === undefined || option.message_id === 'latest') {
                chatMessage = chat.filter(m => !m.is_system).at(normalizedId);
            } else {
                chatMessage = chat.at(normalizedId);
            }
            return chatMessage?.variables?.[chatMessage?.swipe_id ?? 0] ?? {};
        }
        case 'chat': {
            return _.get(ctx.chatMetadata, 'variables', {});
        }
        case 'global': {
            return _.get(ctx.extensionSettings, 'variables.global', {});
        }
        case 'character':
        case 'preset':
        case 'script': {
            return getScopedVariablesMap(option);
        }
        case 'extension': {
            return _.get(ctx.extensionSettings, option.extension_id, {});
        }
        default:
            return {};
    }
}

export function getVariables(option = { type: 'chat' }) {
    return deepClone(get_variables_without_clone(option));
}

export function getAllVariables() {
    let result = _({});
    result = result.assign(
        get_variables_without_clone({ type: 'global' }),
        get_variables_without_clone({ type: 'chat' }),
    );
    return deepClone(result.value());
}

export function replaceVariables(variables, option = { type: 'chat' }) {
    const ctx = getCtx();
    const chat = ctx.chat;

    switch (option.type) {
        case 'message': {
            // 面板模式（批次十三）：写回也要落到「本条消息」。
            // 读路径已改成「自身优先」，写路径若继续按 JSR 原语义写最后一条，
            // 就会出现「读的是自己的变量、写的是别人的变量」这种数据错位。
            let panelMsgId = option.message_id;
            if (option.message_id === undefined || option.message_id === 'latest') {
                const selfId = getCurrentMessageId();
                if (selfId >= 0 && selfId < chat.length) {
                    panelMsgId = selfId;
                }
            }
            const msgId = (panelMsgId === undefined || panelMsgId === 'latest') ? -1 : panelMsgId;
            if (!_.inRange(msgId, -chat.length, chat.length)) {
                throw Error(`提供的消息楼层号 '${option.message_id}' 超出了范围 (${-chat.length}, ${chat.length})`);
            }
            const chatMessage = chat.at(msgId);
            // 确保 variables 是数组
            if (!_.has(chatMessage, 'variables')) {
                _.set(chatMessage, 'variables', _.times(chatMessage.swipes?.length ?? 1, _.constant({})));
            }
            // 兼容: plain object → array
            if (_.isPlainObject(_.get(chatMessage, 'variables'))) {
                _.set(
                    chatMessage,
                    'variables',
                    _.range(0, chatMessage.swipes?.length ?? 1).map(i => chatMessage.variables[i] ?? {}),
                );
            }
            _.set(chatMessage, ['variables', _.get(chatMessage, 'swipe_id', 0)], variables);
            // debounced save
            _debouncedSaveChat();
            break;
        }
        case 'chat': {
            _.set(ctx.chatMetadata, 'variables', variables);
            if (ctx.saveMetadataDebounced) ctx.saveMetadataDebounced();
            break;
        }
        case 'global': {
            if (!ctx.extensionSettings.variables) {
                ctx.extensionSettings.variables = {};
            }
            _.set(ctx.extensionSettings.variables, 'global', variables);
            if (ctx.saveSettingsDebounced) ctx.saveSettingsDebounced();
            break;
        }
        case 'character':
        case 'preset':
        case 'script': {
            setScopedVariablesMap(option, variables);
            break;
        }
        case 'extension': {
            _.set(ctx.extensionSettings, option.extension_id, variables);
            if (ctx.saveSettingsDebounced) ctx.saveSettingsDebounced();
            break;
        }
    }
}

// [FIX M2] debounced save — 每次调用时重新获取 ctx，避免 stale closure
let _saveChatTimer = null;
function _debouncedSaveChat() {
    if (_saveChatTimer) clearTimeout(_saveChatTimer);
    _saveChatTimer = setTimeout(() => {
        _saveChatTimer = null;
        const freshCtx = getCtx();
        if (freshCtx.saveChat) freshCtx.saveChat();
    }, 1000);
}

export function updateVariablesWith(updater, option = { type: 'chat' }) {
    const variables = getVariables(option);
    const result = updater(variables);
    if (result && typeof result.then === 'function') {
        return result.then(r => {
            replaceVariables(r, option);
            return r;
        });
    }
    replaceVariables(result, option);
    return result;
}

export function insertOrAssignVariables(variables, option = { type: 'chat' }) {
    return updateVariablesWith(
        oldVars => _.mergeWith(oldVars, variables, (_lhs, rhs) => (_.isArray(rhs) ? rhs : undefined)),
        option,
    );
}

export function insertVariables(variables, option = { type: 'chat' }) {
    return updateVariablesWith(
        oldVars => _.mergeWith({}, variables, oldVars, (_lhs, rhs) => (_.isArray(rhs) ? rhs : undefined)),
        option,
    );
}

export function deleteVariable(variablePath, option = { type: 'chat' }) {
    let deleteOccurred = false;
    const variables = updateVariablesWith(oldVars => {
        deleteOccurred = _.unset(oldVars, variablePath);
        return oldVars;
    }, option);
    return { variables, delete_occurred: deleteOccurred };
}
