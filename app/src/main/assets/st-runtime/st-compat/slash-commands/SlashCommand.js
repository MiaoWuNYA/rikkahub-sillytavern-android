/**
 * rikkaST st-compat: slash-commands/SlashCommand.js
 * ============================================================
 * 模拟 ST1.18 `SlashCommand` 类。注册的命令对象存入共享注册表
 * （window.__rikkaStSlashRegistry），供未来与宿主 triggerSlash 集成。
 */

function notifyHostSlashRegistryChanged(reg) {
    try {
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.emitEvent === 'function') {
            const keys = Array.from(reg.keys()).map((k) => String(k).toLowerCase());
            window.RikkaBridge.emitEvent('js_slash_commands_updated', JSON.stringify(keys));
        }
    } catch (_e) { /* noop */ }
}

const registry = (() => {
    const map = new Map();
    const origSet = map.set.bind(map);
    const origDelete = map.delete.bind(map);
    const origClear = map.clear.bind(map);
    map.set = function (k, v) {
        const res = origSet(k, v);
        notifyHostSlashRegistryChanged(map);
        return res;
    };
    map.delete = function (k) {
        const res = origDelete(k);
        notifyHostSlashRegistryChanged(map);
        return res;
    };
    map.clear = function () {
        origClear();
        notifyHostSlashRegistryChanged(map);
    };
    try {
        if (typeof window !== 'undefined') {
            if (window.__rikkaStSlashRegistry instanceof Map) {
                for (const [k, v] of window.__rikkaStSlashRegistry.entries()) {
                    origSet(k, v);
                }
            }
            window.__rikkaStSlashRegistry = map;
            notifyHostSlashRegistryChanged(map);
        }
    } catch (_e) { /* noop */ }
    return map;
})();

export function __getRegistry() {
    return registry;
}

export class SlashCommand {
    constructor(name, callback, ...rest) {
        this.name = name;
        this.callback = callback || (() => '');
        this.extra = rest;
    }

    static fromProps(props) {
        const cmd = new SlashCommand(props && props.name, props && props.callback, props);
        if (props && typeof props === 'object') Object.assign(cmd, props);
        return cmd;
    }

    static namedArgument() {
        return null;
    }
}