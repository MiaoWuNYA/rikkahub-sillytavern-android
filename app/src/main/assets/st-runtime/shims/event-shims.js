/**
 * MVU Standalone — 事件系统 Shim
 * 包装 SillyTavern 的 eventSource
 */

// 需要 parseInt 处理的 message 类事件
const MESSAGE_EVENTS = new Set([
    'message_swiped', 'message_sent', 'message_received',
    'message_edited', 'message_updated', 'user_message_rendered',
    'character_message_rendered',
]);

let _eventSource = null;

function getEventSource() {
    if (!_eventSource) {
        const ctx = window.SillyTavern.getContext();
        _eventSource = ctx.eventSource;
    }
    return _eventSource;
}

// listener -> Map<eventType, wrapperFn>
const listenerWrapperMap = new WeakMap();

function getOrCreateWrapperMap(listener) {
    if (!listenerWrapperMap.has(listener)) {
        listenerWrapperMap.set(listener, new Map());
    }
    return listenerWrapperMap.get(listener);
}

// 所有已注册的 wrapper，用于 eventClearAll
const allRegistrations = [];

function createWrapper(eventType, listener) {
    if (MESSAGE_EVENTS.has(eventType)) {
        return function (...args) {
            if (args.length > 0 && typeof args[0] === 'string') {
                args[0] = parseInt(args[0], 10);
            }
            // [FIX H4] 与 JS-Slash-Runner 一致: NaN message_id 时静默丢弃事件
            if (args.length > 0 && typeof args[0] === 'number' && isNaN(args[0])) {
                return;
            }
            return listener.apply(this, args);
        };
    }
    return function (...args) {
        return listener.apply(this, args);
    };
}

/**
 * [v228 S6] per-iframe 事件 API 工厂（v218 起遗留：父 realm 共享闭包 -> 监听表串扰）。
 *
 * ## 为什么必须隔离
 * predefine / 宿主注入把模块级 eventOn 直接塞进每个脚本 iframe 后，全部 iframe 共用
 * 本模块的 listenerWrapperMap / allRegistrations 两张表。真机后果：
 *   1. 任一 iframe 卸载都会跑 window.eventClearAll()（buildScriptIframeHtml 的 pagehide 钩子）
 *      -> 把**其它面板 iframe 的监听全清掉**（多面板卡：一个面板重建=所有面板事件静默失效）；
 *   2. 同 listener 跨窗口复用时 stop()/eventRemoveListener 会删掉别人的 wrapper。
 * 修法：每个子窗口在注入时拿一份**独立实例**（自己的两张表），但底层 eventSource 仍是同一个总线，
 * emit / once / makeLast / 消息类 parseInt 语义逐字不变。
 *
 * @param {boolean} perIframe 标记位（供回归测试断言"这不是父 realm 那份共享实例"）
 */
