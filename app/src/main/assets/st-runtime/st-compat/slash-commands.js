/**
 * rikkaST st-compat: slash-commands/slash-commands.js（根入口）
 * ============================================================
 * 对齐 SillyTavern 1.19.0 `public/scripts/slash-commands.js` 导出面。
 * 优先支持通过 `SlashCommandParser.addCommandObject` / `registerSlashCommand` 在 JS 侧注册的扩展命令，
 * 其余命令直连宿主 `RikkaBridge.triggerSlash`，并准确识别未知命令错误与 `/abort` 中止状态。
 */

import { __getRegistry } from './slash-commands/SlashCommand.js';
import { SlashCommandParser, parser } from './slash-commands/SlashCommandParser.js';

export const SLASH_COMMAND_NAME_REGEX = /^[a-zA-Z0-9_-]+$/;

export class SlashCommandAbortController {
    constructor() {
        this.aborted = false;
        this.isAborted = false;
        this.reason = '';
    }
    abort(reason = '') {
        this.aborted = true;
        this.isAborted = true;
        this.reason = String(reason ?? '');
    }
}

export class SlashCommandBreakController {
    constructor() {
        this.isBroken = false;
        this.value = '';
    }
    break(value = '') {
        this.isBroken = true;
        this.value = String(value ?? '');
    }
}

export class SlashCommandDebugController {
    constructor() {
        this.stack = [];
    }
}

function parseSimpleSlashInvocation(text) {
    const trimmed = String(text == null ? '' : text).trim();
    if (!trimmed.startsWith('/') || trimmed.includes('|')) return null;
    const body = trimmed.slice(1).trimStart();
    const sp = body.search(/\s/);
    const name = (sp === -1 ? body : body.slice(0, sp)).toLowerCase();
    const rawArgs = sp === -1 ? '' : body.slice(sp + 1).trim();
    const namedArgs = {};
    const restParts = [];
    const tokenRe = /([a-zA-Z0-9_-]+)=("[^"]*"|'[^']*'|[^\s]+)|("[^"]*"|'[^']*'|[^\s]+)/g;
    let m;
    while ((m = tokenRe.exec(rawArgs)) !== null) {
        if (m[1]) {
            let v = m[2] || '';
            if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.slice(1, -1);
            }
            namedArgs[m[1]] = v;
        } else if (m[3]) {
            let v = m[3];
            if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.slice(1, -1);
            }
            restParts.push(v);
        }
    }
    return { name, namedArgs, unnamedArgs: restParts.join(' ') };
}

function toExecutionResult(rawValue, commandText = '') {
    const text = rawValue == null ? '' : String(rawValue);
    const cmdTrim = String(commandText == null ? '' : commandText).trim().toLowerCase();
    const isAbort = text === '已中止' || text.startsWith('(slash abort') || cmdTrim === '/abort' || cmdTrim.startsWith('/abort ');
    const isErr = !isAbort && (
        text.startsWith('(slash error:') ||
        text === '(slash runner not ready)' ||
        text.startsWith('未知命令：') ||
        text.startsWith('命令不可用：') ||
        text.startsWith('命令缺少')
    );
    return {
        pipe: (isErr || isAbort) ? '' : text,
        isError: isErr,
        errorMessage: isErr ? text : '',
        isAbort: isAbort,
        isAborted: isAbort,
        isIdle: false,
        hasBackpipe: false,
    };
}

export async function executeSlashCommandsWithOptions(text, options) {
    void options;
    const cmdStr = String(text == null ? '' : text);
    try {
        const parsed = parseSimpleSlashInvocation(cmdStr);
        if (parsed) {
            const reg = __getRegistry();
            const jsCmd = reg.get(parsed.name) || reg.get(cmdStr.trim().slice(1).split(/\s+/)[0]);
            if (jsCmd && typeof jsCmd.callback === 'function') {
                const out = await jsCmd.callback(parsed.namedArgs, parsed.unnamedArgs);
                return toExecutionResult(out == null ? '' : String(out), cmdStr);
            }
        }
        if (window.RikkaBridge && typeof window.RikkaBridge.triggerSlash === 'function') {
            return toExecutionResult(window.RikkaBridge.triggerSlash(cmdStr), cmdStr);
        }
    } catch (e) {
        return toExecutionResult('(slash error: ' + String((e && e.message) || e) + ')', cmdStr);
    }
    return toExecutionResult('(slash runner not ready)', cmdStr);
}

export async function executeSlashCommands(text) {
    const r = await executeSlashCommandsWithOptions(text);
    return r && typeof r === 'object' ? r.pipe : r;
}

export async function executeSlashCommandsOnChatInput(text, options) {
    if (typeof text === 'string' && text.trim().startsWith('/')) {
        return await executeSlashCommandsWithOptions(text, options);
    }
    return { pipe: String(text ?? ''), isError: false, isAbort: false };
}

export function registerSlashCommand(name, callback, aliases = [], helpString = '', interruptsGeneration = false, purgeFromMessage = false) {
    void interruptsGeneration;
    void purgeFromMessage;
    return SlashCommandParser.addCommand(name, callback, aliases, helpString);
}

export function getSlashCommandsHelp() {
    return Object.keys(SlashCommandParser.commands || {}).join(', ');
}

export function initSlashCommandAutoComplete() {}

export { parser, SlashCommandParser };

// ---- [ST 1.19.0 Full Export Parity] ----
export const COMMENT_NAME_DEFAULT = 'Note';
export const CONNECT_API_MAP = {};
export const UNIQUE_APIS = [];
export function activateScriptButtons(..._args) { return null; }
export const commandsFromChatInputAbortController = {};
export function deactivateScriptButtons(..._args) { return null; }
export async function generateSystemMessage(..._args) { return null; }
export function getNameAndAvatarForMessage(..._args) { return null; }
export function initDefaultSlashCommands(..._args) { return null; }
export let isExecutingCommandsFromChatInput = false;
export function pauseScriptExecution(..._args) { return null; }
export function processChatSlashCommands(..._args) { return null; }
export async function promptQuietForLoudResponse(..._args) { return null; }
export async function sendMessageAs(..._args) { return null; }
export async function sendNarratorMessage(..._args) { return null; }
export async function setSlashCommandAutoComplete(..._args) { return null; }
export function stopScriptExecution(..._args) { return null; }
export function validateArrayArg(..._args) { return null; }
export function validateArrayArgString(..._args) { return null; }
