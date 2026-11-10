/**
 * rikkaST st-compat: slash-commands/SlashCommandParser.js
 * ============================================================
 * 对齐 SillyTavern 1.19.0 `SlashCommandParser`：
 * addCommandObject / addCommand 收集命令至共享注册表，同步通知宿主 `js_slash_commands_updated`，
 * 并提供 parse / parseCommand / parseNamedArgument / parseClosure / parseBreak 及 `__rikkaExecuteJsSlashCommand`。
 */

import { __getRegistry } from './SlashCommand.js';
import { SlashCommandClosure } from './SlashCommandClosure.js';

export const PARSER_FLAG = {
    STRICT_ESCAPING: 1,
    REPLACE_GETVAR: 2,
};

function notifyHostCommandsChanged() {
    try {
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.emitEvent === 'function') {
            const names = Array.from(__getRegistry().keys());
            window.RikkaBridge.emitEvent('js_slash_commands_updated', JSON.stringify(names));
        }
    } catch (_e) { /* noop */ }
}

export const SlashCommandParser = {
    commands: {},

    addCommandObject(cmd) {
        try {
            if (cmd && cmd.name) {
                const name = String(cmd.name);
                __getRegistry().set(name, cmd);
                __getRegistry().set(name.toLowerCase(), cmd);
                this.commands[name] = cmd;
                if (Array.isArray(cmd.aliases)) {
                    for (const alias of cmd.aliases) {
                        if (alias) {
                            __getRegistry().set(String(alias), cmd);
                            __getRegistry().set(String(alias).toLowerCase(), cmd);
                        }
                    }
                }
                notifyHostCommandsChanged();
            }
        } catch (_e) { /* noop */ }
        return cmd;
    },

    addCommand(name, callback, aliases = [], helpString = '') {
        const cmd = { name, callback: callback || (() => ''), aliases, helpString };
        try {
            __getRegistry().set(String(name), cmd);
            __getRegistry().set(String(name).toLowerCase(), cmd);
            this.commands[String(name)] = cmd;
            if (Array.isArray(aliases)) {
                for (const alias of aliases) {
                    if (alias) {
                        __getRegistry().set(String(alias), cmd);
                        __getRegistry().set(String(alias).toLowerCase(), cmd);
                    }
                }
            }
            notifyHostCommandsChanged();
        } catch (_e) { /* noop */ }
        return cmd;
    },

    removeCommand(name) {
        try {
            __getRegistry().delete(String(name));
            __getRegistry().delete(String(name).toLowerCase());
            delete this.commands[String(name)];
            notifyHostCommandsChanged();
        } catch (_e) { /* noop */ }
    },

    getCommand(name) {
        try {
            return __getRegistry().get(String(name)) || __getRegistry().get(String(name).toLowerCase()) || null;
        } catch (_e) {
            return null;
        }
    },

    parseCommand(text = '') {
        const trimmed = String(text ?? '').trim();
        if (!trimmed.startsWith('/')) return null;
        const body = trimmed.slice(1).trimStart();
        const sp = body.search(/\s/);
        const name = sp === -1 ? body : body.slice(0, sp);
        const args = sp === -1 ? '' : body.slice(sp + 1);
        return { name, args, raw: trimmed };
    },

    parseNamedArgument(token = '') {
        const s = String(token ?? '');
        const eq = s.indexOf('=');
        if (eq <= 0) return null;
        return { name: s.slice(0, eq).trim(), value: s.slice(eq + 1).trim() };
    },

    parseClosure(text = '', scope = null) {
        const closure = new SlashCommandClosure(scope);
        closure.rawText = String(text ?? '');
        return closure;
    },

    parseBreak(text = '') {
        return { type: 'break', raw: String(text ?? '') };
    },

    async parse(text = '') {
        const closure = this.parseClosure(text);
        return closure;
    },

    async executeCommand(name, namedArgs = {}, unnamedArgs = '') {
        const cmd = this.getCommand(name);
        if (!cmd || typeof cmd.callback !== 'function') return null;
        const r = await cmd.callback(namedArgs, unnamedArgs);
        return r == null ? '' : String(r);
    },
    addHelpCommand() {},
    getHelpString() { return Object.keys(this.commands || {}).join(', '); },
    registerLanguage() {},
};

export const parser = SlashCommandParser;

try {
    if (typeof window !== 'undefined') {
        window.__rikkaExecuteJsSlashCommand = async function (name, namedArgsJson, unnamedArgs, pipe, seq) {
            const cmd = SlashCommandParser.getCommand(name);
            if (!cmd || typeof cmd.callback !== 'function') {
                if (seq != null && window.RikkaBridge && typeof window.RikkaBridge.onSlashResult === 'function') {
                    try { window.RikkaBridge.onSlashResult(seq, JSON.stringify({ found: false, value: '' })); } catch (_e) { /* noop */ }
                }
                return null;
            }
            let named = {};
            try { named = (typeof namedArgsJson === 'string' && namedArgsJson) ? JSON.parse(namedArgsJson) : (namedArgsJson && typeof namedArgsJson === 'object' ? namedArgsJson : {}); } catch (_e) { named = {}; }
            if (pipe !== undefined && named._pipe === undefined) named._pipe = String(pipe);
            try {
                const r = await cmd.callback(named, String(unnamedArgs == null ? '' : unnamedArgs));
                const val = r == null ? '' : String(r);
                if (seq != null && window.RikkaBridge && typeof window.RikkaBridge.onSlashResult === 'function') {
                    try { window.RikkaBridge.onSlashResult(seq, JSON.stringify({ found: true, value: val })); } catch (_e) { /* noop */ }
                }
                return val;
            } catch (err) {
                const errVal = `(js slash error: ${(err && err.message) || err})`;
                if (seq != null && window.RikkaBridge && typeof window.RikkaBridge.onSlashResult === 'function') {
                    try { window.RikkaBridge.onSlashResult(seq, JSON.stringify({ found: true, value: errVal })); } catch (_e) { /* noop */ }
                }
                return errVal;
            }
        };
    }
} catch (_e) { /* noop */ }