export function createEventApi(perIframe) {
    // listener -> Map<eventType, wrapperFn>
    const listenerWrapperMap = new WeakMap();
    // 所有已注册的 wrapper，用于 eventClearAll
    const allRegistrations = [];

    function getOrCreateWrapperMap(listener) {
        if (!listenerWrapperMap.has(listener)) {
            listenerWrapperMap.set(listener, new Map());
        }
        return listenerWrapperMap.get(listener);
    }

    function eventRemoveListener(eventType, listener) {
        const es = getEventSource();
        const wrapperMap = listenerWrapperMap.get(listener);
        if (!wrapperMap) return;

        const wrapper = wrapperMap.get(eventType);
        if (!wrapper) return;

        es.removeListener(eventType, wrapper);
        wrapperMap.delete(eventType);

        // 从全局追踪中移除
        const idx = allRegistrations.findIndex(r => r.eventType === eventType && r.listener === listener);
        if (idx >= 0) allRegistrations.splice(idx, 1);
    }

    function eventOn(eventType, listener) {
        const es = getEventSource();
        const wrapperMap = getOrCreateWrapperMap(listener);

        // 防止重复注册
        if (wrapperMap.has(eventType)) {
            return { stop: () => eventRemoveListener(eventType, listener) };
        }

        const wrapper = createWrapper(eventType, listener);
        wrapperMap.set(eventType, wrapper);
        allRegistrations.push({ eventType, listener, wrapper });

        es.on(eventType, wrapper);

        return { stop: () => eventRemoveListener(eventType, listener) };
    }

    function eventOnce(eventType, listener) {
        const es = getEventSource();
        const wrapper = createWrapper(eventType, listener);

        const onceWrapper = function (...args) {
            eventRemoveListener(eventType, listener);
            return wrapper.apply(this, args);
        };

        const wrapperMap = getOrCreateWrapperMap(listener);
        wrapperMap.set(eventType, onceWrapper);
        allRegistrations.push({ eventType, listener, wrapper: onceWrapper });

        es.on(eventType, onceWrapper);

        return { stop: () => eventRemoveListener(eventType, listener) };
    }

    async function eventEmit(eventType, ...data) {
        const es = getEventSource();
        return es.emit(eventType, ...data);
    }

    function eventEmitAndWait(eventType, ...data) {
        const es = getEventSource();
        return es.emitAndWait(eventType, ...data);
    }

    function eventMakeLast(eventType, listener) {
        const es = getEventSource();
        const wrapperMap = getOrCreateWrapperMap(listener);

        let wrapper = wrapperMap.get(eventType);
        if (!wrapper) {
            wrapper = createWrapper(eventType, listener);
            wrapperMap.set(eventType, wrapper);
            allRegistrations.push({ eventType, listener, wrapper });
        }

        es.makeLast(eventType, wrapper);
        return { stop: () => eventRemoveListener(eventType, listener) };
    }

    function eventMakeFirst(eventType, listener) {
        const es = getEventSource();
        const wrapperMap = getOrCreateWrapperMap(listener);

        let wrapper = wrapperMap.get(eventType);
        if (!wrapper) {
            wrapper = createWrapper(eventType, listener);
            wrapperMap.set(eventType, wrapper);
            allRegistrations.push({ eventType, listener, wrapper });
        }

        es.makeFirst(eventType, wrapper);
        return { stop: () => eventRemoveListener(eventType, listener) };
    }

    function eventClearEvent(eventType) {
        const es = getEventSource();
        const toRemove = allRegistrations.filter(r => r.eventType === eventType);
        for (const reg of toRemove) {
            es.removeListener(reg.eventType, reg.wrapper);
            const wm = listenerWrapperMap.get(reg.listener);
            if (wm) wm.delete(eventType);
        }
        // 从追踪中移除
        for (let i = allRegistrations.length - 1; i >= 0; i--) {
            if (allRegistrations[i].eventType === eventType) {
                allRegistrations.splice(i, 1);
            }
        }
    }

    function eventClearListener(listener) {
        const es = getEventSource();
        const wrapperMap = listenerWrapperMap.get(listener);
        if (!wrapperMap) return;

        for (const [eventType, wrapper] of wrapperMap) {
            es.removeListener(eventType, wrapper);
        }
        wrapperMap.clear();

        for (let i = allRegistrations.length - 1; i >= 0; i--) {
            if (allRegistrations[i].listener === listener) {
                allRegistrations.splice(i, 1);
            }
        }
    }

    function eventClearAll() {
        const es = getEventSource();
        for (const reg of [...allRegistrations]) {
            es.removeListener(reg.eventType, reg.wrapper);
            const wm = listenerWrapperMap.get(reg.listener);
            if (wm) wm.delete(reg.eventType);
        }
        allRegistrations.length = 0;
    }

    const api = {
        eventOn, eventOnce, eventEmit, eventEmitAndWait, eventRemoveListener,
        eventMakeLast, eventMakeFirst, eventClearEvent, eventClearListener, eventClearAll,
        __perIframe: !!perIframe,
        // 供回归/诊断读取（只读快照）
        __registrySize: () => allRegistrations.length,
    };
    return api;
}

// ---------------------------------------------------------------------------
// 模块级默认实例：主窗口（父 realm）与 TavernHelper 顶层 API 用的就是它。
// 子窗口在注入时改用 createEventApi(true) 的独立实例（见 runtime.js installPerIframeEventApi）。
// ---------------------------------------------------------------------------
const _defaultApi = createEventApi(false);

export const eventOn = _defaultApi.eventOn;
export const eventOnce = _defaultApi.eventOnce;
export const eventEmit = _defaultApi.eventEmit;
export const eventEmitAndWait = _defaultApi.eventEmitAndWait;
export const eventRemoveListener = _defaultApi.eventRemoveListener;
export const eventMakeLast = _defaultApi.eventMakeLast;
export const eventMakeFirst = _defaultApi.eventMakeFirst;
export const eventClearEvent = _defaultApi.eventClearEvent;
export const eventClearListener = _defaultApi.eventClearListener;
export const eventClearAll = _defaultApi.eventClearAll;
