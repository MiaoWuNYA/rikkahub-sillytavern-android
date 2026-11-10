/**
 * rikkaST 酒馆运行时（ST / JSR / MVU 宿主）
 * ============================================
 * 设计来源（不造轮子）：
 *  - MVU Standalone (uuuiiiiooo/mvu-sillytavern-extension) 的 shim + blob iframe 模板
 *  - TauriTavern "资源端点原生化" 哲学：不解释扩展代码，只提供资源与 API 表面
 *  - SillyTavern 1.18 原版 events.js / eventemitter.js（事件总线）
 *
 * 数据流：
 *  Kotlin (TavernVariableStore) ← @JavascriptInterface → 本页 state 工作副本
 *  本页 → blob iframe（MVU bundle）通过 window.__mvu_shims__ / SillyTavern 代理
 *  Kotlin → 本页事件：window.__rikkaEmit(name, payloadJson)
 */
import { eventSource, event_types } from './vendor/st/events.js';

// ---------------------------------------------------------------- shims 装载
import * as shimConstants from './shims/constants.js';
import * as shimEvent from './shims/event-shims.js';
import * as shimVariable from './shims/variable-shims.js';
import * as shimChat from './shims/chat-shims.js';
import * as shimLorebook from './shims/lorebook-shims.js';
import * as shimButton from './shims/button-shims.js';
import * as shimUtil from './shims/util-shims.js';
import * as shimInject from './shims/inject-shims.js';
import * as ejsRender from './shims/ejs-render.js';
import * as mathRender from './shims/math-render.js';
import { deepClone } from './shims/clone-util.js';
// [v243 BUGFIX C3] SillyTavern public/script.js 的原生导出面（裸全局挂载用）
import * as stCompatScript from './st-compat/script.js';
import { normalizeStCharacter } from './character-shape.js';
// [v214] ST ctx.powerUserSettings（recon §2.9：ST-PT persona 域）——零依赖模块，直接复用 st-compat 导出。
import { power_user as stCompatPowerUser } from './st-compat/power-user.js';
// [v236 R1] ST `#chat` 楼层 DOM 桩（updateMessageBlock / addCopyToCodeBlocks / appendMediaToMessage）
import * as chatDom from './st-compat/chat-dom.js';
import { MessageFormatter, formatting_stage } from './st-compat/message-formatter.js';
import { accountStorage } from './st-compat/util/AccountStorage.js';
import { ChatCompletionService } from './st-compat/custom-request.js';

// ---------------------------------------------------------------- ESM 库（zod / yaml）
import * as zodModule from './vendor/lib/zod.esm.js';
import * as yamlModule from './vendor/lib/yaml.esm.js';

const LOG_PREFIX = '[RikkaTavern]';

// ============================================================================
// [v229 A1] /api/backends/chat-completions/{status,generate} 兼容路由（JS 侧）
//
// 为什么必须在 JS 侧接：Android WebView 的 shouldInterceptRequest **拿不到 POST body**，
// 而 JSR 的 generate() 走 POST + 请求体（responseGenerator.ts:343/:438）。所以：
//   1) 这里拦 fetch（install 在主模块最早期，早于任何第三方扩展/JSR 装载）；
//   2) 经 RikkaBridge 把请求体交给 Kotlin 宿主生成管线；
//   3) Kotlin 逐块经 __rikkaApiChunk 回推，这里用 ReadableStream 还原成真实 fetch 响应。
// 兼容 JSR 的两条路径：status 只探模型；generate 支持 SSE（含 [DONE]）与 stream:false。
// ============================================================================
(function installChatCompletionsFetchPatch() {
    try {
        if (window.__rikkaApiFetchPatched) return;
        window.__rikkaApiFetchPatched = true;
        const STATUS_PATH = '/api/backends/chat-completions/status';
        const GENERATE_PATH = '/api/backends/chat-completions/generate';
        const originalFetch = window.fetch ? window.fetch.bind(window) : null;
        const pendingNode = {};

        const readBody = async (url, options) => {
            try {
                if (options && typeof options.body === 'string') return options.body;
                if (url && typeof url === 'object' && typeof url.clone === 'function') {
                    return await url.clone().text();
                }
            } catch (_e) { /* noop */ }
            return '';
        };
        const urlOf = (url) => {
            try {
                if (typeof url === 'string') return url;
                if (url && typeof url.url === 'string') return url.url;
            } catch (_e) { /* noop */ }
            return '';
        };
        const pathOf = (u) => {
            try {
                const parsed = new URL(String(u), window.location ? window.location.href : 'https://st.rikka.local/');
                return parsed.pathname;
            } catch (_e) {
                const s = String(u || '');
                const i = s.indexOf('/api/');
                if (i >= 0) return s.slice(i);
                const j = s.indexOf('/user/');
                if (j >= 0) return s.slice(j);
                return s;
            }
        };

        window.__rikkaApiChunk = function (reqId, payload) {
            try {
                const q = window.__rikkaApiPending && window.__rikkaApiPending[reqId];
                if (q) q.push(payload);
                const s = window.__rikkaApiStreams && window.__rikkaApiStreams[reqId];
                if (s && typeof s.wake === 'function') s.wake();
            } catch (_e) { /* noop */ }
        };
        window.__rikkaApiFinish = function (reqId, status) {
            try {
                const s = window.__rikkaApiStreams && window.__rikkaApiStreams[reqId];
                if (!s) return;
                s.finished = true;
                s.status = status || 'DONE';
                delete window.__rikkaApiStreams[reqId];
                if (typeof s.close === 'function') s.close();
            } catch (_e) { /* noop */ }
        };
        window.__rikkaNodeHttpCallback = function (reqId, respJson) {
            const p = pendingNode[reqId];
            if (!p) return;
            delete pendingNode[reqId];
            try {
                const parsed = typeof respJson === 'string' ? JSON.parse(respJson) : respJson;
                p.resolve(parsed);
            } catch (e) {
                p.reject(e);
            }
        };

        const jsonResponse = (payload, status) => new Response(
            typeof payload === 'string' ? payload : JSON.stringify(payload),
            {
                status: status || 200,
                headers: { 'Content-Type': 'application/json' },
            }
        );

        const handleGenerate = async (url, options) => {
            const body = await readBody(url, options);
            let parsedReq = {};
            try { parsedReq = JSON.parse(body || '{}'); } catch (_e) { parsedReq = {}; }
            const isStreaming = Boolean(parsedReq && parsedReq.stream === true);

            const reqId = 'a1-' + Date.now() + '-' + Math.random().toString(36).slice(2);
            window.__rikkaApiPending = window.__rikkaApiPending || {};
            window.__rikkaApiStreams = window.__rikkaApiStreams || {};
            window.__rikkaApiPending[reqId] = [];
            const queue = window.__rikkaApiPending[reqId];

            if (!isStreaming) {
                return await new Promise((resolve) => {
                    window.__rikkaApiStreams[reqId] = {
                        finished: false,
                        wake: function () {},
                        close: function () {
                            const chunks = (window.__rikkaApiPending[reqId] || []).slice();
                            delete window.__rikkaApiPending[reqId];
                            const fullBody = chunks.join('') || JSON.stringify({
                                id: 'chatcmpl-' + reqId,
                                object: 'chat.completion',
                                created: Math.floor(Date.now() / 1000),
                                model: parsedReq.model || '',
                                choices: [{ index: 0, message: { role: 'assistant', content: '' }, finish_reason: 'stop' }],
                            });
                            resolve(jsonResponse(fullBody, 200));
                        },
                    };
                    try {
                        callBridge('chatCompletionStart', reqId, body);
                    } catch (err) {
                        delete window.__rikkaApiPending[reqId];
                        delete window.__rikkaApiStreams[reqId];
                        resolve(jsonResponse({ error: { message: String(err && err.message || err) } }, 500));
                    }
                });
            }

            let wake = null;
            const pump = () => { if (wake) { const w = wake; wake = null; w(); } };
            const streamState = {
                finished: false,
                status: 'DONE',
                wake: pump,
                close: function () { pump(); },
            };
            window.__rikkaApiStreams[reqId] = streamState;
            const encoder = new TextEncoder();
            const stream = new ReadableStream({
                start: function (controller) {
                    (async function () {
                        try {
                            callBridge('chatCompletionStart', reqId, body);
                            while (true) {
                                while (queue.length > 0) {
                                    const payload = queue.shift();
                                    controller.enqueue(encoder.encode('data: ' + payload + '\n\n'));
                                }
                                if (streamState.finished) {
                                    delete window.__rikkaApiPending[reqId];
                                    if (streamState.status === 'DONE') {
                                        controller.enqueue(encoder.encode('data: [DONE]\n\n'));
                                    }
                                    controller.close();
                                    return;
                                }
                                await new Promise(function (res) { wake = res; });
                            }
                        } catch (err) {
                            delete window.__rikkaApiPending[reqId];
                            try { controller.error(err); } catch (_e) { /* noop */ }
                        }
                    })();
                },
                cancel: function () {
                    delete window.__rikkaApiPending[reqId];
                    delete window.__rikkaApiStreams[reqId];
                    try { callBridge('chatCompletionCancel', reqId); } catch (_e) { /* noop */ }
                },
            });
            return new Response(stream, {
                status: 200,
                headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
            });
        };

        const handleStatus = async () => {
            try {
                const res = callBridge('chatCompletionStatus');
                if (typeof res === 'string' && res.length) return jsonResponse(res, 200);
            } catch (e) {
                callBridge('log', 'warn', '[a1] chatCompletionStatus bridge failed: ' + String(e && e.message || e));
            }
            return jsonResponse('{"object":"list","data":[],"model":""}', 200);
        };

        const handleFilesUpload = async (url, options) => {
            try {
                const bodyObj = JSON.parse((await readBody(url, options)) || '{}');
                const name = String(bodyObj.name || '');
                const dataB64 = String(bodyObj.data || '');
                const savedPath = callBridge('writeUserFileBase64', name, dataB64);
                const finalPath = savedPath || ('/user/files/' + name);
                const cleanSavedName = String(finalPath).replace(/^\/?user\/files\//, '');
                window.__rikkaMemFiles = window.__rikkaMemFiles || {};
                window.__rikkaMemFiles[name] = dataB64;
                window.__rikkaMemFiles[cleanSavedName] = dataB64;
                return jsonResponse({ path: finalPath, name: cleanSavedName }, 200);
            } catch (e) {
                return jsonResponse({ error: { message: String(e && e.message || e) } }, 400);
            }
        };

        const handleFilesDelete = async (url, options) => {
            try {
                const bodyObj = JSON.parse((await readBody(url, options)) || '{}');
                const rawPath = String(bodyObj.path || bodyObj.name || '');
                const cleanName = rawPath.replace(/^\/?user\/files\//, '');
                callBridge('deleteUserFile', cleanName);
                if (window.__rikkaMemFiles) delete window.__rikkaMemFiles[cleanName];
                return jsonResponse({ ok: true }, 200);
            } catch (e) {
                return jsonResponse({ error: { message: String(e && e.message || e) } }, 400);
            }
        };

        const handleUserFileGet = async (p) => {
            const cleanName = decodeURIComponent(p.slice('/user/files/'.length));
            try {
                const rawJson = callBridge('readUserFileJson', cleanName);
                if (rawJson) {
                    const info = JSON.parse(rawJson);
                    if (info && info.exists && typeof info.base64 === 'string') {
                        const bin = atob(info.base64);
                        const bytes = new Uint8Array(bin.length);
                        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                        return new Response(bytes, {
                            status: 200,
                            headers: { 'Content-Type': info.mime || 'application/octet-stream', 'Cache-Control': 'no-store' },
                        });
                    }
                    return new Response('Not Found', { status: 404 });
                }
            } catch (_e) { /* fallback to mem */ }
            if (window.__rikkaMemFiles && Object.prototype.hasOwnProperty.call(window.__rikkaMemFiles, cleanName)) {
                const bin = atob(window.__rikkaMemFiles[cleanName]);
                const bytes = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                return new Response(bytes, { status: 200, headers: { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' } });
            }
            return null;
        };

        const handleImagesUpload = async (url, options) => {
            try {
                const bodyObj = JSON.parse((await readBody(url, options)) || '{}');
                const format = String(bodyObj.format || 'png').replace(/[^a-zA-Z0-9]/g, '') || 'png';
                const charFolder = String(bodyObj.ch_name || 'default').replace(/[^a-zA-Z0-9_\-\u4e00-\u9fa5]/g, '_');
                const filename = String(bodyObj.filename || (Date.now() + '.' + format));
                const dataB64 = String(bodyObj.image || bodyObj.data || '').replace(/^data:[^;]+;base64,/, '');
                const savedPath = callBridge('writeUserImageBase64', charFolder, filename, dataB64);
                window.__rikkaMemImages = window.__rikkaMemImages || {};
                const relPath = charFolder + '/' + filename;
                window.__rikkaMemImages[relPath] = dataB64;
                return jsonResponse({ path: savedPath || ('/user/images/' + relPath) }, 200);
            } catch (e) {
                return jsonResponse({ error: { message: String(e && e.message || e) } }, 400);
            }
        };

        const handleImagesList = async (url, options) => {
            try {
                const bodyText = await readBody(url, options);
                const bodyObj = bodyText ? JSON.parse(bodyText) : {};
                const folder = String(bodyObj.folder || bodyObj.ch_name || 'default');
                const listJson = callBridge('listUserImagesJson', folder);
                if (typeof listJson === 'string' && listJson.length > 0) {
                    return jsonResponse(listJson, 200);
                }
                return jsonResponse('[]', 200);
            } catch (_e) {
                return jsonResponse('[]', 200);
            }
        };

        const handleImagesDelete = async (url, options) => {
            try {
                const bodyObj = JSON.parse((await readBody(url, options)) || '{}');
                const relPath = String(bodyObj.path || '').replace(/^\/?user\/images\//, '');
                callBridge('deleteUserImage', relPath);
                if (window.__rikkaMemImages) delete window.__rikkaMemImages[relPath];
                return jsonResponse({ ok: true }, 200);
            } catch (e) {
                return jsonResponse({ error: { message: String(e && e.message || e) } }, 400);
            }
        };

        const handleNodePluginRequest = async (url, options, p) => {
            if (!bridge || typeof bridge.nodePluginHttpRequestAsync !== 'function') {
                return jsonResponse({ ok: false, error: 'Node.js runtime bridge unavailable' }, 503);
            }
            const method = String((options && options.method) || (url && url.method) || 'GET').toUpperCase();
            const bodyStr = (method === 'GET' || method === 'HEAD') ? '' : await readBody(url, options);
            let headersObj = {};
            const rawHeaders = (options && options.headers) || (url && url.headers);
            if (rawHeaders) {
                if (typeof rawHeaders.forEach === 'function') {
                    rawHeaders.forEach((v, k) => { headersObj[k] = v; });
                } else if (typeof rawHeaders === 'object') {
                    headersObj = Object.assign({}, rawHeaders);
                }
            }
            let search = '';
            try {
                search = new URL(urlOf(url), window.location ? window.location.href : 'https://st.rikka.local/').search || '';
            } catch (_e) { search = ''; }
            const reqId = 'node-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9);
            return await new Promise((resolve, reject) => {
                pendingNode[reqId] = {
                    resolve: (resp) => {
                        const status = Number(resp && resp.status) || 502;
                        const respHeaders = Object.assign({ 'Content-Type': 'application/json' }, (resp && resp.headers) || {});
                        const bodyPayload = resp && typeof resp.body === 'string' ? resp.body : JSON.stringify((resp && resp.body) || {});
                        resolve(new Response(bodyPayload, { status, headers: respHeaders }));
                    },
                    reject,
                };
                if (options && options.signal) {
                    options.signal.addEventListener('abort', () => {
                        delete pendingNode[reqId];
                        try { callBridge('nodePluginHttpAbort', reqId); } catch (_e) { /* noop */ }
                        reject(new DOMException('Aborted', 'AbortError'));
                    });
                }
                try {
                    callBridge('nodePluginHttpRequestAsync', reqId, method, p + search, JSON.stringify(headersObj), bodyStr);
                } catch (e) {
                    delete pendingNode[reqId];
                    resolve(jsonResponse({ ok: false, error: String(e && e.message || e) }, 503));
                }
            });
        };

        const pendingProxy = (window.__rikkaProxyHttpPending = window.__rikkaProxyHttpPending || {});
        window.__rikkaProxyHttpCallback = function (reqId, respJson) {
            const entry = pendingProxy[reqId];
            if (!entry) return;
            delete pendingProxy[reqId];
            try {
                const parsed = typeof respJson === 'string' ? JSON.parse(respJson) : respJson;
                entry.resolve(parsed);
            } catch (e) {
                entry.reject(e);
            }
        };

        /* __RIKKA_PROXY_SECURITY_START__ */
        function isForbiddenIpv4Parts(parts) { return isBlockedIpv4Parts(parts); }
        function isBlockedIpv4Parts(parts) {
            if (!Array.isArray(parts) || parts.length !== 4) return true;
            for (let i = 0; i < 4; i++) {
                const n = parts[i];
                if (!Number.isInteger(n) || n < 0 || n > 255) return true;
            }
            const [a, b, c] = parts;
            if (a === 0) return true; // 0.0.0.0/8
            if (a === 10) return true; // 10.0.0.0/8 RFC1918
            if (a === 100 && b >= 64 && b <= 127) return true; // 100.64.0.0/10 CGNAT
            if (a === 127) return true; // 127.0.0.0/8 loopback
            if (a === 169 && b === 254) return true; // 169.254.0.0/16 link-local & cloud metadata
            if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12 RFC1918
            if (a === 192 && b === 0 && (c === 0 || c === 2)) return true; // 192.0.0.0/24, 192.0.2.0/24
            if (a === 192 && b === 168) return true; // 192.168.0.0/16 RFC1918
            if (a === 198 && (b === 18 || b === 19)) return true; // 198.18.0.0/15 benchmark
            if (a === 198 && b === 51 && c === 100) return true; // 198.51.100.0/24 TEST-NET-2
            if (a === 203 && b === 0 && c === 113) return true; // 203.0.113.0/24 TEST-NET-3
            if (a >= 224) return true; // multicast / reserved / broadcast
            return false;
        }

        function isBlockedProxyHost(rawHost) {
            const host = String(rawHost || '').trim().toLowerCase().replace(/\.+$/, '');
            if (!host) return { blocked: true, reason: 'Empty proxy target host' };
            const unbracketed = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
            if (
                unbracketed === 'localhost' ||
                unbracketed.endsWith('.localhost') ||
                unbracketed === 'local' ||
                unbracketed.endsWith('.local') ||
                unbracketed.endsWith('.internal') ||
                unbracketed === 'metadata' ||
                unbracketed === 'metadata.google.internal' ||
                unbracketed === 'instance-data'
            ) {
                return { blocked: true, reason: 'Loopback, local, or metadata hostname is forbidden' };
            }
            // Reject integer, octal, hex, or shorthand IPv4 encodings (e.g., 2130706433, 0177.0.0.1, 0x7f.0.0.1, 127.1)
            if (/^(?:0x[0-9a-f]+|\d+)(?:\.(?:0x[0-9a-f]+|\d+))*$/i.test(unbracketed)) {
                const segs = unbracketed.split('.');
                if (segs.length !== 4 || segs.some((s) => /^0\d+$/.test(s) || /^0x/i.test(s))) {
                    return { blocked: true, reason: 'Non-standard IPv4 literal encoding is forbidden' };
                }
                const nums = segs.map((s) => Number(s));
                if (isBlockedIpv4Parts(nums)) {
                    return { blocked: true, reason: 'Private, loopback, or reserved IPv4 range is forbidden' };
                }
                return { blocked: false };
            }
            // IPv6 literal checks
            if (unbracketed.includes(':')) {
                const normV6 = unbracketed.split('%')[0].toLowerCase();
                if (normV6 === '::' || normV6 === '::1' || /^(?:0+:){7}0*1$/.test(normV6) || /^(?:0+:){7}0+$/.test(normV6)) {
                    return { blocked: true, reason: 'IPv6 loopback or unspecified address is forbidden' };
                }
                if (/^fe[89ab][0-9a-f]:/.test(normV6)) {
                    return { blocked: true, reason: 'IPv6 link-local address (fe80::/10) is forbidden' };
                }
                if (/^f[cd][0-9a-f]{2}:/.test(normV6)) {
                    return { blocked: true, reason: 'IPv6 unique-local address (fc00::/7) is forbidden' };
                }
                if (/^ff[0-9a-f]{2}:/.test(normV6)) {
                    return { blocked: true, reason: 'IPv6 multicast address is forbidden' };
                }
                // Check IPv4-mapped or IPv4-compatible IPv6 (e.g. ::ffff:127.0.0.1 or ::ffff:7f00:1 or ::ffff:a00:1)
                const v4DotMatch = normV6.match(/(\d+\.\d+\.\d+\.\d+)$/);
                if (v4DotMatch) {
                    const nums = v4DotMatch[1].split('.').map((s) => Number(s));
                    if (isBlockedIpv4Parts(nums)) {
                        return { blocked: true, reason: 'IPv4-mapped private or loopback IPv6 address is forbidden' };
                    }
                }
                const v4HexMatch = normV6.match(/(?:::ffff:|64:ff9b::)([0-9a-f]{1,4}):([0-9a-f]{1,4})$/);
                if (v4HexMatch) {
                    const hi = parseInt(v4HexMatch[1], 16);
                    const lo = parseInt(v4HexMatch[2], 16);
                    const nums = [(hi >> 8) & 0xff, hi & 0xff, (lo >> 8) & 0xff, lo & 0xff];
                    if (isBlockedIpv4Parts(nums)) {
                        return { blocked: true, reason: 'Hex IPv4-mapped private or loopback IPv6 address is forbidden' };
                    }
                }
            }
            return { blocked: false };
        }

        function validateProxyTargetUrl(rawTargetUrl) {
            const text = String(rawTargetUrl || '').trim();
            if (!text) return { ok: false, reason: 'Empty proxy target URL' };
            let parsed;
            try {
                parsed = new URL(text);
            } catch (_e) {
                return { ok: false, reason: 'Malformed proxy target URL: ' + text };
            }
            const proto = String(parsed.protocol || '').toLowerCase();
            if (proto !== 'http:' && proto !== 'https:') {
                return { ok: false, reason: 'Disallowed proxy URL protocol: ' + proto };
            }
            if (parsed.username || parsed.password) {
                return { ok: false, reason: 'Embedded credentials in proxy URL are forbidden' };
            }
            const hostCheck = isBlockedProxyHost(parsed.hostname);
            if (hostCheck.blocked) {
                return { ok: false, reason: hostCheck.reason };
            }
            return { ok: true, status: 200, url: parsed.toString(), normalizedUrl: parsed.toString(), origin: parsed.origin };
        }
        /* __RIKKA_PROXY_SECURITY_END__ */
        window.__rikkaValidateProxyTargetUrl = validateProxyTargetUrl;

        const handleCorsProxyFetch = async (rawUrl, url, options, p) => {
            let targetUrl = '';
            try {
                const idx = rawUrl.indexOf('/proxy/');
                const after = idx >= 0 ? rawUrl.slice(idx + 7) : p.slice(7);
                targetUrl = /^https?:%2F%2F/i.test(after) ? decodeURIComponent(after) : after;
            } catch (_e) {
                targetUrl = p.slice(7);
            }
            const targetCheck = validateProxyTargetUrl(targetUrl);
            if (!targetCheck.ok) {
                return jsonResponse({ error: { code: 'PROXY_TARGET_FORBIDDEN', message: targetCheck.reason } }, 403);
            }
            targetUrl = targetCheck.url;
            if (!bridge || typeof bridge.proxyHttpRequestAsync !== 'function') {
                return originalFetch ? originalFetch(targetUrl, options) : jsonResponse({ error: { message: 'CORS proxy bridge unavailable' } }, 502);
            }
            const method = String((options && options.method) || (url && url.method) || 'GET').toUpperCase();
            const bodyStr = (method === 'GET' || method === 'HEAD') ? '' : await readBody(url, options);
            let headersObj = {};
            const rawHeaders = (options && options.headers) || (url && url.headers);
            if (rawHeaders) {
                if (typeof rawHeaders.forEach === 'function') {
                    rawHeaders.forEach((v, k) => { headersObj[k] = v; });
                } else if (typeof rawHeaders === 'object') {
                    headersObj = Object.assign({}, rawHeaders);
                }
            }
            const reqId = 'proxy-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9);
            return await new Promise((resolve, reject) => {
                if (options && options.signal && options.signal.aborted) {
                    reject(new DOMException('Aborted', 'AbortError'));
                    return;
                }
                pendingProxy[reqId] = {
                    resolve: (resp) => {
                        if (!resp || resp.aborted) {
                            reject(new DOMException('Aborted', 'AbortError'));
                            return;
                        }
                        if (resp.networkError) {
                            reject(new TypeError(String(resp.error || 'Network request failed')));
                            return;
                        }
                        const status = Number(resp.status) || 502;
                        const respHeaders = Object.assign({}, resp.headers || {});
                        if (typeof resp.bodyBase64 === 'string') {
                            const bin = atob(resp.bodyBase64);
                            const bytes = new Uint8Array(bin.length);
                            for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                            resolve(new Response(bytes, { status, headers: respHeaders }));
                        } else {
                            const textBody = typeof resp.body === 'string' ? resp.body : JSON.stringify(resp.body || {});
                            resolve(new Response(textBody, { status, headers: respHeaders }));
                        }
                    },
                    reject,
                };
                if (options && options.signal) {
                    options.signal.addEventListener('abort', () => {
                        delete pendingProxy[reqId];
                        try { callBridge('proxyHttpAbort', reqId); } catch (_e) { /* noop */ }
                        reject(new DOMException('Aborted', 'AbortError'));
                    }, { once: true });
                }
                try {
                    callBridge('proxyHttpRequestAsync', reqId, method, targetUrl, JSON.stringify(headersObj), bodyStr);
                } catch (e) {
                    delete pendingProxy[reqId];
                    reject(new TypeError(String(e && e.message || e)));
                }
            });
        };

        const pendingImgGen = (window.__rikkaImgGenPending = window.__rikkaImgGenPending || {});
        window.__rikkaImgGenCallback = function (reqId, respJson) {
            const entry = pendingImgGen[reqId];
            if (!entry) return;
            delete pendingImgGen[reqId];
            try {
                const parsed = typeof respJson === 'string' ? JSON.parse(respJson) : respJson;
                entry.resolve(parsed);
            } catch (e) {
                entry.reject(e);
            }
        };

        const handleNativeImageModels = async () => {
            try {
                const raw = callBridge('getImageGenerationStatusJson');
                if (raw) {
                    const info = JSON.parse(raw);
                    const models = Array.isArray(info && info.models) ? info.models : [];
                    return jsonResponse({
                        object: 'list',
                        data: models.map((id) => ({ id: String(id), object: 'model' })),
                    }, 200);
                }
            } catch (_e) { /* noop */ }
            return jsonResponse({ object: 'list', data: [{ id: 'gpt-image-1', object: 'model' }] }, 200);
        };

        const handleNativeImageGeneration = async (url, options) => {
            if (!bridge || typeof bridge.generateImageFromJsAsync !== 'function') {
                return jsonResponse({ error: { message: 'RikkaHub native image generation bridge is not wired' } }, 503);
            }
            const bodyStr = await readBody(url, options);
            const reqId = 'imggen-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9);
            return await new Promise((resolve, reject) => {
                if (options && options.signal && options.signal.aborted) {
                    reject(new DOMException('Aborted', 'AbortError'));
                    return;
                }
                pendingImgGen[reqId] = {
                    resolve: (resp) => {
                        if (!resp || resp.aborted) {
                            reject(new DOMException('Aborted', 'AbortError'));
                            return;
                        }
                        const status = Number(resp.status) || (resp.ok ? 200 : 500);
                        const body = resp.body || (resp.b64_json
                            ? { data: [{ b64_json: resp.b64_json }], output_format: resp.format || 'png' }
                            : { error: { message: String(resp.error || 'Image generation failed') } });
                        resolve(jsonResponse(body, status));
                    },
                    reject,
                };
                if (options && options.signal) {
                    options.signal.addEventListener('abort', () => {
                        delete pendingImgGen[reqId];
                        try { callBridge('cancelImageGenerationFromJs', reqId); } catch (_e) { /* noop */ }
                        reject(new DOMException('Aborted', 'AbortError'));
                    }, { once: true });
                }
                try {
                    callBridge('generateImageFromJsAsync', reqId, bodyStr || '{}');
                } catch (e) {
                    delete pendingImgGen[reqId];
                    resolve(jsonResponse({ error: { message: String(e && e.message || e) } }, 500));
                }
            });
        };

        const handleComfyProxyFetch = async (_rawUrl, _url, _options, p) => {
            const subRoute = p.slice('/api/sd/comfy/'.length);
            return jsonResponse({
                error: {
                    code: 'UNSUPPORTED_DESKTOP_COMFY_EXTENSION',
                    subRoute,
                    message: '桌面端 SillyTavern ComfyUI 服务端扩展接口 (/api/sd/comfy/*) 不在移动端 WebView 运行时内托管；请切换为 OpenAI 兼容生图 (/v1/images/generations)、NovelAI 或 RikkaHub 原生生图桥。',
                },
            }, 501);
        };

        // [10-19-21] 补齐 jQuery UI easing 函数（st-memory-enhancement appHeaderTableBaseDrawer.js 强依赖 easeInOutCubic）
        try {
            const jq = window.jQuery || window.$;
            if (jq && jq.easing) {
                if (typeof jq.easing.easeInOutCubic !== 'function') {
                    jq.easing.easeInOutCubic = function (x) {
                        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
                    };
                }
                if (typeof jq.easing.easeOutCubic !== 'function') {
                    jq.easing.easeOutCubic = function (x) { return 1 - Math.pow(1 - x, 3); };
                }
                if (typeof jq.easing.easeInCubic !== 'function') {
                    jq.easing.easeInCubic = function (x) { return x * x * x; };
                }
                if (typeof jq.easing.easeInOutQuad !== 'function') {
                    jq.easing.easeInOutQuad = function (x) {
                        return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
                    };
                }
            }
        } catch (_eJq) { /* noop */ }

        // [10-19-21] 追踪扩展设置页打开的 IndexedDB 连接，退出设置页时主动 close()，杜绝第二次进入 ST-iPhonie 时因未关闭连接锁死白屏
        try {
            if (window.indexedDB && typeof window.indexedDB.open === 'function' && !window.indexedDB.__rikkaWrapped) {
                const origIdbOpen = window.indexedDB.open.bind(window.indexedDB);
                const openedDbs = (window.__rikkaOpenedIdbDatabases = window.__rikkaOpenedIdbDatabases || new Set());
                window.indexedDB.open = function (name, version) {
                    const req = version !== undefined ? origIdbOpen(name, version) : origIdbOpen(name);
                    try {
                        req.addEventListener('success', function () {
                            const db = req.result;
                            if (db) {
                                openedDbs.add(db);
                                db.addEventListener('versionchange', function () {
                                    try { db.close(); } catch (_e) { /* noop */ }
                                    openedDbs.delete(db);
                                });
                                db.addEventListener('close', function () {
                                    openedDbs.delete(db);
                                });
                            }
                        });
                    } catch (_eIdb) { /* noop */ }
                    return req;
                };
                window.indexedDB.__rikkaWrapped = true;
            }
        } catch (_eIdbWrap) { /* noop */ }

        window.fetch = function (url, options) {
            try {
                const rawUrl = urlOf(url);
                if (rawUrl && /api\.muyoo\.com\.cn/i.test(rawUrl)) {
                    return Promise.resolve(jsonResponse({ success: true, isLatest: true }, 200));
                }
                if (rawUrl && /fonts\.googleapis\.com|fonts\.gstatic\.com|use\.fontawesome\.com|kit\.fontawesome\.com/i.test(rawUrl)) {
                    return Promise.resolve(new Response('/* offline font shim */', {
                        status: 200,
                        headers: { 'Content-Type': 'text/css; charset=utf-8' },
                    }));
                }
                const p = pathOf(rawUrl);
                const method = String((options && options.method) || (url && url.method) || 'GET').toUpperCase();
                if (p.indexOf(STATUS_PATH) === 0) return handleStatus();
                if (p.indexOf(GENERATE_PATH) === 0) return handleGenerate(url, options);
                if (p === '/api/files/upload' && method === 'POST') return handleFilesUpload(url, options);
                if (p === '/api/files/delete' && method === 'POST') return handleFilesDelete(url, options);
                if (p.indexOf('/user/files/') === 0 && method === 'GET') {
                    return handleUserFileGet(p).then((res) => res || (originalFetch ? originalFetch(url, options) : new Response('Not Found', { status: 404 })));
                }
                if (p === '/api/images/upload' && method === 'POST') return handleImagesUpload(url, options);
                if (p === '/api/images/list') return handleImagesList(url, options);
                if (p === '/api/images/delete' && method === 'POST') return handleImagesDelete(url, options);
                if (p.indexOf('/proxy/') === 0) return handleCorsProxyFetch(rawUrl, url, options, p);
                if ((p === '/api/rikkahub/v1/images/generations' || p === '/api/images/generations' || p === '/sdapi/v1/txt2img') && method === 'POST') {
                    return handleNativeImageGeneration(url, options);
                }
                if (p === '/api/rikkahub/v1/models') return handleNativeImageModels();
                if (p.indexOf('/api/sd/comfy/') === 0) return handleComfyProxyFetch(rawUrl, url, options, p);
                if (p.indexOf('/api/plugins/') === 0 || p.indexOf('/api/host/') === 0 || p === '/health') return handleNodePluginRequest(url, options, p);
            } catch (e) {
                callBridge('log', 'warn', '[a1] fetch patch error: ' + String(e && e.message || e));
            }
            return originalFetch ? originalFetch(url, options) : Promise.reject(new Error('fetch unavailable'));
        };
        console.log(LOG_PREFIX + ' [a1] chat-completions fetch patch installed');
    } catch (__a1e) {
        try { callBridge('log', 'error', '[a1] fetch patch install failed: ' + String(__a1e && __a1e.message || __a1e)); } catch (_e) { /* noop */ }
    }
})();
// [v207] 主页面也要有全局错误闸门。以前 error / unhandledrejection 只在 panel 模式与 script iframe 里转发，
// main 模式是静默的 —— 「扩展抛错但不影响加载」这类问题完全看不见（B 项被误判两版就是这么来的：
// `#send_form` 缺失导致的 MutationObserver 报错，只在用户看得见的 toast 里露过一次）。
(function installGlobalErrorForwarder() {
    try {
        if (window.__rikkaErrFwd) return;
        window.__rikkaErrFwd = true;
        window.addEventListener('error', (e) => {
            try {
                const msg = (e && (e.message || (e.error && e.error.stack))) || e;
                callBridge('log', 'error', `[global] error: ${String(msg)}\n  at ${String((e && e.filename) || '')}:${String((e && e.lineno) || '')}`);
            } catch (_e) { /* noop */ }
        }, true);
        window.addEventListener('unhandledrejection', (e) => {
            try {
                const r = e && e.reason;
                callBridge('log', 'error', `[global] unhandledrejection: ${String((r && (r.stack || r.message)) || r)}`);
            } catch (_e) { /* noop */ }
        });
    } catch (_e) { /* noop */ }
})();

// ---------------------------------------------------------------- 桥接（JS → Kotlin）
const bridge = (typeof window.RikkaBridge !== 'undefined') ? window.RikkaBridge : null;

function callBridge(name, ...args) {
    if (!bridge || typeof bridge[name] !== 'function') return undefined;
    try {
        return bridge[name](...args);
    } catch (e) {
        // [诊断] 把实参个数一起打出来：Android @JavascriptInterface 按「方法名 + 实参个数」解析，
        // 名字对但个数不对时抛的是 "Method not found"（极易被误判成「桥没注入 / R8 删了方法」）。
        // 真机取证（2026-10-28）：getMessageVariablesJson 被传了 1 个实参而 Kotlin 侧是 0 形参，
        // 于是面板启动时变量同步静默失败、面板空白；改成 0 实参后恢复。日志里带上 argc 可一眼定位。
        safeWarn(`bridge.${name} failed (argc=${args.length})`, e);
        return undefined;
    }
}

function safeWarn(...args) {
    try { console.warn(LOG_PREFIX, ...args); } catch (_e) { /* noop */ }
}

// ---------------------------------------------------------------- 面板命令总线（问题 2）
// 所有 panel → host 的动作都带 requestId，Kotlin 处理完广播 __rikkaPanelCommandResult。
// 超时/失败必须 reject 到调用方，禁止“按钮只变色、没有动作”。
const panelPendingCommands = new Map();
let panelCommandSeq = 0;

function panelCommand(kind, fields, timeoutMs) {
    const timeout = Math.max(1000, Number(timeoutMs) || 5000);
    return new Promise((resolve) => {
        const requestId = 'panel-' + Date.now().toString(36) + '-' + (++panelCommandSeq);
        let settled = false;
        const finish = (ok, result, error) => {
            if (settled) return;
            settled = true;
            try { clearTimeout(timer); } catch (_e) { /* noop */ }
            panelPendingCommands.delete(requestId);
            const payload = { ok: !!ok, result: result == null ? '' : String(result), error: error || '' };
            try { if (window.RikkaPanel && RikkaPanel.log) RikkaPanel.log('debug', '[panel-cmd] ' + kind + ' ok=' + payload.ok + (payload.error ? ' err=' + payload.error : '')); } catch (_e) { /* noop */ }
            resolve(payload);
        };
        const timer = setTimeout(() => finish(false, '', '宿主未响应（超时 ' + timeout + 'ms）'), timeout);
        panelPendingCommands.set(requestId, finish);
        let accepted = false;
        try {
            const payload = Object.assign({ requestId: requestId, kind: kind }, fields || {});
            accepted = callBridge('panelCommand', JSON.stringify(payload));
        } catch (e) {
            finish(false, '', '桥接调用失败: ' + String((e && e.message) || e));
            return;
        }
        if (accepted !== true) {
            finish(false, '', '宿主未接线（不在聊天页或桥不可用）');
        }
    });
}

window.__rikkaPanelCommandResult = function (requestId, json) {
    try {
        const finish = panelPendingCommands.get(String(requestId || ''));
        if (!finish) return;
        const obj = typeof json === 'string' ? JSON.parse(json) : (json || {});
        finish(obj.ok === true, obj.result || '', obj.error || '');
    } catch (e) {
        safeWarn('panel command ack parse failed', e);
    }
};

window.__rikkaPanelPing = function () {
    return panelCommand('ping', {}, 3000);
};

window.__rikkaPanelBridgeSelfTest = function () {
    try {
        const th = window.TavernHelper || {};
        const form = document.getElementById('send_form');
        const button = document.getElementById('send_but');
        return JSON.stringify({
            sendMessage: typeof th.sendMessage === 'function',
            sendMessageAsUser: typeof th.sendMessageAsUser === 'function',
            sendMessageAsAssistant: typeof th.sendMessageAsAssistant === 'function',
            triggerSlashAsync: typeof th.triggerSlashAsync === 'function',
            emitHostEvent: typeof th.emitHostEvent === 'function',
            saveChat: typeof th.saveChat === 'function',
            stopGeneration: typeof th.stopGeneration === 'function',
            formSubmit: window.__RIKKA_PANEL_MODE__ ? !!(form && (form.tagName === 'FORM' || typeof form.submit === 'function')) : true,
            sendButton: window.__RIKKA_PANEL_MODE__ ? !!button : true,
            bridgePanelCommand: !!(window.RikkaBridge && typeof window.RikkaBridge.panelCommand === 'function'),
        });
    } catch (e) {
        return JSON.stringify({ error: String((e && e.message) || e) });
    }
};

// ---------------------------------------------------------------- 控制台转发（供调试面板/日志）
(function patchConsole() {
    const MAX = 600;
    // [T5.1] Error 用 JSON.stringify 会得到 "{}" —— 真机日志里成排的 [error] {} 就是这么来的，
    // 唯一有价值的报错信息（message/stack）被丢掉。这里与脚本 iframe 的 send()（见本文件
    // "console → 宿主日志（JSR log.js 语义）" 一段）保持一致：Error / 带 stack 的对象优先取 stack。
    const cut = (v) => {
        let s;
        try {
            if (typeof v === 'string') s = v;
            else if (v instanceof Error) s = v.stack || (v.name + ': ' + v.message);
            else if (v && typeof v === 'object' && typeof v.stack === 'string') {
                s = (v.name ? v.name + ': ' : '') + (v.message || '') + '\n' + v.stack;
            } else s = JSON.stringify(v);
        } catch (_e) { s = String(v); }
        if (s === undefined) s = String(v);
        return s.length > MAX ? s.slice(0, MAX) + '…' : s;
    };
    for (const level of ['log', 'info', 'warn', 'error']) {
        const orig = console[level] ? console[level].bind(console) : () => {};
        console[level] = (...args) => {
            orig(...args);
            try { callBridge('log', level, args.map(cut).join(' ')); } catch (_e) { /* noop */ }
        };
    }
})();

// ---------------------------------------------------------------- 状态（工作副本）
const state = {
    chat: [],              // [{ name, is_user, is_system, mes, swipe_id, variables: [ {} ] }]
    chatMetadata: { variables: {} },
    extensionSettings: { variables: { global: {} } }, // 设置根（variables.global 落点）
    thirdPartySettings: {},                            // ST extension_settings 语义（按扩展名分键）
    characters: [],
    characterId: 0,
    chatId: '',
    // [v208] ST `world_names`（世界书名列表）。此前恒为空 → ST-PT worldinfo.ts:660
    //   `results.filter(e => e && world_names.includes(e))` 把所有书过滤光（WI 功能静默全灭），
    //   JSR setLorebookSettings 对非空书单 throw Error。数据源 = 宿主 getWorldNamesJson。
    worldNames: [],
    // [v208] ST `world_info` 对象（ST 65：{ globalSelect: string[], charLore: [...] }）。
    worldInfo: { globalSelect: [], charLore: [] },
    // [v208] ST `selected_world_info`：全局启用的世界书（charLore 附加书不在此列）。
    selectedWorldInfo: [],
    name1: 'User',
    name2: 'AI',
    modelName: '',
};

const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

// 聊天内容指纹（diff 出被 JS 侧修改过的楼层；JSR setChatMessages / MVU saveChat 写路径）
let chatFingerprintParts = [];
let lastPullChatAt = 0;

function invalidateChatCache() {
    lastPullChatAt = 0;
}

function pullChat(force) {
    const now = Date.now();
    // [P0-2 内存风暴修复] 面板 varsWait 每 400ms 调一次 readMvuVars() -> pullChat()。
    // 若距离上次全量拉取不足 1500ms 且非强制刷新，跳过 218KB 全量消息文本序列化（getChatJson），
    // 仅拉取轻量级的 getMessageVariablesJson 原地更新变量，既保证变量 0 延迟可见，又消除 V8 堆内存风暴。
    let chat = null;
    if (!force && Array.isArray(state.chat) && state.chat.length > 0 && (now - lastPullChatAt) < 1500) {
        chat = state.chat;
    } else {
        let raw = null;
        try { raw = callBridge('getChatJson'); } catch (_e) { /* noop */ }
        try { chat = raw ? JSON.parse(raw) : []; } catch (_e) { chat = []; }
        if (!Array.isArray(chat)) chat = [];
        lastPullChatAt = now;
    }

    let mv = null;
    try { mv = JSON.parse(callBridge('getMessageVariablesJson') || '[]'); } catch (_e) { mv = null; }

    chat.forEach((m, i) => {
        if (!m || typeof m !== 'object') return;
        let vs = Array.isArray(mv) ? mv[i] : null;
        if (!Array.isArray(vs)) vs = Array.isArray(m.variables) ? m.variables : [];
        if (vs.length === 0) vs = [{}];
        m.variables = vs;
        if (typeof m.swipe_id !== 'number') m.swipe_id = 0;
        // ⚠️ swipes 必须是【字符串】数组：MVU 切换聊天后重新初始化会对 swipe 内容调用 matchAll。
        // 此前用 {} 填充导致 "n.matchAll is not a function"（红色报错真凶），严禁回退为对象占位。
        const mesText = String(m.mes == null ? '' : m.mes);
        if (!Array.isArray(m.swipes) || m.swipes.length === 0) {
            m.swipes = new Array(Math.max(1, vs.length)).fill(mesText);
        } else {
            m.swipes = m.swipes.map((s) => (typeof s === 'string' ? s : mesText));
        }
    });
    state.chat = chat;
    rebuildChatFingerprint();
}

function pullMetadata() {
    let obj = null;
    try { obj = JSON.parse(callBridge('getChatMetadataJson') || '{}'); } catch (_e) { obj = null; }
    if (!obj || typeof obj !== 'object') obj = {};
    if (!obj.variables || typeof obj.variables !== 'object' || Array.isArray(obj.variables)) obj.variables = {};
    state.chatMetadata = obj;
}

function pullSettings() {
    let obj = null;
    try { obj = JSON.parse(callBridge('getSettingsJson') || '{}'); } catch (_e) { obj = null; }
    if (!obj || typeof obj !== 'object') obj = {};
    if (!obj.variables || typeof obj.variables !== 'object' || Array.isArray(obj.variables)) obj.variables = {};
    if (!obj.variables.global || typeof obj.variables.global !== 'object' || Array.isArray(obj.variables.global)) obj.variables.global = {};
    state.extensionSettings = obj;
    if (!state.thirdPartySettings || typeof state.thirdPartySettings !== 'object' || Array.isArray(state.thirdPartySettings)) {
        state.thirdPartySettings = {};
    }
    // 绑定 state.thirdPartySettings.variables 与 state.extensionSettings.variables 为同一引用，消除全局变量双轨分裂
    state.thirdPartySettings.variables = state.extensionSettings.variables;
}

// 第三方扩展设置（extension_settings 语义：按扩展名分键）。补充 ST 内置键的安全默认，
// 避免扩展直接访问 extension_settings.note / .regex / .disabledExtensions 时崩溃。
let thirdPartySettingsDirty = false;

/**
 * [v215] ST 契约形状保证：`extension_settings.variables.global` 必须存在，
 * 且 `state.thirdPartySettings.variables` 与 `state.extensionSettings.variables` 始终指向同一容器对象。
 */
function ensureStStateShapes() {
    try {
        if (!state.extensionSettings || typeof state.extensionSettings !== 'object' || Array.isArray(state.extensionSettings)) {
            state.extensionSettings = { variables: { global: {} } };
        }
        if (!state.extensionSettings.variables || typeof state.extensionSettings.variables !== 'object' || Array.isArray(state.extensionSettings.variables)) {
            state.extensionSettings.variables = { global: {} };
        }
        if (!state.extensionSettings.variables.global || typeof state.extensionSettings.variables.global !== 'object' || Array.isArray(state.extensionSettings.variables.global)) {
            state.extensionSettings.variables.global = {};
        }
        if (!state.thirdPartySettings || typeof state.thirdPartySettings !== 'object' || Array.isArray(state.thirdPartySettings)) {
            state.thirdPartySettings = {};
        }
        const es = state.thirdPartySettings;
        if (es.variables && es.variables !== state.extensionSettings.variables && typeof es.variables === 'object' && !Array.isArray(es.variables)) {
            if (es.variables.global && typeof es.variables.global === 'object' && !Array.isArray(es.variables.global)) {
                state.extensionSettings.variables.global = es.variables.global;
            }
        }
        es.variables = state.extensionSettings.variables;
        if (!state.chatMetadata || typeof state.chatMetadata !== 'object' || Array.isArray(state.chatMetadata)) state.chatMetadata = { variables: {} };
        if (!state.chatMetadata.variables || typeof state.chatMetadata.variables !== 'object' || Array.isArray(state.chatMetadata.variables)) {
            state.chatMetadata.variables = {};
        }
    } catch (_e) { /* noop */ }
}

function pullThirdPartySettings(force) {
    if (!force && thirdPartySettingsDirty) return;
    let obj = null;
    try { obj = JSON.parse(callBridge('getThirdPartySettingsJson') || '{}'); } catch (_e) { obj = null; }
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) obj = {};
    if (!Array.isArray(obj.disabledExtensions)) obj.disabledExtensions = [];
    if (!Array.isArray(obj.regex)) obj.regex = [];
    if (!Array.isArray(obj.regex_presets)) obj.regex_presets = [];
    if (!Array.isArray(obj.character_allowed_regex)) obj.character_allowed_regex = [];
    if (!obj.preset_allowed_regex || typeof obj.preset_allowed_regex !== 'object' || Array.isArray(obj.preset_allowed_regex)) obj.preset_allowed_regex = {};
    ensureStStateShapes();
    if (obj.variables && obj.variables.global && typeof obj.variables.global === 'object' && !Array.isArray(obj.variables.global)) {
        Object.assign(state.extensionSettings.variables.global, obj.variables.global);
    }
    obj.variables = state.extensionSettings.variables;
    if (!obj.note || typeof obj.note !== 'object' || Array.isArray(obj.note)) {
        obj.note = {
            default: '',
            defaultPosition: 1,
            defaultDepth: 4,
            defaultInterval: 1,
            defaultRole: 0,
            chara: [],
            allowWIScan: false,
        };
    }
    if (!state.thirdPartySettings || typeof state.thirdPartySettings !== 'object' || Array.isArray(state.thirdPartySettings)) {
        state.thirdPartySettings = obj;
    } else {
        (function deepMergeInPlace(target, source) {
            for (const k of Object.keys(source)) {
                const sv = source[k];
                const tv = target[k];
                if (sv && typeof sv === 'object' && !Array.isArray(sv) && tv && typeof tv === 'object' && !Array.isArray(tv)) {
                    deepMergeInPlace(tv, sv);
                } else {
                    target[k] = sv;
                }
            }
        })(state.thirdPartySettings, obj);
    }
    try {
        if (typeof window.__rikkaSyncPowerUserFromExt === 'function') {
            window.__rikkaSyncPowerUserFromExt();
        }
    } catch (_ePu) { /* noop */ }
}

// 角色信息（ST `characters[i]` / v1CharData 语义）
//   ⚠️ v204 事故：这里曾经只塞 `{ name, data: { extensions: { world } } }`，
//   于是 ST-Prompt-Template 的 `getCharacterDefine()` 在 `char.mes_example.trim()` 处炸掉，
//   真机日志却写成 `Error processing world info: TypeError: ... reading 'trim'`（世界书背了黑锅）。
//   形态补齐统一交给 character-shape.js（补全 ST 的全部字段），宿主 Kotlin 侧同步输出完整形态：
//   少一个键，扩展就会在某个 .trim()/.replace()/for..of 处炸掉。
function pullCharacter() {
    let obj = null;
    try { obj = JSON.parse(callBridge('getCharacterJson') || '{}'); } catch (_e) { obj = null; }
    if (!obj || typeof obj !== 'object') obj = {};
    const char = normalizeStCharacter(obj);

    if (char.name) state.name2 = char.name;
    state.characters = char.name ? [char] : [];
    state.characterId = 0;

    // [v208] 世界书名列表 + 全局启用书（P0-3：此前 world_names/selected_world_info 恒为空）
    try {
        const raw = callBridge('getWorldNamesJson');
        const parsed = raw ? JSON.parse(raw) : null;
        if (parsed && typeof parsed === 'object') {
            state.worldNames = Array.isArray(parsed.world_names) ? parsed.world_names : [];
            state.selectedWorldInfo = Array.isArray(parsed.selected) ? parsed.selected : [];
            state.worldInfo = {
                globalSelect: state.selectedWorldInfo.slice(),
                charLore: Array.isArray(parsed.charLore) ? parsed.charLore : [],
            };
            // 角色绑定的主世界书（data.extensions.world）也算「可用书」，兜底并入列表
            const w = char.data && char.data.extensions && char.data.extensions.world;
            if (typeof w === 'string' && w) {
                if (!state.worldNames.includes(w)) state.worldNames.push(w);
                if (!state.selectedWorldInfo.includes(w)) state.selectedWorldInfo.push(w);
                if (!state.worldInfo.globalSelect.includes(w)) state.worldInfo.globalSelect.push(w);
            }
        }
    } catch (_e) { /* noop */ }
}

function syncMessageVariablesOnly() {
    try {
        const mvRaw = callBridge('getMessageVariablesJson') || '[]';
        const mv = JSON.parse(mvRaw);
        if (!Array.isArray(mv)) return;
        if (!Array.isArray(state.chat) || state.chat.length === 0) {
            state.chat = mv.map((vs, idx) => ({
                name: idx === 0 ? state.name2 : 'Message',
                is_user: false,
                is_system: false,
                mes: '',
                swipe_id: 0,
                swipes: [''],
                variables: (Array.isArray(vs) && vs.length > 0) ? vs : [{}],
                __lightweightPlaceholder: true,
            }));
        } else {
            mv.forEach((vs, i) => {
                if (state.chat[i] && typeof state.chat[i] === 'object') {
                    state.chat[i].variables = (Array.isArray(vs) && vs.length > 0) ? vs : [{}];
                }
            });
        }
    } catch (_e) { /* noop */ }
}

function refreshForPanelMode() {
    pullMetadata();
    pullSettings();
    pullThirdPartySettings(false);
    pullCharacter();
    const cid = callBridge('getActiveConversationId');
    if (typeof cid === 'string') state.chatId = cid;
    syncMessageVariablesOnly();
    ensureStStateShapes();
    try { window.dispatchEvent(new Event('rikka-st-refresh')); } catch (_e) { /* noop */ }
}

function refreshSettings() {
    try { pullSettings(); } catch (_e) { /* noop */ }
    try { pullThirdPartySettings(true); } catch (_e) { /* noop */ }
}

function refreshAll() {
    invalidateChatCache();
    pullChat(true);
    pullMetadata();
    pullSettings();
    pullThirdPartySettings(false);
    pullCharacter();
    const cid = callBridge('getActiveConversationId');
    if (typeof cid === 'string') state.chatId = cid;
    // [v215] 刷新后保证 ST 契约形状（ST-PT / JSR 全局变量读写依赖）
    ensureStStateShapes();
    // 通知 st-compat shim 重新快照（live binding）
    try { window.dispatchEvent(new Event('rikka-st-refresh')); } catch (_e) { /* noop */ }
}

// ---------------------------------------------------------------- 写回（JS → Kotlin，去抖）
let tChat = null, tMeta = null, tGlobal = null;

function pushMessageVars() {
    try {
        callBridge('replaceMessageVariables', JSON.stringify(state.chat.map(m => (m && Array.isArray(m.variables)) ? m.variables : [{}])));
    } catch (e) { safeWarn('pushMessageVars failed', e); }
}

function pushChatVars() {
    try { callBridge('replaceChatVariables', JSON.stringify(state.chatMetadata.variables || {})); }
    catch (e) { safeWarn('pushChatVars failed', e); }
}

function pushGlobalVars() {
    try {
        ensureStStateShapes();
        const globalObj = (state.thirdPartySettings && state.thirdPartySettings.variables && state.thirdPartySettings.variables.global)
            || (state.extensionSettings && state.extensionSettings.variables && state.extensionSettings.variables.global)
            || {};
        state.extensionSettings.variables.global = globalObj;
        state.thirdPartySettings.variables = state.extensionSettings.variables;
        callBridge('replaceGlobalVariables', JSON.stringify(globalObj));
    } catch (e) { safeWarn('pushGlobalVars failed', e); }
}

function pushThirdPartySettingsNow() {
    try {
        if (typeof window.__rikkaSyncPowerUserToExt === 'function') {
            window.__rikkaSyncPowerUserToExt();
        }
        callBridge('saveThirdPartySettings', JSON.stringify(state.thirdPartySettings || {}));
        thirdPartySettingsDirty = false;
    } catch (e) { safeWarn('pushThirdPartySettings failed', e); }
}

function saveChat() {
    clearTimeout(tChat);
    tChat = setTimeout(() => { pushMessageVars(); pushChatContent(); }, 800);
}
function rebuildChatFingerprint() {
    chatFingerprintParts = state.chat.map(chatFingerprintPart);
}
function chatFingerprintPart(m) {
    return JSON.stringify([
        m && m.mes != null ? String(m.mes) : '',
        m && Array.isArray(m.swipes) ? m.swipes : [],
        m && typeof m.swipe_id === 'number' ? m.swipe_id : 0,
        !!(m && m.is_system),
        m && m.name != null ? String(m.name) : '',
    ]);
}
/**
 * 把 JS 侧对聊天记录内容的修改（swipes / swipe_id / mes / is_system / name）回写宿主。
 * 仅推送与上次快照相比有变化的楼层；无变化时静默返回。
 */
function pushChatContent() {
    try {
        const parts = state.chat.map(chatFingerprintPart);
        const updates = [];
        for (let i = 0; i < parts.length; i++) {
            if (parts[i] !== chatFingerprintParts[i]) {
                const m = state.chat[i] || {};
                updates.push({
                    index: i,
                    mes: m.mes != null ? String(m.mes) : '',
                    swipes: (Array.isArray(m.swipes) ? m.swipes : []).map((s) => (typeof s === 'string' ? s : '')),
                    swipe_id: typeof m.swipe_id === 'number' ? m.swipe_id : 0,
                    is_system: !!m.is_system,
                    name: m.name != null ? String(m.name) : '',
                });
            }
        }
        chatFingerprintParts = parts;
        if (updates.length === 0) return;
        callBridge('updateChatMessages', JSON.stringify(updates));
    } catch (e) {
        safeWarn('pushChatContent failed', e);
    }
}
function saveMetadataDebounced() { clearTimeout(tMeta); tMeta = setTimeout(pushChatVars, 800); }
function saveSettingsDebounced() {
    thirdPartySettingsDirty = true;
    try {
        if (typeof window.__rikkaSyncPowerUserToExt === 'function') {
            window.__rikkaSyncPowerUserToExt();
        }
    } catch (_e) { /* noop */ }
    clearTimeout(tGlobal);
    const delayMs = window.__RIKKA_EXT_SETTINGS_MODE__ ? 40 : 400;
    tGlobal = setTimeout(() => { pushGlobalVars(); pushThirdPartySettingsNow(); }, delayMs);
}
function flushAll() {
    clearTimeout(tChat); clearTimeout(tMeta); clearTimeout(tGlobal);
    pushMessageVars(); pushChatContent(); pushChatVars(); pushGlobalVars(); pushThirdPartySettingsNow();
}

// ---------------------------------------------------------------- ST 常量（对齐 popup.js）
const POPUP_TYPE = { TEXT: 1, CONFIRM: 2, INPUT: 3, DISPLAY: 4, CROP: 5 };
const POPUP_RESULT = {
    AFFIRMATIVE: 1, NEGATIVE: 0, CANCELLED: null,
    CUSTOM1: 1001, CUSTOM2: 1002, CUSTOM3: 1003, CUSTOM4: 1004, CUSTOM5: 1005,
    CUSTOM6: 1006, CUSTOM7: 1007, CUSTOM8: 1008, CUSTOM9: 1009,
};

const stFunctionToolRegistry = new Map();
let stFunctionToolSyncTimer = null;
function syncJsFunctionToolsToHost() {
    if (window.__RIKKA_PANEL_MODE__ || window.__RIKKA_EXT_SETTINGS_MODE__) return;
    if (stFunctionToolSyncTimer) clearTimeout(stFunctionToolSyncTimer);
    stFunctionToolSyncTimer = setTimeout(() => {
        stFunctionToolSyncTimer = null;
        try {
            const list = [];
            stFunctionToolRegistry.forEach((def, name) => {
                try {
                    if (typeof def.shouldRegister === 'function' && !def.shouldRegister()) return;
                } catch (_e) { /* ignore shouldRegister error */ }
                list.push({
                    name: String(name),
                    displayName: String(def.displayName || name),
                    description: String(def.description || ''),
                    parameters: (def.parameters && typeof def.parameters === 'object') ? def.parameters : {},
                    stealth: !!def.stealth,
                });
            });
            callBridge('emitEvent', 'js_function_tools_updated', JSON.stringify(list));
        } catch (_e) { /* noop */ }
    }, 20);
}

function registerStFunctionTool(toolDef) {
    if (!toolDef || typeof toolDef !== 'object') return false;
    const name = String(toolDef.name || '').trim();
    if (!name || typeof toolDef.action !== 'function') return false;
    stFunctionToolRegistry.set(name, toolDef);
    syncJsFunctionToolsToHost();
    return true;
}

function unregisterStFunctionTool(name) {
    const key = String(name || '').trim();
    if (!key) return false;
    const existed = stFunctionToolRegistry.delete(key);
    syncJsFunctionToolsToHost();
    return existed;
}

window.__rikkaInvokeJsFunctionTool = async (name, argsJson, seq) => {
    const finish = (payload) => {
        try {
            if (window.RikkaBridge && typeof window.RikkaBridge.onFunctionToolResult === 'function') {
                window.RikkaBridge.onFunctionToolResult(seq, JSON.stringify(payload));
            }
        } catch (_e) { /* noop */ }
    };
    try {
        const key = String(name || '').trim();
        let entry = stFunctionToolRegistry.get(key);
        if (!entry) {
            for (const [k, v] of stFunctionToolRegistry.entries()) {
                if (k.toLowerCase() === key.toLowerCase()) { entry = v; break; }
            }
        }
        if (!entry || typeof entry.action !== 'function') {
            finish({ ok: false, error: `Tool not registered: ${key}` });
            return;
        }
        let parsedArgs = {};
        try { parsedArgs = argsJson ? JSON.parse(argsJson) : {}; } catch (_e) { parsedArgs = {}; }
        const rawOut = await Promise.resolve(entry.action(parsedArgs));
        const valueStr = (typeof rawOut === 'string') ? rawOut : JSON.stringify(rawOut ?? '');
        finish({ ok: true, value: valueStr });
    } catch (err) {
        finish({ ok: false, error: String((err && err.message) || err) });
    }
};

const toolManagerStub = {
    isToolCallingSupported: () => true,
    canPerformToolCalls: () => true,
    registerFunctionTool: registerStFunctionTool,
    unregisterFunctionTool: unregisterStFunctionTool,
};

const chatCompletionSettingsStub = {
    chat_completion_source: 'custom',
    openai_model: 'unknown',
    reverse_proxy: '',
    temperature: 1,
    max_tokens: 4096,
};

async function callGenericPopupStub(content, type, defaultResult /*, options */) {
    try {
        callBridge('log', 'info', `[popup] type=${type} content=${String(content).slice(0, 200)}`);
    } catch (_e) { /* noop */ }
    // 无界面宿主：直接返回调用方给定的默认结果（不阻塞自动化流程）
    return defaultResult;
}

// ---------------------------------------------------------------- [v214] ST 兼容成员面的实现体
// st-memory-enhancement 走 `ctx.macros.registry.registerMacro`（新版）与 `ctx.registerMacro`（旧版）双路径；
// 这里给一个真实注册表（可查询、可展开），避免 TypeError。
const stMacroRegistry = new Map();
let stMacroSyncTimer = null;
function syncStMacrosToHost() {
    if (stMacroSyncTimer) clearTimeout(stMacroSyncTimer);
    stMacroSyncTimer = setTimeout(() => {
        stMacroSyncTimer = null;
        try {
            const out = {};
            if (typeof MacrosParserModule !== 'undefined' && MacrosParserModule.MacrosParser && MacrosParserModule.MacrosParser.macros instanceof Map) {
                for (const [k, v] of MacrosParserModule.MacrosParser.macros.entries()) {
                    out[k] = { type: typeof v === 'function' ? 'function' : 'string', value: typeof v === 'function' ? null : String(v) };
                }
            }
            for (const [k, v] of stMacroRegistry.entries()) {
                out[k] = { type: typeof v === 'function' ? 'function' : 'string', value: typeof v === 'function' ? null : String(v) };
            }
            callBridge('emitEvent', 'macros_updated', JSON.stringify(out));
        } catch (_e) { /* noop */ }
    }, 30);
}
function registerStMacroUnified(name, value) {
    try {
        const key = String(name);
        stMacroRegistry.set(key, value);
        if (typeof MacrosParserModule !== 'undefined' && MacrosParserModule.MacrosParser && typeof MacrosParserModule.MacrosParser.registerMacro === 'function') {
            MacrosParserModule.MacrosParser.registerMacro(key, value);
        }
        syncStMacrosToHost();
    } catch (_e) { /* noop */ }
    return value;
}
function unregisterStMacroUnified(name) {
    try {
        const key = String(name);
        stMacroRegistry.delete(key);
        if (typeof MacrosParserModule !== 'undefined' && MacrosParserModule.MacrosParser && typeof MacrosParserModule.MacrosParser.unregisterMacro === 'function') {
            MacrosParserModule.MacrosParser.unregisterMacro(key);
        }
        syncStMacrosToHost();
    } catch (_e) { /* noop */ }
}
const stMacrosFacade = {
    macros: stMacroRegistry,
    registry: {
        registerMacro: (name, value) => registerStMacroUnified(name, value),
        unregisterMacro: (name) => unregisterStMacroUnified(name),
        getMacro: (name) => stMacroRegistry.get(String(name)) ?? null,
        hasMacro: (name) => stMacroRegistry.has(String(name)),
        clear: () => {
            try {
                stMacroRegistry.clear();
                if (typeof MacrosParserModule !== 'undefined' && MacrosParserModule.MacrosParser && MacrosParserModule.MacrosParser.macros instanceof Map) {
                    MacrosParserModule.MacrosParser.macros.clear();
                }
                syncStMacrosToHost();
            } catch (_e) { /* noop */ }
        },
    },
    register: (name, value) => registerStMacroUnified(name, value),
    unregister: (name) => unregisterStMacroUnified(name),
    registerMacro: (name, value) => registerStMacroUnified(name, value),
    unregisterMacro: (name) => unregisterStMacroUnified(name),
    getMacro: (name) => stMacroRegistry.get(String(name)) ?? null,
};

/** ST ctx.substituteParams：优先调用 RikkaBridge.substituteParams（完整 82+ Kotlin 宏引擎），再展开 JS 函数宏与消息索引宏。 */
function substituteParamsWithMacros(text, name1, name2, original, group) {
    let out = String(text == null ? '' : text);
    if (!out.includes('{{') && !/<(?:user|char|bot|group)>/i.test(out)) return out;
    try {
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.substituteParams === 'function') {
            const ctxPayload = JSON.stringify({
                name1: name1 ?? state.name1 ?? '',
                name2: name2 ?? state.name2 ?? '',
                original: original ?? '',
                group: group ?? '',
            });
            out = String(window.RikkaBridge.substituteParams(out, ctxPayload) ?? out);
        }
    } catch (_e) { /* noop */ }
    if (!out.includes('{{')) return out;
    try {
        out = out.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (m, name) => {
            const lower = String(name).toLowerCase();
            if (lower === 'lastmessageid') {
                return String(Math.max(0, (state.chat || []).length - 1));
            }
            if (lower === 'firstmessageid') {
                return '0';
            }
            if (lower === 'lastusermessageid') {
                const chat = state.chat || [];
                for (let i = chat.length - 1; i >= 0; i--) {
                    if (chat[i] && chat[i].is_user) return String(i);
                }
                return '-1';
            }
            if (lower === 'lastcharmessageid') {
                const chat = state.chat || [];
                for (let i = chat.length - 1; i >= 0; i--) {
                    if (chat[i] && !chat[i].is_user && !chat[i].is_system) return String(i);
                }
                return '-1';
            }
            if (lower === 'char') return String(state.name2 || '');
            if (lower === 'user') return String(state.name1 || '');
            if (stMacroRegistry.has(name) || stMacroRegistry.has(lower)) {
                try {
                    const entry = stMacroRegistry.get(name) ?? stMacroRegistry.get(lower);
                    const fn = (entry && typeof entry === 'object' && 'fn' in entry) ? entry.fn : entry;
                    return typeof fn === 'function' ? String(fn()) : String(fn ?? '');
                } catch (_e) { return m; }
            }
            return m;
        });
    } catch (_e) { /* noop */ }
    return out;
}

/** ST ctx.variables.local / global（st-context.js:288-303），增量合并写回，严禁抹掉其它已有键。 */
function makeStVariableScope(type) {
    const readAll = () => {
        try { return shimVariable.getVariables({ type }) || {}; } catch (_e) { return {}; }
    };
    const write = (name, value) => {
        try {
            const current = readAll();
            current[String(name)] = value;
            shimVariable.replaceVariables(current, { type });
        } catch (_e) { /* noop */ }
    };
    return {
        get: (name) => { const v = readAll(); return name == null ? v : v[name]; },
        set: (name, value) => write(name, value),
        del: (name) => { try { shimVariable.deleteVariable(String(name), { type }); } catch (_e) { /* noop */ } },
        add: (name, value) => { const v = Number(readAll()[name] || 0) + Number(value || 0); write(name, v); return v; },
        inc: (name) => { const v = Number(readAll()[name] || 0) + 1; write(name, v); return v; },
        dec: (name) => { const v = Number(readAll()[name] || 0) - 1; write(name, v); return v; },
        has: (name) => Object.prototype.hasOwnProperty.call(readAll(), String(name)),
    };
}
const stVariablesFacade = { local: makeStVariableScope('chat'), global: makeStVariableScope('global') };

/**
 * [v231] ST ctx.swipe（st-context.js:277-287）真桥（此前为安全桩，长尾遗留项）。
 *
 * 复用宿主已有的 `/swipe` 斜杠命令实现（StSlashExecutor.kt:239 -> ChatSlashHost.kt:326，
 * 含「左=切换上一变体 / 右=切换下一变体 / 右越界=重新生成」全套 ST 语义），零宿主改动：
 *   - left/right() 经 RikkaBridge.triggerSlash("/swipe direction=..|await=..") 走真桥；
 *   - to() 在 JS 侧直接改最后楼层 swipe_id -> pushChatContent() 走既有写回链持久化，
 *     并广播 message_swiped（对齐 ST ctx.swipe.to 的本地切换语义）；
 *   - show/hide/refresh 是纯 DOM 面板操作（宿主消息 UI 自绘，无 DOM 面板可操作），保持 no-op；
 *   - isAllowed 对齐 ST：最后一条 AI 消息才允许 swipe；
 *   - state() 返回当前楼层真实 swipe_id（从 state.chat 取，不再恒 0）。
 */
const stSwipeFacade = {
    left: async (options) => {
        try {
            const awaitFlag = options && typeof options === 'object' && options.await ? 'true' : 'false';
            const r = callBridge('triggerSlash', '/swipe direction=left await=' + awaitFlag);
            return !/error|not ready|无法|失败/i.test(String(r == null ? '' : r));
        } catch (_e) { return false; }
    },
    right: async (options) => {
        try {
            const awaitFlag = options && typeof options === 'object' && options.await ? 'true' : 'false';
            const r = callBridge('triggerSlash', '/swipe direction=right await=' + awaitFlag);
            return !/error|not ready|无法|失败/i.test(String(r == null ? '' : r));
        } catch (_e) { return false; }
    },
    to: async (swipeId) => {
        try {
            const id = Number(swipeId);
            if (!Number.isFinite(id)) return false;
            const n = state.chat ? state.chat.length : 0;
            const m = n > 0 ? state.chat[n - 1] : null;
            if (!m || !Array.isArray(m.swipes) || id < 0 || id >= m.swipes.length) return false;
            m.swipe_id = id;
            pushChatContent();
            try { eventSource.emit('message_swiped', n - 1); } catch (_e) { /* noop */ }
            return true;
        } catch (_e) { return false; }
    },
    show: () => {},
    hide: () => {},
    refresh: () => {},
    isAllowed: () => {
        try {
            const n = state.chat ? state.chat.length : 0;
            const m = n > 0 ? state.chat[n - 1] : null;
            return !!(m && !m.is_user && !m.is_system);
        } catch (_e) { return false; }
    },
    state: () => {
        try {
            const n = state.chat ? state.chat.length : 0;
            const m = n > 0 ? state.chat[n - 1] : null;
            return { swipeId: m && typeof m.swipe_id === 'number' ? m.swipe_id : 0 };
        } catch (_e) { return { swipeId: 0 }; }
    },
};

/** ST ctx.messageFormatter（st-context.js:275）：最小实现（Markdown 渲染由宿主/面板负责）。 */
const stMessageFormatterFacade = MessageFormatter;
window.__rikkaMessageFormatter = MessageFormatter;

window.__rikkaRunMessageFormatterStage = function (seq, stage, text, contextJson) {
    let ctx = {};
    try { ctx = contextJson ? JSON.parse(contextJson) : {}; } catch (_e) { ctx = {}; }
    const out = MessageFormatter.runStage(stage, String(text == null ? '' : text), ctx);
    try {
        callBridge('onMessageFormatterStageResult', seq, String(out));
    } catch (_e) { /* noop */ }
    return out;
};

const ST_SYMBOL_IGNORE = Symbol('ST.ignore');
const ST_SYMBOL_UNSET = Symbol('ST.unset');

/** [v214] 千纱卡编辑消息后的写回链：改 state.chat → 桥持久化 → 广播事件。 */
function stUpdateMessageBlock(messageId, message, options) {
    try {
        const idx = Number(messageId);
        let resolved = message;
        if (Number.isFinite(idx) && state.chat && state.chat[idx]) {
            const cur = state.chat[idx];
            if (message && typeof message === 'object') {
                for (const k of Object.keys(message)) {
                    if (k === 'variables') continue;
                    cur[k] = message[k];
                }
            }
            resolved = cur;
            try { callBridge('updateChatMessages', JSON.stringify(state.chat)); } catch (_e) { /* noop */ }
            try { eventSource.emit('message_updated', { message_id: idx, message: cur }); } catch (_e) { /* noop */ }
        }
        // [v236 R1] 同步 `#chat` 楼层 DOM（卡内脚本 / ST-PT 读写楼层节点）
        chatDom.updateMessageBlock(Number.isFinite(idx) ? idx : messageId, resolved, options);
    } catch (e) { safeWarn('updateMessageBlock failed', e); }
}

// [v235 R2] 装载离线 KaTeX（index.html / panel.html 的 <script> 已先加载 katex.min.js）
try { mathRender.setKatex(window.katex); } catch (_e) { /* noop */ }

/**
 * [v231] ST ctx.messageFormatting（script.js:1800 真源契约）：
 *   messageFormatting(mes, ch_name, isSystem, isUser, messageId, sanitizerOverrides={}, isReasoning=false)
 * 语义 = Markdown -> HTML 全流程（此前为「原文返回」的桩，ST-PT 消息内渲染降级裸文本）。
 * 实现：复用 third-party-globals.js 的 showdown 兼容层（真库或轻量桩，同名 makeHtml API）
 * 做 Markdown -> HTML；宏替换已由 substitudeMacros 链路在上游完成，此处不重复；
 * isReasoning=true 时包 reasoning 折叠块（对齐 ST 的 reasoning 视觉语义）。
 */
function stMessageFormatting(text, ch_name, isSystem, isUser, messageId, sanitizerOverrides, isReasoning) {
    const baseCtx = {
        characterName: String(ch_name ?? state.name2 ?? ''),
        ch_name: String(ch_name ?? state.name2 ?? ''),
        isSystem: Boolean(isSystem),
        isUser: Boolean(isUser),
        messageId: typeof messageId === 'number' ? messageId : -1,
        isReasoning: Boolean(isReasoning),
    };
    let raw = String(text == null ? "" : text);
    try {
        raw = MessageFormatter.runStage(formatting_stage.BEFORE_REGEX, raw, baseCtx);
    } catch (_e) { /* noop */ }
    try {
        raw = MessageFormatter.runStage(formatting_stage.AFTER_REGEX, raw, baseCtx);
    } catch (_e) { /* noop */ }
    let html = raw;
    try {
        const pre = mathRender.stashMath(raw);
        let body = pre.text;
        if (typeof window.showdown !== "undefined" && window.showdown && typeof window.showdown.Converter === "function") {
            if (!stMessageFormatting._converter) {
                try { stMessageFormatting._converter = new window.showdown.Converter({ simpleLineBreaks: false, ghCodeBlocks: true, tables: true, strikethrough: true }); }
                catch (_e) { stMessageFormatting._converter = new window.showdown.Converter(); }
            }
            body = stMessageFormatting._converter.makeHtml(body);
        }
        html = pre.restore(body);
    } catch (_e) { html = raw; }
    try {
        html = MessageFormatter.runStage(formatting_stage.AFTER_MARKDOWN, html, baseCtx);
    } catch (_e) { /* noop */ }
    if (isReasoning) {
        html = '<div class="reasoning_block"><details open><summary>思考过程</summary>' + html + '</details></div>';
    }
    return html;
}

// ---------------------------------------------------------------- ST 上下文（ctx）
// [v228 S1] ST 兼容版本口径（**唯一真源**）：`/version` 端点、`SillyTavern.getContext().getVersion()`、
// `TavernHelper.getTavernVersion()` 三处必须是同一个值 —— v227 之前它们分别是 1.0.0（404 兜底）/1.13.4/1.13.4。
// 取值依据见 notes/recon-version-gate-20261008.md §4：1.13.5 = 打开 JSR 的 G1/G2/G3 三条闸门，
// 同时**不开** 1.15.0（宿主 createGenerationParameters 还是桩、MacroRegistry 模块缺失）。
// Kotlin 侧同一个值定义在 TavernRuntimeManager.TAVERN_ST_COMPAT_VERSION（回归测试会比对两处一致）。
const TAVERN_ST_COMPAT_VERSION = '1.19.0';

const stCtx = {
    getThumbnailUrl(type = 'avatar', file = '') {
        const f = String(file || '').trim();
        if (f.startsWith('http://') || f.startsWith('https://') || f.startsWith('data:') || f.startsWith('blob:')) {
            return f;
        }
        const effectiveFile = f || (state.character && state.character.avatar) || '';
        if (!effectiveFile) return 'img/ai4.png';
        return `https://appassets.androidplatform.net/thumbnail?type=${encodeURIComponent(String(type || 'avatar'))}&file=${encodeURIComponent(effectiveFile)}`;
    },
    get chat() {
        if (window.__RIKKA_PANEL_MODE__ && (!Array.isArray(state.chat) || state.chat.length === 0 || (state.chat[0] && state.chat[0].__lightweightPlaceholder))) {
            try { pullChat(false); } catch (_e) { /* noop */ }
        }
        return state.chat;
    },
    get chatMetadata() { ensureStStateShapes(); return state.chatMetadata; },
    get extensionSettings() { ensureStStateShapes(); return state.thirdPartySettings; },
    get characters() { return state.characters; },
    get characterId() { return state.characterId; },
    // [v208] ST world_names / world_info / selected_world_info（st-compat/world-info.js 的 refresh 从这里取）
    get world_names() { return state.worldNames; },
    get world_info() { return state.worldInfo; },
    get selected_world_info() { return state.selectedWorldInfo; },
    get name1() { return state.name1; },
    get name2() { return state.name2; },
    get chatId() { return state.chatId; },
    get eventSource() { return eventSource; },
    // [v228 S1] ST /version 口径：JSR 读 fetch('/version').pkgVersion；shims/util-shims.js 的
    // getTavernVersion() 读这里。两处必须一致（v227 是 1.0.0 vs 1.13.4 的漂移）。
    getVersion: () => TAVERN_ST_COMPAT_VERSION,
    get eventTypes() { return event_types; },
    get event_types() { return event_types; },
    get chatCompletionSettings() { return chatCompletionSettingsStub; },
    POPUP_TYPE, POPUP_RESULT,
    ToolManager: toolManagerStub,
    saveChat,
    saveMetadataDebounced,
    saveMetadata: saveMetadataDebounced,
    saveSettingsDebounced,
    reloadCurrentChat: () => { refreshAll(); return Promise.resolve(); },
    loadWorldInfo: async (name) => {
        try {
            const raw = callBridge('getLorebookJson', String(name == null ? '' : name));
            const parsed = raw ? JSON.parse(raw) : {};
            return (parsed && typeof parsed === 'object') ? parsed : {};
        } catch (_e) {
            return {};
        }
    },
    substituteParams: (text, name1, name2, original, group) => substituteParamsWithMacros(text, name1, name2, original, group),
    substituteParamsExtended: (text, name1, name2, original, group) => substituteParamsWithMacros(text, name1, name2, original, group),
    getRequestHeaders: () => ({}),
    getCurrentChatId: () => state.chatId,
    getCurrentLocale: () => 'zh-cn',
    getChatCompletionModel: () => state.modelName || 'unknown',
    getCharacterCardFields: () => ({ name: state.name2 || '', description: '', personality: '', scenario: '', first_mes: '', mes_example: '' }),
    callGenericPopup: callGenericPopupStub,
    registerFunctionTool: registerStFunctionTool,
    unregisterFunctionTool: unregisterStFunctionTool,
    registerMacro: stMacrosFacade.registerMacro,
    unregisterMacro: stMacrosFacade.unregisterMacro,
    extensionPrompts: {},
    get extension_prompts() { return stCtx.extensionPrompts; },
    executeSlashCommandsWithOptions: async (text, _options) => {
        try {
            const raw = callBridge('triggerSlash', String(text == null ? '' : text));
            const pipe = raw == null ? '' : String(raw);
            const isErr = pipe.startsWith('(slash error:') || pipe === '(slash runner not ready)';
            return {
                pipe: isErr ? '' : pipe,
                isError: isErr,
                errorMessage: isErr ? pipe : '',
                isAbort: false,
                isIdle: false,
                hasBackpipe: false,
            };
        } catch (_e) {
            return { pipe: '', isError: true, errorMessage: String(_e), isAbort: false, isIdle: false, hasBackpipe: false };
        }
    },
    executeSlashCommands: async (text) => {
        const r = await stCtx.executeSlashCommandsWithOptions(text);
        return r && typeof r === 'object' ? r.pipe : r;
    },
    // [P0 修复] 不要再把失败吞成空字符串：宿主拒绝（未选模型 / 无消息 / slashRunner 未接线）
    // 一律以 `(slash error: ...)` 返回，这里必须抛出去，让卡片的 `await generate()` 真的进 catch。
    generate: async () => {
        const raw = callBridge('triggerSlash', '/trigger');
        const text = raw == null ? '' : String(raw);
        if (text.startsWith('(slash error:') || text === '(slash runner not ready)') {
            throw new Error(text);
        }
        return text;
    },
    generateRaw: async (opts) => {
        try {
            const prompt = typeof opts === 'string' ? opts : (opts && (opts.prompt || opts.quietPrompt) ? String(opts.prompt || opts.quietPrompt) : '');
            const systemPrompt = (opts && typeof opts === 'object' && (opts.systemPrompt || opts.system)) ? String(opts.systemPrompt || opts.system) : '';
            if (!prompt) return '';
            if (window.RikkaBridge && typeof window.RikkaBridge.generateQuiet === 'function') {
                return String(callBridge('generateQuiet', prompt, true, systemPrompt) || '');
            }
            const quotedPrompt = '"' + prompt.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
            const sysArg = systemPrompt ? ('system="' + systemPrompt.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '" ') : '';
            return String(callBridge('triggerSlash', '/genraw ' + sysArg + quotedPrompt) || '');
        } catch (_e) { return ''; }
    },
    generateQuietPrompt: async (quietPrompt) => {
        try {
            const prompt = typeof quietPrompt === 'string'
                ? quietPrompt
                : (quietPrompt && (quietPrompt.quietPrompt || quietPrompt.prompt) ? String(quietPrompt.quietPrompt || quietPrompt.prompt) : '');
            if (!prompt) return '';
            if (window.RikkaBridge && typeof window.RikkaBridge.generateQuiet === 'function') {
                return String(callBridge('generateQuiet', prompt, false, '') || '');
            }
            const quotedPrompt = '"' + prompt.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
            return String(callBridge('triggerSlash', '/gen ' + quotedPrompt) || '');
        } catch (_e) { return ''; }
    },
    generateRawData: async () => ({}),
    sendStreamingRequest: async () => {},
    sendGenerationRequest: async () => {},
    stopGeneration: () => {
        try {
            if (stCompatScript && stCompatScript.externalAbortController && typeof stCompatScript.externalAbortController.abort === 'function') {
                stCompatScript.externalAbortController.abort();
            }
        } catch (_e) { /* noop */ }
        try {
            const streams = window.__rikkaApiStreams || {};
            for (const reqId of Object.keys(streams)) {
                try { callBridge('chatCompletionCancel', reqId); } catch (_e2) { /* noop */ }
                try { window.__rikkaApiFinish(reqId, 'ABORTED'); } catch (_e3) { /* noop */ }
            }
        } catch (_e) { /* noop */ }
        try { callBridge('stopGeneration'); } catch (_e) { /* noop */ }
        try { eventSource.emit(event_types.GENERATION_STOPPED); } catch (_e) { /* noop */ }
        return true;
    },
    // ---- 面板/卡脚本统一命令面：全部返回 { ok, result, error }，不再是“发后即忘” ----
    sendMessage: (text, options) => panelCommand('send_message', {
        text: String(text == null ? '' : text),
        answer: !(options && options.answer === false),
    }, options && options.timeout),
    sendMessageAsUser: (text, options) => panelCommand('send_as_user', {
        text: String(text == null ? '' : text),
    }, options && options.timeout),
    sendMessageAsAssistant: (text, options) => panelCommand('send_as_assistant', {
        text: String(text == null ? '' : text),
    }, options && options.timeout),
    triggerSlashAsync: (script, options) => panelCommand('trigger_slash', {
        script: String(script == null ? '' : script),
    }, options && options.timeout),
    emitHostEvent: (name, payload, options) => panelCommand('emit_event', {
        event: String(name == null ? '' : name),
        payload: typeof payload === 'string' ? payload : JSON.stringify(payload == null ? {} : payload),
    }, options && options.timeout),
    saveChatAsync: () => panelCommand('save_chat', {}),
    stopGenerationAsync: () => panelCommand('stop_generation', {}),
    clearChat: () => {},
    printMessages: () => {},
    // ---- [v214] ST getContext() 成员面补齐（st-context.js:129-311；按真实消费方优先级）----
    get groups() { return []; },
    get groupId() { return null; },
    get onlineStatus() { return ''; },
    get maxContext() { return 32768; },
    get mainApi() { return 'openai'; },
    get textCompletionSettings() { return {}; },
    get tags() { return []; },
    get tagMap() { return {}; },
    get menuType() { return 0; },
    get accountStorage() { return accountStorage; },
    get ChatCompletionService() { return ChatCompletionService; },
    get streamingProcessor() { return {}; },
    get loader() { return {}; },
    get createCharacterData() { return {}; },
    get symbols() { return { ignore: ST_SYMBOL_IGNORE, unset: ST_SYMBOL_UNSET }; },
    get constants() { return { unset: ST_SYMBOL_UNSET }; },
    get macros() { return stMacrosFacade; },
    get messageFormatter() { return stMessageFormatterFacade; },
    get swipe() { return stSwipeFacade; },
    get variables() { return stVariablesFacade; },
    get CONNECT_API_MAP() { return {}; },
    // ST st-context.js:229 powerUserSettings（ST-PT persona 域：persona_description / persona_description_position）
    get powerUserSettings() { return stCompatPowerUser; },
    // ST st-context.js:214-217 i18n 面
    t: (text) => String(text == null ? '' : text),
    translate: (text) => String(text == null ? '' : text),
    addLocaleData: () => {},
    setExtensionPrompt: (key, value, position, depth, scan = false, role = 0) => {
        try {
            const k = String(key == null ? '' : key).trim();
            if (!k) return;
            const val = value == null ? '' : String(value);
            stCtx.extensionPrompts[k] = { value: val, position: position, depth: depth, scan: !!scan, role: role };
            if (!val) {
                shimInject.uninjectPrompts([k]);
            } else {
                const roleStr = (role === 1 || role === 'user') ? 'user'
                    : ((role === 2 || role === 'assistant') ? 'assistant' : 'system');
                const posStr = (position === -1 || position === 'none') ? 'none' : 'in_chat';
                const depthNum = Number.isFinite(Number(depth)) ? Number(depth) : 4;
                shimInject.injectPrompts([{
                    id: k,
                    content: val,
                    position: posStr,
                    depth: depthNum,
                    role: roleStr,
                    should_scan: !!scan,
                }]);
            }
        } catch (_e) { /* noop */ }
    },
    updateChatMetadata: (patch) => {
        try {
            if (patch && typeof patch === 'object') Object.assign(state.chatMetadata, patch);
            saveMetadataDebounced();
        } catch (_e) { /* noop */ }
    },
    // ST 真源 extensions.js:2070：`writeExtensionField(characterId, key, value)`。
    // 宿主端卡数据在 Kotlin 侧，这里做“可读回、可持久化”的最小落点（thirdPartySettings 命名空间）。
    writeExtensionField: (characterId, key, value) => {
        try {
            const bucket = state.thirdPartySettings['__extension_fields__'] || (state.thirdPartySettings['__extension_fields__'] = {});
            const cid = String(characterId == null ? '' : characterId);
            if (!bucket[cid] || typeof bucket[cid] !== 'object') bucket[cid] = {};
            bucket[cid][String(key == null ? '' : key)] = value;
            pushThirdPartySettingsNow();
        } catch (_e) { /* noop */ }
    },
    // ST 真源 extensions.js:2152：`writeExtensionFieldBulk(avatars, key, value, {filterPath})`
    writeExtensionFieldBulk: (avatars, key, value) => {
        try {
            const list = Array.isArray(avatars) ? avatars : (avatars == null ? [] : [avatars]);
            list.forEach((cid) => stCtx.writeExtensionField(cid, key, value));
        } catch (_e) { /* noop */ }
    },
    getCharacters: () => state.characters,
    getOneCharacter: (id) => {
        try { return (Array.isArray(state.characters) && state.characters[Number(id)]) || null; } catch (_e) { return null; }
    },
    getCharacterSource: () => 'character',
    selectCharacterById: async () => {},
    openCharacterChat: async () => {},
    openGroupChat: async () => {},
    renameChat: async () => {},
    unshallowCharacter: async () => {},
    unshallowGroupMembers: async () => {},
    addOneMessage: () => {},
    deleteLastMessage: async () => {
        try {
            const n = state.chat ? state.chat.length : 0;
            if (n > 0) await shimChat.deleteChatMessages([n - 1]);
        } catch (_e) { /* noop */ }
    },
    deleteMessage: async (index, _swipeId, _confirm, deleteToolCalls = false) => {
        try {
            if (index === undefined || index === null) return;
            const idx = Number(index);
            const ids = [idx];
            if (deleteToolCalls && Array.isArray(state.chat)) {
                let nextIdx = idx + 1;
                while (nextIdx < state.chat.length) {
                    const m = state.chat[nextIdx];
                    if (m && (m.role === 'tool' || (m.extra && m.extra.tool_calls))) {
                        ids.push(nextIdx);
                        nextIdx++;
                    } else {
                        break;
                    }
                }
            }
            await shimChat.deleteChatMessages(ids);
        } catch (_e) { /* noop */ }
    },
    saveReply: async () => {},
    sendSystemMessage: () => {},
    activateSendButtons: () => {},
    deactivateSendButtons: () => {},
    showLoader: () => {},
    hideLoader: () => {},
    callPopup: callGenericPopupStub,
    registerHelper: () => {},
    registerDebugFunction: () => {},
    isToolCallingSupported: () => true,
    canPerformToolCalls: () => true,
    getWorldInfoPrompt: async (chat, maxContext, isDryRun, globalScanData) => {
        try {
            if (window.RikkaBridge && typeof window.RikkaBridge.getWorldInfoPrompt === 'function') {
                const raw = window.RikkaBridge.getWorldInfoPrompt(
                    JSON.stringify(chat || state.chat || []),
                    Number(maxContext) || 32768,
                    Boolean(isDryRun ?? true),
                    JSON.stringify(globalScanData || {}),
                );
                if (raw) return JSON.parse(raw);
            }
        } catch (_e) { /* noop */ }
        return { worldInfoString: '', worldInfoBefore: '', worldInfoAfter: '', worldInfoExamples: [], worldInfoDepth: [], anBefore: '', anAfter: '', outletEntries: [] };
    },
    reloadWorldInfoEditor: () => {},
    Popup: class Popup {
        constructor(content, type, inputValue, options) {
            this.content = content;
            this.type = type;
            this.value = inputValue;
            this.options = options || {};
            this.result = 1;
        }
        async show() { return this.value !== undefined ? this.value : this.result; }
    },
    get AccountStorage() { return accountStorage; },
    ARGUMENT_TYPE: { STRING: 'string', NUMBER: 'number', BOOLEAN: 'boolean', LIST: 'list', OBJECT: 'object', CLOSURE: 'closure', SUBCOMMAND: 'subcommand' },
    ConnectionManagerRequestService: { handleDropdown: () => {}, sendRequest: async () => ({}) },
    ModuleWorkerWrapper: class ModuleWorkerWrapper { constructor(fn) { this.fn = fn; } async update(...args) { return typeof this.fn === 'function' ? await this.fn(...args) : undefined; } },
    SlashCommand: class SlashCommand { static fromProps(props = {}) { return Object.assign(new SlashCommand(), props); } },
    SlashCommandArgument: class SlashCommandArgument { static fromProps(props = {}) { return Object.assign(new SlashCommandArgument(), props); } },
    SlashCommandEnumValue: class SlashCommandEnumValue { constructor(value = '', description = '') { this.value = value; this.description = description; } },
    SlashCommandNamedArgument: class SlashCommandNamedArgument { static fromProps(props = {}) { return Object.assign(new SlashCommandNamedArgument(), props); } },
    SlashCommandParser: { commands: {}, addCommandObject: (cmd) => cmd, addCommand: () => {}, getHelpString: () => '' },
    TextCompletionService: { create: () => ({}), sendRequest: async () => ({}) },
    convertCharacterBook: (book) => (book && typeof book === 'object' ? book : { entries: {} }),
    getPresetManager: () => ({ getPresetList: () => ({ presets: [], preset_names: {} }), getCompletionPresetByName: () => ({}), updateList: () => {} }),
    getWorldInfoNames: () => (Array.isArray(state.worldNames) ? state.worldNames.slice() : []),
    registerDataBankScraper: () => {},
    registerSlashCommand: () => {},
    saveWorldInfo: async (name, data) => {
        try {
            if (window.RikkaBridge && typeof window.RikkaBridge.createOrReplaceLorebook === 'function') {
                const entriesObj = (data && data.entries && typeof data.entries === 'object') ? data.entries : {};
                return Boolean(window.RikkaBridge.createOrReplaceLorebook(String(name || ''), JSON.stringify(Object.values(entriesObj))));
            }
        } catch (_e) { /* noop */ }
        return false;
    },
    tokenizers: {},
    updateWorldInfoList: async () => {},
    shouldSendOnEnter: () => false,
    isMobile: () => true,
    timestampToMoment: () => ({ format: () => '' }),
    uuidv4: () => {
        try {
            if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
        } catch (_e) { /* noop */ }
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
            const r = (Math.random() * 16) | 0;
            const v = c === 'x' ? r : ((r & 0x3) | 0x8);
            return v.toString(16);
        });
    },
    humanizedDateTime: () => new Date().toLocaleString('zh-CN', { hour12: false }),
    importFromExternalUrl: async () => null,
    importTags: async () => {},
    updateMessageBlock: stUpdateMessageBlock,
    messageFormatting: stMessageFormatting,
    // [v236 R1] ST script.js:2479 / :2216 真执行版（真插 DOM 节点，不再 no-op）
    addCopyToCodeBlocks: chatDom.addCopyToCodeBlocks,
    appendMediaToMessage: chatDom.appendMediaToMessage,
    renderMessageFloors: chatDom.renderFloors,
    ensureMessageMediaIsArray: (m) => m,
    getMediaDisplay: () => 'none',
    getMediaIndex: () => -1,
    scrollChatToBottom: () => {},
    scrollOnMediaLoad: () => {},
    getTextGenServer: () => '',
    extractMessageFromData: (data, activeApi) => stCompatScript.extractMessageFromData(data, activeApi),
    getExtensionManifest: (extId) => {
        try {
            const key = String(extId || '').trim();
            if (!key) return null;
            const shortKey = key.replace(/^third-party\//, '');
            const normAlias = (s) => String(s || '').trim().replace(/-(?:main|master|dev|release|v?\d+(?:\.\d+)*(?:-[a-z0-9._-]+)?)$/i, '').toLowerCase();
            const wantedAlias = normAlias(shortKey);
            const list = (window.__rikkaSt && Array.isArray(window.__rikkaSt.extensions)) ? window.__rikkaSt.extensions : [];
            const hit = list.find((e) => e && (e.folder === key || e.folder === shortKey || e.displayName === key || (wantedAlias && normAlias(e.folder) === wantedAlias)));
            if (!hit) return null;
            return Object.assign({
                display_name: hit.displayName || hit.folder,
                loading_order: hit.loadingOrder ?? 100,
                requires: Array.isArray(hit.requires) ? hit.requires : [],
                optional: Array.isArray(hit.optional) ? hit.optional : [],
                js: hit.js || '',
                css: hit.css || '',
                author: hit.author || '',
                version: hit.version || '',
                homePage: hit.homePage || '',
                minimum_client_version: hit.minimumClientVersion || '',
                generate_interceptor: hit.generateInterceptor || '',
                hooks: hit.hooks || {},
            }, hit.manifest || {});
        } catch (_e) { return null; }
    },
    openThirdPartyExtensionMenu: () => {},
    renderExtensionTemplate: (extensionName, templateId, templateData) => renderExtensionTemplateAsync(extensionName, templateId, templateData),
    renderExtensionTemplateAsync: (extensionName, templateId, templateData) => renderExtensionTemplateAsync(extensionName, templateId, templateData),
    updateReasoningUI: () => {},
    parseReasoningFromString: (text) => String(text == null ? '' : text),
    getReasoningTemplateByName: () => null,
    getTokenizerModel: () => '',
    getTextTokens: () => 0,
    getTokenCount: () => 0,
    getTokenCountAsync: async () => 0,
};

// ---------------------------------------------------------------- SillyTavern 全局（顶层静态成员，MVU bundle 大量使用）
const SillyTavern = {
    name: 'SillyTavern',
    version: '1.19.0',
    libs: {},
    getContext: () => stCtx,
    callGenericPopup: callGenericPopupStub,
    saveChat,
    saveSettingsDebounced,
    saveMetadataDebounced,
    getCurrentChatId: () => state.chatId,
    getRequestHeaders: () => ({}),
    getCurrentLocale: () => 'zh-cn',
    getChatCompletionModel: () => state.modelName || 'unknown',
    getCharacterCardFields: () => stCtx.getCharacterCardFields(),
    registerFunctionTool: registerStFunctionTool,
    unregisterFunctionTool: unregisterStFunctionTool,
    registerMacro: stMacrosFacade.registerMacro,
    unregisterMacro: stMacrosFacade.unregisterMacro,
    loadWorldInfo: async (name) => {
        try {
            const raw = callBridge('getLorebookJson', String(name == null ? '' : name));
            const parsed = raw ? JSON.parse(raw) : {};
            return (parsed && typeof parsed === 'object') ? parsed : {};
        } catch (_e) {
            return {};
        }
    },
};
Object.defineProperties(SillyTavern, {
    chat: { get: () => state.chat, configurable: true },
    chatMetadata: { get: () => { ensureStStateShapes(); return state.chatMetadata; }, configurable: true },
    characters: { get: () => state.characters, configurable: true },
    characterId: { get: () => state.characterId, configurable: true },
    extensionSettings: { get: () => { ensureStStateShapes(); return state.thirdPartySettings; }, configurable: true },
    chatCompletionSettings: { get: () => chatCompletionSettingsStub, configurable: true },
    name1: { get: () => state.name1, configurable: true },
    name2: { get: () => state.name2, configurable: true },
    POPUP_TYPE: { value: POPUP_TYPE },
    POPUP_RESULT: { value: POPUP_RESULT },
    ToolManager: { value: toolManagerStub },
});
window.SillyTavern = SillyTavern;

// [v214] 卡脚本第二分支 `window.top.context` / `window.context`（千纱卡 a() 函数）：
// WebView 里 window.top === window.self，挂在 window 上即同时满足 top 访问。
try {
    Object.defineProperty(window, 'context', {
        configurable: true,
        get: () => ({
            chat: state.chat,
            chatMetadata: state.chatMetadata,
            characters: state.characters,
            characterId: state.characterId,
            name1: state.name1,
            name2: state.name2,
            chatId: state.chatId,
            eventSource: eventSource,
            event_types: event_types,
            extensionSettings: state.thirdPartySettings,
        }),
    });
} catch (_e) { /* noop */ }

// [v214] 千纱卡 window.top 直挂面（卡 JSON 全卡 21 处）：
// saveChat / updateMessageBlock / messageFormatting 等。已存在的同名全局不覆盖。
(function installWindowStCompatMounts() {
    const mounts = {
        saveChat: saveChat,
        saveMetadataDebounced: saveMetadataDebounced,
        saveSettingsDebounced: saveSettingsDebounced,
        updateMessageBlock: stCtx.updateMessageBlock,
        messageFormatting: stCtx.messageFormatting,
        addCopyToCodeBlocks: stCtx.addCopyToCodeBlocks,
        appendMediaToMessage: stCtx.appendMediaToMessage,
        substituteParams: stCtx.substituteParams,
        substituteParamsExtended: stCtx.substituteParamsExtended,
        getContext: () => stCtx,
    };
    for (const k of Object.keys(mounts)) {
        try { if (!(k in window)) window[k] = mounts[k]; } catch (_e) { /* noop */ }
    }
    // [v243 BUGFIX C3] 把 st-compat/script.js 的**整个原生导出面**挂成裸全局。
    // 依据：SillyTavern 的 public/script.js 导出（249 个）里绝大多数在酒馆里同时是裸全局，
    // 卡脚本按标识符直接调用（`getChat()` / `updateChatMetadata()` / `ensureSwipes()` …）。
    // rikkaST 原来只挂了 8 个，其余全部 ReferenceError。
    // 只挂**函数**与**稳定常量**；live 标量（chat / characters / name1 …）跳过 ——
    // 它们的值是每次 refreshAll 才更新的，拷贝一份到 window 会读到过期快照。
    const LIVE_SCALARS = new Set([
        'chat', 'characters', 'name1', 'name2', 'this_chid', 'chat_metadata', 'extension_settings',
        'settings', 'amount_gen', 'max_context', 'selected_group', 'groups', 'user_avatar',
        'online_status', 'is_send_press', 'isChatSaving', 'main_api', 'extension_prompts',
        'nai_settings', 'GenerateOptions',
    ]);
    let mounted = 0;
    const sources = [stCtx, stCompatScript];
    for (const src of sources) for (const k of Object.keys(src)) {
        try {
            if (LIVE_SCALARS.has(k) || (k in window)) continue;
            const v = src[k];
            if (typeof v === 'function' || (v && typeof v === 'object') || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
                window[k] = v;
                mounted++;
            }
        } catch (_e) { /* noop */ }
    }
    try { if (typeof RikkaPanel !== 'undefined' && RikkaPanel.log) RikkaPanel.log('debug', '[boot] ST native bare globals mounted: ' + mounted); } catch (_e) { /* noop */ }
    try { console.log('[RikkaTavern] ST native bare globals mounted: ' + mounted); } catch (_e) { /* noop */ }
})();

// [v236 R1] 楼层 DOM 桩接线：渲染器 = stMessageFormatting；chat_changed / chat_id_changed → 重建楼层。
try {
    chatDom.setFormatter(stMessageFormatting);
    chatDom.setChatProvider(() => (Array.isArray(state.chat) ? state.chat : []));
    chatDom.attachChatListeners(eventSource);
} catch (_e) { /* noop */ }

// ---------------------------------------------------------------- TavernHelper 全局（JSR API 兼容层）
const TavernHelper = {
    // 宿主命令面（问题 2）：panel → host 统一 ack/toast，返回 Promise<{ok,error,result}>
    sendMessage: (...args) => stCtx.sendMessage(...args),
    sendMessageAsUser: (...args) => stCtx.sendMessageAsUser(...args),
    sendMessageAsAssistant: (...args) => stCtx.sendMessageAsAssistant(...args),
    triggerSlashAsync: (...args) => stCtx.triggerSlashAsync(...args),
    emitHostEvent: (...args) => stCtx.emitHostEvent(...args),
    saveChat: (...args) => stCtx.saveChatAsync(...args),
    stopGeneration: () => stCtx.stopGenerationAsync(),
    stopGenerationAsync: () => stCtx.stopGenerationAsync(),
    // 常量
    tavern_events: shimConstants.tavern_events,
    iframe_events: shimConstants.iframe_events,
    // 事件系统
    eventOn: shimEvent.eventOn,
    eventOnce: shimEvent.eventOnce,
    eventEmit: shimEvent.eventEmit,
    eventEmitAndWait: shimEvent.eventEmitAndWait,
    eventRemoveListener: shimEvent.eventRemoveListener,
    eventMakeLast: shimEvent.eventMakeLast,
    eventMakeFirst: shimEvent.eventMakeFirst,
    eventClearEvent: shimEvent.eventClearEvent,
    eventClearListener: shimEvent.eventClearListener,
    eventClearAll: shimEvent.eventClearAll,
    // 变量系统
    getVariables: shimVariable.getVariables,
    getAllVariables: shimVariable.getAllVariables,
    replaceVariables: shimVariable.replaceVariables,
    updateVariablesWith: shimVariable.updateVariablesWith,
    deleteVariable: shimVariable.deleteVariable,
    insertOrAssignVariables: shimVariable.insertOrAssignVariables,
    insertVariables: shimVariable.insertVariables,
    // 聊天消息
    getChatMessages: shimChat.getChatMessages,
    setChatMessages: shimChat.setChatMessages,
    setChatMessage: shimChat.setChatMessage,
    // [v243 BUGFIX A2] JSR 聊天消息写入面（此前缺失 -> 卡脚本 ReferenceError: createChatMessages is not defined）
    createChatMessages: shimChat.createChatMessages,
    deleteChatMessages: shimChat.deleteChatMessages,
    // 世界书
    getLorebookEntries: shimLorebook.getLorebookEntries,
    getCurrentCharPrimaryLorebook: shimLorebook.getCurrentCharPrimaryLorebook,
    getLorebookSettings: shimLorebook.getLorebookSettings,
    setLorebookSettings: shimLorebook.setLorebookSettings,
    getCharWorldbookNames: shimLorebook.getCharWorldbookNames,
    getCharLorebooks: shimLorebook.getCharLorebooks,
    updateWorldbookWith: shimLorebook.updateWorldbookWith,
    // [v214] JSR 4.x 世界书 API（千纱卡开关 UI 依赖；裸全局循环会自动同步）
    getWorldbook: shimLorebook.getWorldbook,
    getWorldbookNames: shimLorebook.getWorldbookNames,
    getChatWorldbookName: shimLorebook.getChatWorldbookName,
    getGlobalWorldbookNames: shimLorebook.getGlobalWorldbookNames,
    // [v243 BUGFIX A3] JSR 世界书写入面（此前缺失 -> 卡片能力探测报「当前酒馆助手不支持世界书接口」）
    createWorldbookEntries: shimLorebook.createWorldbookEntries,
    createLorebookEntry: shimLorebook.createLorebookEntry,
    createLorebookEntries: shimLorebook.createLorebookEntries,
    setLorebookEntries: shimLorebook.setLorebookEntries,
    replaceLorebookEntries: shimLorebook.replaceLorebookEntries,
    replaceWorldbook: shimLorebook.replaceWorldbook,
    updateLorebookEntriesWith: shimLorebook.updateLorebookEntriesWith,
    deleteWorldbookEntries: shimLorebook.deleteWorldbookEntries,
    deleteLorebookEntries: shimLorebook.deleteLorebookEntries,
    deleteLorebookEntry: shimLorebook.deleteLorebookEntry,
    getLorebooks: shimLorebook.getLorebooks,
    createLorebook: shimLorebook.createLorebook,
    createWorldbook: shimLorebook.createWorldbook,
    createOrReplaceWorldbook: shimLorebook.createOrReplaceWorldbook,
    deleteLorebook: shimLorebook.deleteLorebook,
    deleteWorldbook: shimLorebook.deleteWorldbook,
    getChatLorebook: shimLorebook.getChatLorebook,
    setChatLorebook: shimLorebook.setChatLorebook,
    getOrCreateChatWorldbook: shimLorebook.getOrCreateChatWorldbook,
    setCurrentCharLorebooks: shimLorebook.setCurrentCharLorebooks,
    rebindCharWorldbooks: shimLorebook.rebindCharWorldbooks,
    rebindChatWorldbook: shimLorebook.rebindChatWorldbook,
    rebindGlobalWorldbooks: shimLorebook.rebindGlobalWorldbooks,
    // 按钮
    getScriptButtons: shimButton.getScriptButtons,
    replaceScriptButtons: shimButton.replaceScriptButtons,
    updateScriptButtonsWith: shimButton.updateScriptButtonsWith,
    appendInexistentScriptButtons: shimButton.appendInexistentScriptButtons,
    getButtonEvent: shimButton.getButtonEvent,
    // 工具
    getTavernHelperVersion: shimUtil.getTavernHelperVersion,
    getTavernVersion: shimUtil.getTavernVersion,
    triggerSlash: shimUtil.triggerSlash,
    substitudeMacros: shimUtil.substitudeMacros,
    getLastMessageId: shimUtil.getLastMessageId,
    getCurrentMessageId: shimUtil.getCurrentMessageId,
    getScriptId: shimUtil.getScriptId,
    getScriptName: shimUtil.getScriptName,
    getScriptInfo: shimUtil.getScriptInfo,
    replaceScriptInfo: shimUtil.replaceScriptInfo,
    getIframeName: shimUtil.getIframeName,
    reloadIframe: shimUtil.reloadIframe,
    errorCatched: shimUtil.errorCatched,
    initializeGlobal: shimUtil.initializeGlobal,
    waitGlobalInitialized: shimUtil.waitGlobalInitialized,
    registerMacroLike: shimUtil.registerMacroLike,
    unregisterMacroLike: shimUtil.unregisterMacroLike,
    registerVariableSchema: shimUtil.registerVariableSchema,
    // [v243 BUGFIX C2] 扩展安装状态（卡脚本判断「酒馆助手装没装」的第二种常用方式）
    isInstalledExtension: shimUtil.isInstalledExtension,
    getExtensionType: shimUtil.getExtensionType,
    getExtensionStatus: shimUtil.getExtensionStatus,
    getTavernHelperExtensionId: shimUtil.getTavernHelperExtensionId,
    isAdmin: shimUtil.isAdmin,
    installExtension: shimUtil.installExtension,
    uninstallExtension: shimUtil.uninstallExtension,
    reinstallExtension: shimUtil.reinstallExtension,
    updateExtension: shimUtil.updateExtension,
    // [v243 BUGFIX C2] 角色信息 + 常用别名（JSR 同义）
    getCurrentCharacterName: shimUtil.getCurrentCharacterName,
    getCurrentCharacterId: shimUtil.getCurrentCharacterId,
    getCharacterNames: shimUtil.getCharacterNames,
    getCharacterIds: shimUtil.getCharacterIds,
    getCharData: shimUtil.getCharData,
    getMessageId: shimUtil.getMessageId,
    triggerSlashWithResult: shimUtil.triggerSlashWithResult,
    getFrontendVersion: shimUtil.getFrontendVersion,
    updateFrontendVersion: shimUtil.updateFrontendVersion,
    updateTavernHelper: shimUtil.updateTavernHelper,
    // [v241] JSR 注入提示词（酒馆助手）：injectPrompts / uninjectPrompts
    // 语义与差异见 shims/inject-shims.js 头部注释（filter 不支持，已在 DIVERGENCE §H.4 登记）
    injectPrompts: shimInject.injectPrompts,
    uninjectPrompts: shimInject.uninjectPrompts,
    _bind: {},
};
window.TavernHelper = TavernHelper;
window.__mvu_shims__ = TavernHelper;

// 便捷全局（对齐 JSR iframe 里这些函数是裸全局的行为）
for (const key of Object.keys(TavernHelper)) {
    if (!(key in window)) {
        try { window[key] = TavernHelper[key]; } catch (_e) { /* noop */ }
    }
}

// ---------------------------------------------------------------- [v221] 裸全局注入（P0 eventOn 复发收口）
// 真机根因（v221 取证，logs\v221\runtime-20261003.log L1 vs L268）：
//   首次挂载时父窗口 window.TavernHelper 还是模块内建那份（55 keys，顶层有 eventOn）→ 子窗口正常；
//   第三方扩展 JSR 加载后会把父窗口 window.TavernHelper 换成它自己的对象（157 keys，**顶层没有 eventOn**，
//   eventOn 只在 _bind._eventOn 里；见 JS-Slash-Runner/src/function/index.ts:221 + src/iframe/predefine.js:14-18）；
//   此后任何 iframe（force 重装 / 会话切换 / 模型回复后的重建）都拿不到裸全局 eventOn →
//   卡片报「引擎初始化遇到严重错误: eventOn is not defined」。
// 修法：注入时**顶层 key 与 _bind key 都装**，并把模块内建那份（永远有顶层 eventOn）作为兜底源。
const CORE_BARE_GLOBALS = [
    'eventOn', 'eventOnce', 'eventEmit', 'eventEmitAndWait', 'eventRemoveListener',
    'eventMakeLast', 'eventMakeFirst', 'eventClearEvent', 'eventClearListener', 'eventClearAll',
    'getScriptId', 'getScriptName', 'getIframeName',
    'getVariables', 'getAllVariables', 'replaceVariables', 'updateVariablesWith',
    'deleteVariable', 'insertOrAssignVariables', 'insertVariables',
    'triggerSlash', 'substitudeMacros', 'getLastMessageId', 'getCurrentMessageId',
    // [v243 BUGFIX A2/A3] 卡脚本按裸全局调用的写入面（JSR predefine 会把 TavernHelper 顶层键全装成裸全局）
    'createChatMessages', 'deleteChatMessages', 'getChatMessages', 'setChatMessages', 'setChatMessage',
    'createWorldbookEntries', 'createLorebookEntry', 'createLorebookEntries', 'setLorebookEntries',
    'replaceLorebookEntries', 'replaceWorldbook', 'updateLorebookEntriesWith', 'deleteWorldbookEntries',
    'deleteLorebookEntries', 'deleteLorebookEntry', 'getLorebooks', 'getWorldbook', 'getWorldbookNames',
    'getCharWorldbookNames', 'getChatWorldbookName', 'getGlobalWorldbookNames', 'getLorebookEntries',
    'updateWorldbookWith', 'getCurrentCharPrimaryLorebook',
    // [v243 BUGFIX C2] 扩展安装状态 / 角色信息 / 别名（卡脚本按裸全局调用）
    'isInstalledExtension', 'getExtensionType', 'getExtensionStatus', 'getTavernHelperExtensionId',
    'getCurrentCharacterName', 'getCurrentCharacterId', 'getCharacterNames', 'getCharacterIds',
    'getCharData', 'getMessageId', 'triggerSlashWithResult',
    'injectPrompts', 'uninjectPrompts',
    'createOrReplaceWorldbook', 'createWorldbook', 'createLorebook',
    'deleteWorldbook', 'deleteLorebook', 'setChatLorebook',
    'rebindCharWorldbooks', 'rebindChatWorldbook', 'rebindGlobalWorldbooks',
];

// 把 JSR predefine 语义的裸全局装进一个子窗口（脚本 iframe / MVU iframe 共用）。
// sources 按优先级排列；**不覆盖**子窗口已有的同名属性（per-iframe 身份对象不能被打回父窗口身份）。
function installBareGlobals(w, sources) {
    if (!w) return;
    // (1) 顶层 key
    for (const s of sources) {
        if (!s || typeof s !== 'object') continue;
        let keys = [];
        try { keys = Object.keys(s); } catch (_e) { keys = []; }
        for (const k of keys) {
            try { if (!(k in w)) w[k] = s[k]; } catch (_e) { /* noop */ }
        }
    }
    // (2) JSR `_bind`：去掉前导下划线 + bind(子窗口)，只在缺失时装（predefine.js:14-18 同义）
    for (const s of sources) {
        let bind = null;
        try { bind = s && s._bind; } catch (_e) { bind = null; }
        if (!bind || typeof bind !== 'object') continue;
        let bkeys = [];
        try { bkeys = Object.keys(bind); } catch (_e) { bkeys = []; }
        for (const bk of bkeys) {
            const bare = String(bk).replace(/^_/, '');
            if (bare === bk) continue;
            try { if (typeof w[bare] !== 'function') w[bare] = bind[bk].bind(w); } catch (_e) { /* noop */ }
        }
    }
}

// 取「最丰富」的父窗口 TavernHelper（第三方扩展可能往上加了 JSR API 键），退回模块内建那份。
function richestTavernHelper() {
    let src = TavernHelper;
    try {
        const live = window.TavernHelper;
        if (live && typeof live === 'object' && Object.keys(live).length >= Object.keys(TavernHelper).length) src = live;
    } catch (_e) { /* noop */ }
    return src;
}

/**
 * [核心修复 · 核对项3] 当第三方扩展 JS-Slash-Runner (酒馆助手) 加载并执行 `globalThis.TavernHelper = fq()` 时，
 * 会覆盖父窗口 `window.TavernHelper` 及裸全局上的聊天/世界书/变量/斜杠/提示词注入函数（改为走 ST 网页 DOM/HTTP）。
 * 本函数将宿主 RikkaBridge 支撑的核心实现强制重绑回 `window.TavernHelper`、`window.TavernHelper._bind` 与 `window`，
 * 同时完整保留 JSR 扩展挂载的额外 API（如 `audioPlay`、`builtin`、`_th_impl` 等），确保酒馆助手与宿主 100% 协同生效！
 */
function rebindHostTavernHelperShims() {
    try {
        const live = (window.TavernHelper && typeof window.TavernHelper === 'object') ? window.TavernHelper : {};
        for (const name of CORE_BARE_GLOBALS) {
            const hostFn = TavernHelper[name];
            if (typeof hostFn === 'function') {
                try { live[name] = hostFn; } catch (_e) { /* noop */ }
                try {
                    if (live._bind && typeof live._bind === 'object') {
                        live._bind['_' + name] = hostFn;
                    }
                } catch (_e) { /* noop */ }
                try { window[name] = hostFn; } catch (_e) { /* noop */ }
            }
        }
        window.TavernHelper = live;
        window.__mvu_shims__ = live;
    } catch (_e) { /* noop */ }
}

// [v228 S6] 事件 API 的裸全局清单（监听表必须**随 iframe 隔离**）。
// 为什么只隔离事件类：其余裸全局（变量/宏/按钮）都是无状态转发，共享闭包没有串扰问题；
// 而 eventOn/eventOnce/.../eventClearAll 带着「监听表」这个可变状态（shims/event-shims.js）。
const EVENT_API_BARE_GLOBALS = [
    'eventOn', 'eventOnce', 'eventEmit', 'eventEmitAndWait', 'eventRemoveListener',
    'eventMakeLast', 'eventMakeFirst', 'eventClearEvent', 'eventClearListener', 'eventClearAll',
];

/**
 * [v228 S6] 给一个子窗口装**它自己那份**事件 API（v218 起遗留的跨 iframe 串扰收口）。
 *
 * 真机后果（父 realm 共享闭包）：任一脚本 iframe 卸载都会跑 window.eventClearAll()
 * （buildScriptIframeHtml 的 pagehide 钩子）-> 把其它面板 iframe 的监听全清掉；
 * 同 listener 跨窗口复用时 stop() 也会误删别人的 wrapper。
 * 修法 = 每个子窗口一份独立实例（自己的监听表），底层仍共用同一根 eventSource（emit 语义不变）。
 *
 * @param w 子窗口（脚本 iframe / MVU iframe）
 * @param makeApi () => api（调用方传 shimEvent.createEventApi(true)；回归里注入假工厂）
 * @param sharedTH 父窗口那份共享 TavernHelper（同一个对象会污染父 realm -> 必须换成子窗口副本）
 */
function installPerIframeEventApi(w, makeApi, sharedTH) {
    if (!w || typeof makeApi !== 'function') return null;
    var api = null;
    try { api = w.__rikkaEventApi || null; } catch (_e) { api = null; }
    if (!api) {
        try { api = makeApi(); } catch (_e) { api = null; }
        if (!api) return null;
        try { w.__rikkaEventApi = api; } catch (_e) { /* noop */ }
    }
    for (var i = 0; i < EVENT_API_BARE_GLOBALS.length; i++) {
        var name = EVENT_API_BARE_GLOBALS[i];
        try { if (typeof api[name] === 'function') w[name] = api[name]; } catch (_e) { /* noop */ }
    }
    // TavernHelper 顶层：JSR 语义里子窗口的 TavernHelper 是「merge 出来的自己那份」，
    // 但宿主注入在 childKeys===0 时会把父窗口对象**直接赋给**子窗口（同一个引用）-> 先脱钩再装。
    try {
        var th = w.TavernHelper;
        if (!th || typeof th !== 'object') {
            // 子窗口没有 TavernHelper（pushShims 未命中时）-> 建一个本窗口私有空壳，
            // 至少保证 TavernHelper.eventOn 也走 per-iframe 表（与裸全局同源）。
            th = {};
            w.TavernHelper = th;
        }
        if (th && typeof th === 'object') {
            if (th === sharedTH) {
                var copy = {};
                for (var k in th) { try { copy[k] = th[k]; } catch (_e) { /* noop */ } }
                w.TavernHelper = copy;
                th = copy;
            }
            for (var j = 0; j < EVENT_API_BARE_GLOBALS.length; j++) {
                var nm = EVENT_API_BARE_GLOBALS[j];
                try { if (typeof api[nm] === 'function') th[nm] = api[nm]; } catch (_e) { /* noop */ }
            }
        }
    } catch (_e) { /* noop */ }
    try { if (typeof w.eventOn === 'function') w.__rikkaHostInjected = true; } catch (_e) { /* noop */ }
    return api;
}

// 往子窗口注入：最丰富源优先 + 模块内建补缺 + 核心事件 API 最后兜底（保证 eventOn 一定在）。
function pushRuntimeGlobals(w) {
    if (!w) return;
    const src = richestTavernHelper();
    installBareGlobals(w, [src, TavernHelper]);
    // [v244 BUGFIX D1] 核心 API 必须**裸全局 + TavernHelper 两个位置都在**。
    // 卡片 fn(name) 会依次探 window / window.TavernHelper / parent / parent.TavernHelper；
    // 第三方扩展（JSR）把父窗口 TavernHelper 换成它自己那份后，子窗口复制到的对象可能缺键
    // （或键在、值不是函数）→ 卡片判「未检测到 createChatMessages / triggerSlash 接口」。
    // 这里用宿主自带实现（模块内建的 TavernHelper）补齐**两个位置**；需要补键时先脱钩，
    // 绝不就地改父窗口那份共享对象。
    for (const name of CORE_BARE_GLOBALS) {
        try {
            if (typeof TavernHelper[name] !== 'function') continue;
            if (typeof w[name] !== 'function') w[name] = TavernHelper[name];
            let th = null;
            try { th = w.TavernHelper; } catch (_e) { th = null; }
            if (!th || typeof th !== 'object') continue;
            if (typeof th[name] === 'function') continue;
            let shared = false;
            try { shared = (th === TavernHelper) || (th === src) || (typeof window !== 'undefined' && th === window.TavernHelper); } catch (_e) { shared = true; }
            if (shared) {
                const copy = {};
                for (const k in th) { try { copy[k] = th[k]; } catch (_e) { /* noop */ } }
                try { w.TavernHelper = copy; } catch (_e) { /* noop */ }
                th = copy;
            }
            th[name] = TavernHelper[name];
        } catch (_e) { /* noop */ }
    }
    try { if (!w.__mvu_shims__) w.__mvu_shims__ = src; } catch (_e) { /* noop */ }
    let childKeys = 0;
    try { childKeys = (w.TavernHelper && typeof w.TavernHelper === 'object') ? Object.keys(w.TavernHelper).length : 0; } catch (_e) { childKeys = 0; }
    if (childKeys === 0) { try { w.TavernHelper = src; } catch (_e) { /* noop */ } }
    // [v228 S6] 最后一步：把事件 API 换成**本子窗口私有**的实例（自己的监听表）。
    // 放在最后 -> 覆盖上面三条路（顶层 key / _bind 绑定 / CORE 兜底）装进来的父 realm 共享函数。
    try { installPerIframeEventApi(w, function () { return shimEvent.createEventApi(true); }, src); } catch (_e) { /* noop */ }
    try { if (typeof w.eventOn === 'function') w.__rikkaHostInjected = true; } catch (_e) { /* noop */ }
}

// [v221] 注入守护（H1）：iframe 重建 / 导航会换掉子窗口 global，一次性 4 次注入不够 →
// 短轮询补注，直到裸 eventOn 就位或超时（2.5s，与 host-verify 同一时刻）。
function guardChildGlobals(w) {
    let ticks = 0;
    const timer = setInterval(() => {
        ticks++;
        let ok = false;
        try { ok = !!(w && typeof w.eventOn === 'function'); } catch (_e) { ok = false; }
        if (ok) { clearInterval(timer); return; }
        pushRuntimeGlobals(w);
        if (ticks >= 25) clearInterval(timer);
    }, 100);
    return timer;
}

// 全局库（供 iframe 拷贝 / MVU 使用）
// 与 JSR 语义逐字对齐：globalThis.z = import * as zod（命名空间本体）。
// 命名空间同时具备 z.object / z.string（卡脚本直接用法）与 z.z.ZodObject（mvu_zod.js 用法），
// 绝不能只赋值 zodModule.z —— 否则 mvu_zod.js 的 r.z.ZodObject 会炸。
window.z = zodModule;
window.YAML = (yamlModule && yamlModule.default) ? yamlModule.default : yamlModule;
window.klona = window.klona || ((obj) => {
    if (typeof structuredClone === 'function') {
        try { return structuredClone(obj); } catch (_e) { /* fallthrough */ }
    }
    return deepClone(obj);
});

// ---------------------------------------------------------------- 事件桥（Kotlin → JS）
// ---------------------------------------------------------------- ST 模板渲染 API（批次十七）
// ST 的第三方扩展普遍这么用：
//   $('#extensions_settings').append(await renderExtensionTemplateAsync('third-party/<folder>', 'settings'));
// 路径规则：/<folder>/<templateId>.html，由宿主资产路由伺服（第三方扩展目录已可直读）。
// 模板里的 {{key}} 用传进来的 data 做最简替换（ST 用 Handlebars；扩展的 settings 模板绝大多数是纯 HTML）。
const __rikkaTemplateCache = new Map();

function __rikkaInterpolateTemplate(rawHtml, data) {
    let html = String(rawHtml == null ? '' : rawHtml).replace(/^(?:\s|<!--[\s\S]*?-->)+/, '');
    if (data && typeof data === 'object') {
        html = html.replace(/\{\{\{\s*([\w.$]+)\s*\}\}\}/g, (m, k) => (k in data ? String(data[k]) : m));
        html = html.replace(/\{\{\s*([\w.$]+)\s*\}\}/g, (m, k) => (k in data ? String(data[k]) : m));
    }
    return html;
}

function __rikkaResolveTemplateUrl(pathOrTemplateId, nameOrData) {
    if (typeof nameOrData !== 'string') {
        const tplId = String(pathOrTemplateId == null ? '' : pathOrTemplateId).replace(/^\/+/, '');
        if (!tplId) return '';
        const rawUrl = tplId.endsWith('.html') ? ('/' + tplId) : (`/scripts/templates/${tplId}.html`);
        return rawUrl.replace(/\/{2,}/g, '/');
    }
    const rel = String(pathOrTemplateId == null ? '' : pathOrTemplateId).replace(/^\/+/, '');
    const tpl = String(nameOrData == null ? '' : nameOrData).replace(/^\/+/, '').replace(/\.html$/i, '');
    return ('/' + rel + '/' + tpl + '.html').replace(/\/{2,}/g, '/');
}

async function renderTemplateAsync(path, name, data) {
    const isOneArg = typeof name !== 'string';
    const templateData = isOneArg ? (name && typeof name === 'object' ? name : {}) : data;
    const url = __rikkaResolveTemplateUrl(path, name);
    if (!url) return '';
    let html = '';
    try {
        const res = await fetch(url, { cache: 'no-store' });
        if (!res.ok) throw new Error('http=' + res.status);
        html = await res.text();
        __rikkaTemplateCache.set(url, html);
    } catch (e) {
        if (__rikkaTemplateCache.has(url)) {
            html = __rikkaTemplateCache.get(url);
        } else {
            try { console.warn(`${LOG_PREFIX} renderTemplateAsync failed: ${url}`, e); } catch (_e) { /* noop */ }
            return '';
        }
    }
    return __rikkaInterpolateTemplate(html, templateData);
}

function renderTemplate(path, name, data) {
    const isOneArg = typeof name !== 'string';
    const templateData = isOneArg ? (name && typeof name === 'object' ? name : {}) : data;
    const url = __rikkaResolveTemplateUrl(path, name);
    if (!url) return '';
    if (__rikkaTemplateCache.has(url)) {
        return __rikkaInterpolateTemplate(__rikkaTemplateCache.get(url), templateData);
    }
    try {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', url, false);
        xhr.send();
        if (xhr.status >= 200 && xhr.status < 300) {
            const html = xhr.responseText || '';
            __rikkaTemplateCache.set(url, html);
            return __rikkaInterpolateTemplate(html, templateData);
        }
    } catch (_e) { /* noop */ }
    return '';
}

async function renderExtensionTemplateAsync(extensionName, templateId, templateData, _sanitize) {
    const raw = String(extensionName == null ? '' : extensionName).replace(/^\/+/, '');
    const folder = raw.startsWith('third-party/') || raw.startsWith('scripts/extensions/third-party/')
        ? raw
        : `third-party/${raw}`;
    return renderTemplateAsync(folder, String(templateId == null ? '' : templateId), templateData);
}

function renderExtensionTemplate(extensionName, templateId, templateData, _sanitize) {
    const raw = String(extensionName == null ? '' : extensionName).replace(/^\/+/, '');
    const folder = raw.startsWith('third-party/') || raw.startsWith('scripts/extensions/third-party/')
        ? raw
        : `third-party/${raw}`;
    return renderTemplate(folder, String(templateId == null ? '' : templateId), templateData);
}

try { window.renderTemplate = renderTemplate; } catch (_e) { /* noop */ }
try { window.renderTemplateAsync = renderTemplateAsync; } catch (_e) { /* noop */ }
try { window.renderExtensionTemplate = renderExtensionTemplate; } catch (_e) { /* noop */ }
try { window.renderExtensionTemplateAsync = renderExtensionTemplateAsync; } catch (_e) { /* noop */ }

// ============================================================
// [v211] 生成管线钩子（ST CHAT_COMPLETION_SETTINGS_READY / GENERATE_AFTER_DATA）
// ============================================================
// ST 契约（openai.js:1616-1623）：prompt 组装完成后
//   const chat = chatCompletion.getChat();            // [{role, content, ...}]
//   const eventData = { chat, dryRun };
//   await eventSource.emit(CHAT_COMPLETION_PROMPT_READY, eventData);   // 扩展就地改 eventData.chat
//   return [chat, ...];                               // ← 改写后的结果继续用
// 而 ST-PT handler.ts:979-980 实际监听的是【GENERATE_AFTER_DATA（prompt 路径）】与
//   【CHAT_COMPLETION_SETTINGS_READY（chat completion 路径，data.messages = await processGenerateAfter(data.messages)）】
// MVU bundle 里 5 处 CHAT_COMPLETION_SETTINGS_READY（含 eventMakeLast —— 要求排在最后拿最终结果）。
// 所以这里只需实现 CHAT_COMPLETION_SETTINGS_READY，ST-PT 与 MVU 就都能参与生成。
//
// Kotlin 调用：TavernRuntimeManager.runGenerationHooks(messagesJson)
// 返回：JSON 字符串（改写后的 messages 数组）或 null（无监听器/超时 → 调用方保留原文，零开销直通）。
const loadedExtensionHooks = {};
const loadedInterceptors = [];

async function invokeExtensionHook(folder, hookName) {
    const entry = loadedExtensionHooks[folder];
    if (!entry || !entry.hooks) return false;
    const fnName = entry.hooks[hookName];
    if (!fnName) return false;
    const fn = (entry.mod && typeof entry.mod[fnName] === 'function')
        ? entry.mod[fnName]
        : (typeof window[fnName] === 'function' ? window[fnName] : null);
    if (typeof fn !== 'function') return false;
    try {
        await fn();
        return true;
    } catch (e) {
        console.error('[RikkaTavern] extension lifecycle hook error:', folder, hookName, fnName, e);
        return false;
    }
}

window.__rikkaInvokeExtensionLifecycleHook = async function (folder, hookName) {
    return await invokeExtensionHook(String(folder || ''), String(hookName || ''));
};

async function runGenerationInterceptors(chatMessages, contextSize, type) {
    if (!Array.isArray(loadedInterceptors) || loadedInterceptors.length === 0) {
        return { aborted: false };
    }
    let aborted = false;
    const abortFn = function (reason) {
        aborted = true;
        if (reason) {
            try { callBridge("log", "info", "[gen-hooks] generate_interceptor aborted: " + String(reason)); } catch (_e) { /* noop */ }
        }
    };
    for (let i = 0; i < loadedInterceptors.length; i++) {
        const item = loadedInterceptors[i];
        const fn = (item.mod && typeof item.mod[item.name] === 'function')
            ? item.mod[item.name]
            : (typeof window[item.name] === 'function' ? window[item.name] : null);
        if (typeof fn !== 'function') continue;
        try {
            await fn(chatMessages, contextSize, abortFn, type || 'normal');
            if (aborted) break;
        } catch (err) {
            console.error('[RikkaTavern] generate_interceptor error (' + item.folder + ':' + item.name + '):', err);
        }
    }
    return { aborted };
}

window.__rikkaGenerationAbortEpoch = 0;
window.__rikkaAbortActiveGeneration = function __rikkaAbortActiveGeneration(reason) {
    try {
        window.__rikkaGenerationAbortEpoch = (window.__rikkaGenerationAbortEpoch || 0) + 1;
        try {
            if (window.SillyTavern && typeof window.SillyTavern.getContext === 'function') {
                const ctx = window.SillyTavern.getContext();
                if (ctx && ctx.externalAbortController && typeof ctx.externalAbortController.abort === 'function') {
                    ctx.externalAbortController.abort(reason || 'generation_stopped');
                }
            }
        } catch (_eAc) { /* noop */ }
        if (window.__rikkaApiStreams) {
            Object.keys(window.__rikkaApiStreams).forEach((reqId) => {
                try {
                    const st = window.__rikkaApiStreams[reqId];
                    if (st) {
                        st.finished = true;
                        st.status = 'ABORTED';
                        if (typeof st.wake === 'function') st.wake();
                    }
                } catch (_e) { /* noop */ }
            });
        }
        if (window.__rikkaProxyPending) {
            Object.keys(window.__rikkaProxyPending).forEach((reqId) => {
                try {
                    const entry = window.__rikkaProxyPending[reqId];
                    delete window.__rikkaProxyPending[reqId];
                    if (entry && typeof entry.resolve === 'function') {
                        entry.resolve({ aborted: true });
                    }
                } catch (_eProxy) { /* noop */ }
            });
        }
        if (window.__rikkaImgGenPending) {
            Object.keys(window.__rikkaImgGenPending).forEach((reqId) => {
                try {
                    const entry = window.__rikkaImgGenPending[reqId];
                    delete window.__rikkaImgGenPending[reqId];
                    if (entry && typeof entry.resolve === 'function') {
                        entry.resolve({ aborted: true });
                    }
                } catch (_eImg) { /* noop */ }
            });
        }
        try { eventSource.emit('generation_stopped', { reason: String(reason || 'manual') }); } catch (_e) { /* noop */ }
        return true;
    } catch (_e) {
        return false;
    }
};

// [P0 修复] 宿主 runGenerationHooks 用 CompletableDeferred 等这个回调，超时 15s。
// 旧实现的所有「提前 return」分支都**不回调** → 每次生成在发出请求前固定空转满 15 秒，
// 用户看到的就是「转圈、上游收不到」。这里统一用 settle 保证任何分支都恰好回调一次。
window.__rikkaRunGenerationHooks = async function __rikkaRunGenerationHooks(seq, messagesJson) {
    const startEpoch = window.__rikkaGenerationAbortEpoch || 0;
    let settled = false;
    const settle = (value) => {
        if (settled) return;
        settled = true;
        try {
            if (window.RikkaBridge && typeof window.RikkaBridge.onGenerationHooksResult === 'function') {
                window.RikkaBridge.onGenerationHooksResult(seq, JSON.stringify(value == null ? null : value));
            }
        } catch (_e) { /* noop */ }
    };
    try {
        const parsedInput = JSON.parse(messagesJson || '[]');
        const rawMessages = Array.isArray(parsedInput)
            ? parsedInput
            : (parsedInput && Array.isArray(parsedInput.messages) ? parsedInput.messages : null);
        if (!Array.isArray(rawMessages)) {
            settle(null);
            return null;
        }

        let activeMessages = rawMessages.map((m) => {
            const contentStr = m && typeof m.content === 'string' ? m.content : String((m && m.mes) ?? '');
            const mesStr = m && typeof m.mes === 'string' ? m.mes : contentStr;
            const roleStr = m && m.role ? String(m.role) : 'user';
            const out = Object.assign({}, m, {
                role: roleStr,
                content: contentStr,
                mes: mesStr,
                _origContent: contentStr,
                _origMes: mesStr,
                is_user: m && typeof m.is_user === 'boolean' ? m.is_user : (roleStr === 'user'),
                is_system: m && typeof m.is_system === 'boolean' ? m.is_system : (roleStr === 'system'),
            });
            return out;
        });

        const isEpochAborted = () => (window.__rikkaGenerationAbortEpoch || 0) !== startEpoch;
        let aborted = isEpochAborted();
        if (!aborted) {
            const interceptRes = await runGenerationInterceptors(activeMessages, 32768, 'normal');
            if ((interceptRes && interceptRes.aborted) || isEpochAborted()) {
                aborted = true;
            }
        }

        // Sync .mes <-> .content after generate_interceptor (e.g., ST-iPhonie stIphonieInterceptor mutates .mes)
        for (let i = 0; i < activeMessages.length; i++) {
            const item = activeMessages[i];
            if (!item || typeof item !== 'object') continue;
            if (typeof item.mes === 'string' && item.mes !== item._origMes) {
                item.content = item.mes;
                item._origContent = item.content;
                item._origMes = item.mes;
            } else if (typeof item.content === 'string' && item.content !== item._origContent) {
                item.mes = item.content;
                item._origContent = item.content;
                item._origMes = item.mes;
            }
        }

        if (!aborted) {
            const eventData = {
                type: '',
                chatId: state.chatId,
                dryRun: false,
            };
            Object.defineProperty(eventData, 'messages', {
                enumerable: true,
                configurable: true,
                get() { return activeMessages; },
                set(v) { if (Array.isArray(v)) activeMessages = v; },
            });
            Object.defineProperty(eventData, 'chat', {
                enumerable: true,
                configurable: true,
                get() { return activeMessages; },
                set(v) { if (Array.isArray(v)) activeMessages = v; },
            });
            Object.defineProperty(eventData, 'prompt', {
                enumerable: true,
                configurable: true,
                get() { return activeMessages; },
                set(v) { if (Array.isArray(v)) activeMessages = v; },
            });

            if (!isEpochAborted()) {
                try {
                    try { callBridge("log", "info", "[event] GENERATION_AFTER_COMMANDS " + JSON.stringify({ args: ["normal", {}, false] })); } catch (_eLog) { /* noop */ }
                    await eventSource.emitAndWait(event_types.GENERATION_AFTER_COMMANDS, eventData);
                } catch (eAfter) {
                    console.error('[RikkaTavern] GENERATION_AFTER_COMMANDS handler failed:', (eAfter && eAfter.stack) || eAfter);
                }
            }

            if (!isEpochAborted()) {
                try {
                    try { callBridge("log", "info", "[event] GENERATE_BEFORE_COMBINE_PROMPTS " + JSON.stringify({ dryRun: false })); } catch (_eLog) { /* noop */ }
                    await eventSource.emitAndWait(event_types.GENERATE_BEFORE_COMBINE_PROMPTS, eventData);
                } catch (eBeforeCombine) {
                    console.error('[RikkaTavern] GENERATE_BEFORE_COMBINE_PROMPTS handler failed:', (eBeforeCombine && eBeforeCombine.stack) || eBeforeCombine);
                }
            }

            if (!isEpochAborted()) {
                try {
                    try { callBridge("log", "info", "[event] GENERATE_AFTER_DATA " + JSON.stringify({ dryRun: false })); } catch (_eLog) { /* noop */ }
                    await eventSource.emitAndWait(event_types.GENERATE_AFTER_DATA, eventData, false);
                } catch (eAfterData) {
                    console.error('[RikkaTavern] GENERATE_AFTER_DATA handler failed:', (eAfterData && eAfterData.stack) || eAfterData);
                }
            }

            // Official SillyTavern 1.19.0 order: CHAT_COMPLETION_PROMPT_READY (openai.js:1619) fires BEFORE CHAT_COMPLETION_SETTINGS_READY (openai.js:3146)
            if (!isEpochAborted()) {
                try {
                    await eventSource.emitAndWait(event_types.CHAT_COMPLETION_PROMPT_READY, eventData);
                } catch (ePrompt) {
                    console.error("[RikkaTavern] prompt-ready hook handler failed:", (ePrompt && ePrompt.stack) || ePrompt);
                }
            }

            if (!isEpochAborted()) {
                try {
                    await eventSource.emitAndWait(event_types.CHAT_COMPLETION_SETTINGS_READY, eventData);
                } catch (eSettings) {
                    console.error('[RikkaTavern] generation hook handler failed:', (eSettings && eSettings.stack) || eSettings);
                }
            }

            if (!isEpochAborted()) {
                try {
                    const afterData = {
                        prompt: JSON.stringify(activeMessages),
                        messages: activeMessages,
                        chat: activeMessages,
                        type: eventData.type || '',
                        chatId: eventData.chatId,
                        dryRun: false,
                    };
                    try { callBridge("log", "info", "[event] GENERATE_AFTER_COMBINE_PROMPTS " + JSON.stringify({ dryRun: false })); } catch (_eLog) { /* noop */ }
                    await eventSource.emitAndWait(event_types.GENERATE_AFTER_COMBINE_PROMPTS, afterData);
                if (typeof afterData.prompt === 'string' && Array.isArray(activeMessages) &&
                    afterData.prompt !== JSON.stringify(activeMessages)) {
                    try {
                        const reparsed = JSON.parse(afterData.prompt);
                        if (Array.isArray(reparsed) && reparsed.length > 0) activeMessages = reparsed;
                    } catch (_eParse) { /* ignore non-JSON prompt */ }
                } else if (Array.isArray(afterData.messages)) {
                    activeMessages = afterData.messages;
                }
                } catch (eAfterCombine) {
                    console.error('[RikkaTavern] GENERATE_AFTER_COMBINE_PROMPTS handler failed:', (eAfterCombine && eAfterCombine.stack) || eAfterCombine);
                }
            }
            if (isEpochAborted()) {
                aborted = true;
            }
        }

        const normalized = (Array.isArray(activeMessages) ? activeMessages : []).map((m) => {
            if (!m || typeof m !== 'object') {
                return { role: 'user', content: String(m ?? '') };
            }
            const role = String(m.role || (m.is_system ? 'system' : (m.is_user === false ? 'assistant' : 'user')));
            let content = '';
            if (typeof m.content === 'string' && typeof m.mes === 'string' && m.mes !== m.content) {
                content = (m.mes !== m._origMes) ? m.mes : m.content;
            } else {
                content = typeof m.content === 'string' ? m.content : String(m.mes ?? '');
            }
            const outObj = { role, content };
            if (m.name) outObj.name = String(m.name);
            return outObj;
        });

        const json = aborted ? JSON.stringify({ messages: normalized, aborted: true }) : JSON.stringify(normalized);
        // settle() 内部会再做一次 JSON.stringify —— 这是**刻意**的，用来与旧实现保持一致的
        // 传输形状：旧代码是 `onGenerationHooksResult(seq, JSON.stringify(json))`，
        // 而 json 本身已是字符串，于是 JS 侧发出的是「再包一层的字符串字面量」，
        // Kotlin 侧用 `jsonPrimitive.content` 恰好剥掉那一层，拿回真正的 JSON 文本。
        // 这里沿用同一形状，避免改变宿主解析行为。
        settle(json);
        return json;
    } catch (e) {
        console.error('[RikkaTavern] __rikkaRunGenerationHooks failed:', (e && e.stack) || e);
        // 异常分支也必须回调，否则宿主固定卡满 15s 超时。
        settle(null);
        return null;
    }
};

window.__rikkaEmit = function __rikkaEmit(name, payload) {
    window.__rikkaIncomingHostEvent = true;
    setTimeout(() => { window.__rikkaIncomingHostEvent = false; }, 0);
    try {
        if (['message_received', 'message_sent', 'message_updated', 'user_message_rendered',
             'character_message_rendered', 'message_swiped', 'active_conversation_changed'].includes(name)) {
            refreshAll();
        }
        if (name === 'active_conversation_changed') {
            // [v214] 会话切换：**强制**重装卡脚本（force=true）。
            // 真机取证：会话数据（assistantId）可能晚于事件到达 → 签名比对误判「没变化」而跳过
            // → 新卡的 MVU/脚本永不装载（面板 vars 恒空）。
            // 随后按需重启 MVU（等旧脚本 iframe 的 pagehide 清理完 parent.Mvu 再判定）。
            try {
                Promise.resolve().then(async () => {
                    try {
                        await loadTavernScripts(true);
                    } catch (e) { safeWarn('loadTavernScripts(force) failed', e); }
                    setTimeout(() => {
                        try { if (!window.Mvu) loadMvu(); } catch (_e) { /* noop */ }
                    }, 700);
                    setTimeout(notifyChatReady, 300);
                });
            } catch (_e) { /* noop */ }
        }
        if (name === 'settings_updated') {
            try {
                const prevIphonieJson = JSON.stringify((state.thirdPartySettings && state.thirdPartySettings.stTtsDialogue) || null);
                thirdPartySettingsDirty = false;
                refreshSettings();
                pullThirdPartySettings(true);
                const nextIphonieJson = JSON.stringify((state.thirdPartySettings && state.thirdPartySettings.stTtsDialogue) || null);
                if (prevIphonieJson !== nextIphonieJson && loadedExtensionHooks) {
                    const ipKey = Object.keys(loadedExtensionHooks).find((k) => k.toLowerCase().includes('iphonie'));
                    const ipEntry = ipKey ? loadedExtensionHooks[ipKey] : null;
                    const ipMod = ipEntry ? (ipEntry.mod || ipEntry) : null;
                    const panelEl = document.getElementById('sttts-panel');
                    const isPanelOpen = Boolean(panelEl && (panelEl.open || panelEl.hasAttribute('open') || panelEl.style.display === 'block')) || window.__rikkaCurrentFloatingTarget === 'iphonie';
                    if (ipMod && typeof ipMod.disable === 'function' && typeof ipMod.enable === 'function' && !isPanelOpen && window.__rikkaCurrentFloatingTarget !== 'iphonie') {
                        Promise.resolve().then(async () => {
                            try {
                                await ipMod.disable();
                                await ipMod.enable();
                            } catch (_eIp) { /* noop */ }
                        });
                    }
                }
            } catch (_eSet) { /* noop */ }
        }
        if (name === 'third_party_extensions_changed') {
            // 第三方扩展安装/启停/删除：重装扩展资源（已装载 URL 不重复执行）
            try {
                Promise.resolve().then(() => loadThirdPartyExtensions(true));
            } catch (_e) { /* noop */ }
        }
        let data = payload;
        if (typeof payload === 'string') {
            try { data = JSON.parse(payload); } catch (_e) { data = payload; }
        }
        if (data && typeof data === 'object' && Array.isArray(data.args)) {
            return eventSource.emit(name, ...data.args);
        }
        // [batch17] 空对象过去会被吞成「无参 emit」，导致监听方拿到 undefined 而崩（MVU / ST-Prompt-Template 的 chat_completion_prompt_ready 监听器就是这样炸的）。载荷一律照传。
        if (data && typeof data === 'object' && Object.keys(data).length === 0) return eventSource.emit(name, data);
        return eventSource.emit(name, data);
    } catch (e) {
        safeWarn('__rikkaEmit failed', name, e);
    }
};

// JS 侧事件上抛（script_ready / js_result / mvu_ready …）
function emitToHost(name, payload) {
    try { callBridge('emitEvent', name, JSON.stringify(payload ?? {})); } catch (_e) { /* noop */ }
}

// JS → ST 事件（让 JS-Slash-Runner 风格脚本的 eventEmit 同时能到达宿主可见的日志）
eventSource.on(event_types.MESSAGE_RECEIVED, (id) => {
    try { callBridge('log', 'info', `[event] message_received id=${id}`); } catch (_e) { /* noop */ }
});

// ---------------------------------------------------------------- toastr 转发（离屏 WebView 中弹窗不可见 → 桥接日志）
(function patchToastr() {
    const t = window.toastr;
    if (!t || t.__rikkaPatched) return;
    for (const level of ['info', 'success', 'warning', 'error']) {
        const orig = t[level];
        if (typeof orig !== 'function') continue;
        t[level] = function (...args) {
            try { emitToHost('toast', { level, args: args.map((a) => String(a).slice(0, 300)) }); } catch (_e) { /* noop */ }
            return orig.apply(t, args);
        };
    }
    t.__rikkaPatched = true;
})();

// 供脚本 iframe 的 console/toastr 桥接（log.js 语义）
window.__rikkaLog = function (level, message) {
    try { callBridge('log', String(level || 'log'), String(message || '')); } catch (_e) { /* noop */ }
};

// [v218] iframe → 宿主的 postMessage 通道。
// 背景：TH-script iframe 里的 console 桥走 `window.parent.__rikkaLog`（跨窗口属性读），
// 真机上这条通道**从来没在日志里出现过**（MVU iframe 的 postMessage 通道却一直正常），
// 于是「predefine 抛错」这类问题完全静默 —— eventOn P0 查不下去就是这个盲区。
window.addEventListener('message', (ev) => {
    try {
        const d = ev && ev.data;
        if (!d || typeof d !== 'object') return;
        if (d.type === 'th-iframe-log') {
            callBridge('log', String(d.level || 'debug'), String(d.text || ''));
            return;
        }
        if (d.type === 'th-script-diag') {
            callBridge('log', 'debug', '[TH-script] diag ' + JSON.stringify(d.diag || {}));
        }
    } catch (_e) { /* noop */ }
});

// ---------------------------------------------------------------- 脚本执行入口（/js）
window.__rikkaRunScript = async function __rikkaRunScript(code) {
    refreshAll();
    let result, error = null;
    try {
        try {
            const exprFn = new Function('"use strict"; return (' + code + '\n);');
            result = await exprFn();
        } catch (_e1) {
            const stmtFn = new Function('"use strict"; return (async () => {\n' + code + '\n})();');
            result = await stmtFn();
        }
    } catch (e) {
        error = e;
    }
    let payload;
    if (error) {
        payload = { ok: false, error: String((error && error.stack) || error) };
    } else {
        let v;
        try { v = JSON.stringify(result); if (v === undefined) v = 'undefined'; }
        catch (_e) { v = String(result); }
        payload = { ok: true, value: v };
    }
    emitToHost('js_result', payload);
    return payload;
};

// ---------------------------------------------------------------- EJS 模板渲染（ST-Prompt-Template 语义）
/** 构建 EJS 渲染上下文（ST-Prompt-Template prepareContext 子集映射）。 */
function buildEjsContext() {
    const pickLast = (pred) => {
        try {
            for (let i = state.chat.length - 1; i >= 0; i--) {
                const m = state.chat[i];
                if (m && pred(m)) return m;
            }
        } catch (_e) { /* noop */ }
        return null;
    };
    return ejsRender.createEjsApi({
        userName: state.name1,
        charName: state.name2,
        assistantName: state.name2,
        chatId: state.chatId,
        characterId: state.characterId,
        get lastMessageId() { return Math.max(0, state.chat.length - 1); },
        get lastUserMessage() {
            const m = pickLast(x => x.is_user);
            return m ? String(m.mes || '') : '';
        },
        get lastCharMessage() {
            const m = pickLast(x => !x.is_user && !x.is_system);
            return m ? String(m.mes || '') : '';
        },
    });
}

/**
 * EJS 批量渲染入口（Kotlin → JS）：
 * textsJson 为待渲染文本数组；逐条渲染（仅含 `<%` 的才处理），
 * 完成后经 RikkaBridge.onEjsResult(seq, json) 回传（杜绝轮询）。
 */
window.__rikkaEvalEjsBatch = async function __rikkaEvalEjsBatch(seq, textsJson) {
    let texts = [];
    try { texts = JSON.parse(textsJson); } catch (_e) { texts = []; }
    if (!Array.isArray(texts)) texts = [];
    const out = [];
    try {
        refreshAll();
        const data = buildEjsContext();
        for (const t of texts) {
            const src = (t === null || t === undefined) ? '' : String(t);
            if (!ejsRender.containsEjs(src)) { out.push(src); continue; }
            try {
                out.push(await ejsRender.renderEjsText(src, data));
            } catch (e) {
                const brief = String((e && e.message) || e).slice(0, 300);
                safeWarn(`[ejs] render failed: ${brief}`);
                try { callBridge('log', 'error', `[ejs] render failed: ${brief}`); } catch (_e2) { /* noop */ }
                out.push(src); // 失败保留原文，不破坏 prompt
            }
        }
    } catch (e) {
        // [P0 修复] 旧实现没有这层兜底：refreshAll/buildEjsContext 抛错时宿主收不到
        // onEjsResult，会白等满 10 秒超时。
        safeWarn('[ejs] batch aborted: ' + String((e && e.message) || e));
        while (out.length < texts.length) out.push(String(texts[out.length] ?? ''));
    }
    try { callBridge('onEjsResult', seq, JSON.stringify(out)); } catch (_e) { /* noop */ }
    return out;
};

// ---------------------------------------------------------------- [v234 S2] 函数宏批量执行（Kotlin → JS）
// Kotlin 的 MacrosMacroPass 把文本里的 {{fnmacro}} 哨兵替换请求发回 JS：
// __rikkaEvalMacrosBatch(seq, requestsJson)，requestsJson = [{id, name, nonce}, ...]
// 逐项调用 MacrosParser 注册的函数宏（ST 语义 fn(nonce) → sanitizeMacroValue），
// 完成后经 RikkaBridge.onMacrosResult(seq, json) 回传 [{id, value}, ...]。
window.__rikkaEvalMacrosBatch = async function __rikkaEvalMacrosBatch(seq, requestsJson) {
    const results = [];
    let requests = [];
    try { requests = JSON.parse(requestsJson); } catch (_e) { requests = []; }
    if (!Array.isArray(requests)) requests = [];
    try {
        const parserCtor = (window.SillyTavern && window.SillyTavern.getContext
            && window.SillyTavern.getContext().MacrosParser) || null;
        const mod = (typeof MacrosParserModule !== 'undefined') ? MacrosParserModule : null;
        for (const req of requests) {
            let value = '';
            try {
                let fn = null;
                if (parserCtor && parserCtor.macros instanceof Map) {
                    fn = parserCtor.macros.get(req.name);
                }
                if (typeof fn !== 'function' && mod && mod.MacrosParser && mod.MacrosParser.macros instanceof Map) {
                    fn = mod.MacrosParser.macros.get(req.name);
                }
                if (typeof fn !== 'function' && stMacroRegistry instanceof Map) {
                    fn = stMacroRegistry.get(req.name);
                }
                if (typeof fn === 'function') {
                    const raw = fn(req.nonce);
                    value = (raw == null) ? '' : (typeof raw === 'object' ? JSON.stringify(raw) : String(raw));
                }
            } catch (e) {
                try { callBridge('log', 'warn', '[macros] function macro ' + req.name + ' threw: ' + String(e && e.message || e)); } catch (_e2) { /* noop */ }
                value = '';
            }
            results.push({ id: req.id, value: value });
        }
    } catch (_e) { /* noop */ }
    try { callBridge('onMacrosResult', seq, JSON.stringify(results)); } catch (_e) { /* noop */ }
    return results;
};

// ---------------------------------------------------------------- 卡脚本装载（TH-script iframe，JSR 兼容）
const scriptIframes = new Map();      // scriptId → HTMLIFrameElement
let lastScriptsSignature = '';
let mvuFromScripts = false;           // 卡脚本自带 MVU 加载器（MVU-offline）时置 true

function scriptEnabled(v) {
    if (v === undefined || v === null) return true;
    if (typeof v === 'boolean') return v;
    if (typeof v === 'number') return v !== 0;
    if (typeof v === 'string') return v.toLowerCase() === 'true' || v === '1';
    return true;
}

function buildScriptIframeHtml(script) {
    const parentOrigin = window.location.origin;
    const scriptName = String(script.name == null ? '' : script.name);
    const scriptId = String(script.id == null ? '' : script.id);
    const iframeName = `TH-script--${scriptName}--${scriptId}`;
    const rawContent = String(script.content == null ? '' : script.content);
    // 防止 HTML 解析被 </script> 截断（模块内 <\/script> 与 </script> 等价）
    const content = rawContent.replace(/<\/script>/gi, '<\\/script>');
    return `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<base href="${parentOrigin}/st-runtime/">
<script>
// [v218] 诊断 + console/error 闸门必须在 predefine **之前**安装。
// 旧版把 console 桥放在 body（predefine 之后）：predefine 一旦抛错就完全静默，
// 真机「eventOn is not defined」查了三版查不下去就是这个盲区。
(function () {
    window.__rikkaDiag = function (tag, extra) {
        var d = { tag: String(tag) };
        try { d.eventOn = typeof window.eventOn; } catch (e) { d.eventOn = 'ERR'; }
        try { d.thKeys = window.TavernHelper ? Object.keys(window.TavernHelper).length : -1; } catch (e) { d.thKeys = 'ERR'; }
        try { d.toastr = typeof window.toastr; } catch (e) { d.toastr = 'ERR'; }
        try { d.mvu = typeof window.Mvu; } catch (e) { d.mvu = 'ERR'; }
        try { d.dollar = typeof window.$; } catch (e) { d.dollar = 'ERR'; }
        try { d.name = String(window.name || ''); } catch (e) { d.name = 'ERR'; }
        try { d.isTop = (window.parent === window); } catch (e) { d.isTop = 'ERR'; }
        try { d.parentHasLog = !!(window.parent && window.parent.__rikkaLog); } catch (e) { d.parentHasLog = 'ERR:' + (e && e.name); }
        try { d.parentThKeys = (window.parent && window.parent.TavernHelper) ? Object.keys(window.parent.TavernHelper).length : -1; } catch (e) { d.parentThKeys = 'ERR:' + (e && e.name); }
        try { d.parentErr = window.__rikkaParentErr || null; } catch (e) { /* noop */ }
        try { d.hostInjected = !!window.__rikkaHostInjected; } catch (e) { /* noop */ }
        if (extra) { for (var k in extra) { try { d[k] = extra[k]; } catch (e) { /* noop */ } } }
        try { window.parent.postMessage({ type: 'th-script-diag', diag: d }, '*'); } catch (e) { /* noop */ }
        try { if (window.parent && window.parent.__rikkaLog) window.parent.__rikkaLog('debug', '[TH-script] diag ' + JSON.stringify(d)); } catch (e) { /* noop */ }
        return d;
    };
    if (!window.__rikkaConsolePatched) {
        window.__rikkaConsolePatched = true;
        var send = function (level, args) {
            try {
                var text = Array.prototype.map.call(args, function (a) {
                    try {
                        if (typeof a === 'string') return a;
                        if (a instanceof Error) return a.stack || (a.name + ': ' + a.message);
                        if (a && typeof a === 'object' && typeof a.stack === 'string') {
                            return (a.name ? a.name + ': ' : '') + (a.message || '') + '\\n' + a.stack;
                        }
                        var s = JSON.stringify(a);
                        return s === undefined ? String(a) : s;
                    } catch (e) { return String(a); }
                }).join(' ');
                try { window.parent.postMessage({ type: 'th-iframe-log', level: String(level), text: String(text).slice(0, 900) }, '*'); } catch (e) { /* noop */ }
                try { if (window.parent && window.parent.__rikkaLog) window.parent.__rikkaLog(level, text); } catch (e) { /* noop */ }
            } catch (e) { /* noop */ }
        };
        ['log', 'debug', 'info', 'warn', 'error'].forEach(function (level) {
            var orig = console[level] ? console[level].bind(console) : function () {};
            console[level] = function () {
                var args = Array.prototype.slice.call(arguments);
                send(level, args);
                orig.apply(null, args);
            };
        });
    }
    if (!window.__rikkaErrGate) {
        window.__rikkaErrGate = true;
        window.addEventListener('error', function (e) {
            try {
                var m = '[TH-script] error: ' + String((e && (e.message || (e.error && e.error.stack))) || e);
                try { window.parent.postMessage({ type: 'th-iframe-log', level: 'error', text: m }, '*'); } catch (_e) { /* noop */ }
                try { if (window.parent && window.parent.__rikkaLog) window.parent.__rikkaLog('error', m); } catch (_e) { /* noop */ }
            } catch (_e) { /* noop */ }
        }, true);
    }
})();
<\/script>
<script>
(function () {
    var p = null;
    try { p = window.parent; } catch (e) { window.__rikkaParentErr = String((e && e.name) || e); }
    try {
        // 1) 全局库引用（纯对象/函数，跨 realm 安全；jQuery/lodash 由下方标签自加载）
        window.z = p.z;
        window.YAML = p.YAML;
        window.klona = p.klona;
        window.Vue = p.Vue;
        window.VueRouter = p.VueRouter;
        window.toastr = p.toastr; // 复用宿主 toastr（已桥接日志；离屏 WebView 中弹窗不可见）
        // 2) TavernHelper + 裸全局函数（JSR predefine 语义）
        // [v218] 三个来源按优先级取，且**不覆盖已注入的非空集合**：
        //   (a) 父窗口的 TavernHelper（同源时可读）
        //   (b) 宿主在 iframe 装载瞬间直接写进本窗口的 TavernHelper（跨域也能到，见 mountScriptIframe 的 pushShims）
        //   (c) 本窗口已有的非空 TavernHelper
        var parentTH = null;
        try { parentTH = (p && p.TavernHelper) ? p.TavernHelper : null; } catch (e) { parentTH = null; }
        var parentTHKeys = 0;
        try { parentTHKeys = parentTH ? Object.keys(parentTH).length : 0; } catch (e) { parentTHKeys = 0; }
        var existingTH = (window.TavernHelper && typeof window.TavernHelper === 'object') ? window.TavernHelper : null;
        var existingKeys = 0;
        try { existingKeys = existingTH ? Object.keys(existingTH).length : 0; } catch (e) { existingKeys = 0; }
        // [v244 BUGFIX D1] 不再「二选一丢弃」，改成**并集合并**。
        // 真机取证（tavern-runtime.log 2026-10-13 23:07:45 diag）：
        //   parentTHKeys=157 / existingKeys=167 —— 旧代码 parentTHKeys > 0 ? parentTH : …
        //   会把更丰富的 existingTH 整份丢掉；而两份的键集**并不相同**
        //   （父窗口那份可能已被第三方扩展 JSR 换成它自己的对象，宿主那份是模块内建实现）。
        //   后果：卡片 fn('createChatMessages'/'triggerSlash') 随机命中/落空 →
        //   「未检测到酒馆助手的 createChatMessages / triggerSlash 接口，请确认助手扩展已开启！」。
        // 现在：两份都装（键少的先装、键多的后装覆盖），再把宿主自带实现（p.__mvu_shims__）补缺。
        var sources = [];
        if (parentTH && existingTH && parentTH !== existingTH) {
            sources = parentTHKeys >= existingKeys ? [existingTH, parentTH] : [parentTH, existingTH];
        } else {
            sources = [parentTH || existingTH || {}];
        }
        var hostShims = null;
        try { hostShims = (p && p.__mvu_shims__) ? p.__mvu_shims__ : null; } catch (e) { hostShims = null; }
        if (hostShims && sources.indexOf(hostShims) < 0) sources.push(hostShims);
        window.TavernHelper = {};
        for (var si = 0; si < sources.length; si++) {
            var srcObj = sources[si];
            if (!srcObj || typeof srcObj !== 'object') continue;
            for (var key in srcObj) { try { window.TavernHelper[key] = srcObj[key]; } catch (e) { /* noop */ } }
        }
        window.TavernHelper._bind = window.TavernHelper._bind || {};
        for (var key2 in window.TavernHelper) {
            if (key2 === '_bind') continue;
            try { window[key2] = window.TavernHelper[key2]; } catch (e) { /* noop */ }
        }
        // 宿主自带实现（模块内建那份，永远有 createChatMessages / triggerSlash / 世界书写入面）
        // 只补**缺失或非函数**的键 —— 裸全局 + TavernHelper 两个位置都要在。
        if (hostShims) {
            var __core = ${JSON.stringify(CORE_BARE_GLOBALS)};
            for (var ci = 0; ci < __core.length; ci++) {
                var cn = __core[ci];
                try {
                    if (typeof hostShims[cn] !== 'function') continue;
                    if (typeof window[cn] !== 'function') window[cn] = hostShims[cn];
                    if (typeof window.TavernHelper[cn] !== 'function') window.TavernHelper[cn] = hostShims[cn];
                } catch (e) { /* noop */ }
            }
        }
        // [v221] JSR predefine 语义补全：_bind 的每个 key 去掉前导下划线后挂成裸全局，并 bind(本 iframe window)。
        // 真机根因：JSR 会把父窗口 window.TavernHelper 换成它自己的对象（顶层**没有** eventOn，
        // 只有 _bind._eventOn）→ 旧代码只遍历顶层 key，裸全局 eventOn 就丢了
        // （真机 diag：thKeys=165/parentThKeys=157 但 eventOn=undefined）。
        // [v244] 注意：TH 变量已在上面被 sources 合并逻辑取代，这里只能读合并后的 window.TavernHelper。
        var __bindSrc = (window.TavernHelper && window.TavernHelper._bind) || (sources[0] && sources[0]._bind) || null;
        if (__bindSrc) {
            for (var bkey in __bindSrc) {
                var bare = String(bkey).replace(/^_/, '');
                if (bare === bkey) continue;
                try { if (typeof window[bare] !== 'function') window[bare] = __bindSrc[bkey].bind(window); } catch (e) { /* noop */ }
            }
        }
        // [v244] API 面探针：卡片 fn(name) 会依次探 window / window.TavernHelper / parent / parent.TavernHelper。
        // 四个位置全部打出来 —— 一次真机复现即可判定「未检测到 createChatMessages / triggerSlash」缺在哪一层。
        var __probeOf = function (o, k) { try { return typeof (o && o[k]); } catch (e) { return 'ERR'; } };
        window.__rikkaPredefineInfo = {
            parentTHKeys: parentTHKeys,
            existingKeys: existingKeys,
            usedKeys: Object.keys(window.TavernHelper).length,
            srcCount: sources.length,
            hostShims: !!hostShims,
            win_triggerSlash: __probeOf(window, 'triggerSlash'),
            win_createChatMessages: __probeOf(window, 'createChatMessages'),
            win_createWorldbookEntries: __probeOf(window, 'createWorldbookEntries'),
            th_triggerSlash: __probeOf(window.TavernHelper, 'triggerSlash'),
            th_createChatMessages: __probeOf(window.TavernHelper, 'createChatMessages'),
            th_createWorldbookEntries: __probeOf(window.TavernHelper, 'createWorldbookEntries'),
            p_triggerSlash: __probeOf(p, 'triggerSlash'),
            pTh_triggerSlash: __probeOf(p && p.TavernHelper, 'triggerSlash'),
            pTh_createChatMessages: __probeOf(p && p.TavernHelper, 'createChatMessages'),
        };
        // 2.5) 脚本按钮 / 脚本身份：per-iframe 包装（对齐 JSR 语义）
        var sid = ${JSON.stringify(scriptId)};
        var sname = ${JSON.stringify(scriptName)};
        function wrapButton(fnName) {
            return function () {
                var args = Array.prototype.slice.call(arguments);
                var hub = p.__rikkaButtonHub;
                if (!hub || typeof hub[fnName] !== 'function') return undefined;
                return hub[fnName].apply(null, [sid, sname].concat(args));
            };
        }
        window.appendInexistentScriptButtons = wrapButton('append');
        window.replaceScriptButtons = wrapButton('replace');
        window.updateScriptButtonsWith = wrapButton('update');
        window.getScriptButtons = function () {
            return (p.__rikkaButtonHub && p.__rikkaButtonHub.list) ? p.__rikkaButtonHub.list(sid) : [];
        };
        window.getButtonEvent = function (name) {
            return (p.__rikkaButtonHub && p.__rikkaButtonHub.event) ? p.__rikkaButtonHub.event(sid, String(name)) : String(name);
        };
        // 脚本身份 API（覆盖主窗口硬编码实现）
        window.getScriptId = function () { return sid; };
        window.getScriptName = function () { return sname; };
        window.getIframeName = function () { return window.name; };
        if (window.TavernHelper) {
            window.TavernHelper.appendInexistentScriptButtons = window.appendInexistentScriptButtons;
            window.TavernHelper.replaceScriptButtons = window.replaceScriptButtons;
            window.TavernHelper.updateScriptButtonsWith = window.updateScriptButtonsWith;
            window.TavernHelper.getScriptButtons = window.getScriptButtons;
            window.TavernHelper.getButtonEvent = window.getButtonEvent;
            window.TavernHelper.getScriptId = window.getScriptId;
            window.TavernHelper.getScriptName = window.getScriptName;
            window.TavernHelper.getIframeName = window.getIframeName;
        }
        // 3) SillyTavern 代理
        Object.defineProperty(window, 'SillyTavern', {
            get: function () { return p.SillyTavern; },
            configurable: true
        });
        // 4) Mvu 代理（MVU-offline / 卡 MVU 脚本挂到 parent）
        Object.defineProperty(window, 'Mvu', {
            get: function () { return p.Mvu; },
            set: function (v) { p.Mvu = v; try { p.__rikkaMvuOwner = window.name; } catch (e) { /* noop */ } },
            configurable: true
        });
        // 5) vue / pinia 标志（JSR predefine.js 同款）
        window.__VUE_PROD_DEVTOOLS__ = true;
        window.__VUE_OPTIONS_API__ = true;
        window.__VUE_PROD_HYDRATION_MISMATCH_DETAILS__ = false;
        // 6) iframe 名称（JSR getScriptId 解析语义）
        window.name = ${JSON.stringify(iframeName)};
        window.__TH_IFRAME_ID = window.name;
        console.log('[TH-script] predefine ok: ' + window.name);
        if (typeof window.__rikkaDiag === 'function') window.__rikkaDiag('predefine-ok', window.__rikkaPredefineInfo);
    } catch (err) {
        window.__rikkaPredefineErr = String((err && (err.stack || err.message)) || err);
        if (typeof window.__rikkaDiag === 'function') window.__rikkaDiag('predefine-failed', { err: window.__rikkaPredefineErr });
        console.error('[TH-script] predefine failed', err);
    }
})();
<\/script>
<script src="${parentOrigin}/st-runtime/vendor/lib/jquery-3.5.1.min.js"><\/script>
<script src="${parentOrigin}/st-runtime/vendor/lib/lodash.min.js"><\/script>
</head>
<body>
<script>
(function () {
    // [v218] 错误转发与 console 桥已提前到 <head>（predefine 之前）安装，此处不再重复。
    // iframe 卸载清理（重建脚本时防止监听器 / Mvu 泄漏）
    $(window).on('pagehide', function () {
        try {
            if (window.parent && window.parent.__rikkaMvuOwner === window.name) {
                delete window.parent.Mvu;
                delete window.parent.__rikkaMvuOwner;
            }
        } catch (e) { /* noop */ }
        try { if (window.eventClearAll) window.eventClearAll(); } catch (e) { /* noop */ }
    });
})();
<\/script>
<script type="module">
// [v218] 卡片脚本执行前最后一道自检：eventOn 缺失时立刻上报（而不是等卡自己抛「is not defined」）
try { if (typeof window.__rikkaDiag === 'function') window.__rikkaDiag('module-start'); } catch (_e) { /* noop */ }
${content}
<\/script>
</body>
</html>`;
}

// [v245 BUGFIX F1] iframe 拆除统一入口。
// 真机取证（round-4 tavern-runtime.log，6 个脚本 iframe 全部报）：
//   [10-14 00:49:40.590][error] [TH-script] host-verify FAILED: TH-script--fb4825fd-… eventOn missing; {"eventOn":"object"}
// 而同一个 iframe 在 2500ms 前的 predefine-ok diag 里明明写着 "eventOn":"function"。
//
// 根因（先证伪了「eventOn 在 predefine 之后被 JSR 覆盖成 object」这条假设）：
//   ① loadTavernScripts(force) / mountScriptIframe 重建脚本 iframe 时直接 frame.remove()，
//      **没有取消**该 iframe 自己的 2500ms host-verify 定时器；
//   ② 定时器到点时 iframe 早已被摘出 DOM → `iframe.contentWindow` 返回 **null**；
//   ③ 原诊断的回退分支写的是 `{ eventOn: typeof (w && w.eventOn) }`，而 JS 里
//      **`typeof null === 'object'`** → 于是打出 `{"eventOn":"object"}` 这个假告警。
//   判据：真正走 __rikkaDiag('host-verify') 的那条路会带上 tag / thKeys / name 等字段；
//   日志里只有孤零零一个 eventOn 键 → 必然走的是 null 回退分支。
// 修法：拆除时统一清定时器；host-verify 自己再区分「iframe 还在 DOM 但窗口为空」与「已拆除」。
function teardownScriptIframe(frame) {
    if (!frame) return;
    try { if (frame.__rikkaHostVerifyTimer) clearTimeout(frame.__rikkaHostVerifyTimer); } catch (_e) { /* noop */ }
    try { if (frame.__rikkaGuardTimer) clearInterval(frame.__rikkaGuardTimer); } catch (_e) { /* noop */ }
    try { frame.remove(); } catch (_e) { /* noop */ }
}

function mountScriptIframe(script) {
    const id = String(script.id == null || script.id === '' ? (script.name == null ? 'script' : script.name) : script.id);
    if (scriptIframes.has(id)) {
        teardownScriptIframe(scriptIframes.get(id));
        scriptIframes.delete(id);
    }
    const html = buildScriptIframeHtml(script);
    const blob = new Blob([html], { type: 'text/html' });
    const blobUrl = URL.createObjectURL(blob);
    const iframe = document.createElement('iframe');
    iframe.src = blobUrl;
    iframe.id = `TH-script--${id}`;
    iframe.style.cssText = 'display:none;width:0;height:0;border:none;position:absolute;pointer-events:none;';
    document.body.appendChild(iframe);
    iframe.addEventListener('load', () => { try { URL.revokeObjectURL(blobUrl); } catch (_e) { /* noop */ } });
    // [v218] 宿主侧兜底注入：**不依赖子窗口去读 window.parent**。
    // 真机 P0「eventOn is not defined」的现场是「首次装载正常、force 重装后必现」，
    // 说明子窗口 head predefine 在某些时机会拿不到父窗口的 TavernHelper；而父→子的直接赋值
    // 在任何时候都成立（同源导航复用同一个 Window 对象，文档还没解析就能写）。
    // [v218.1]/[v221] 宿主侧兜底注入：**不依赖子窗口去读 window.parent**。
    // v221 真机根因：父窗口 TavernHelper 被 JSR 换成它那份（顶层无 eventOn）→
    // pushRuntimeGlobals 现在同时装顶层 key 与 _bind key，并用模块内建那份兜底。
    const pushShims = () => pushRuntimeGlobals(iframe.contentWindow);
    pushShims();
    iframe.addEventListener('load', pushShims);
    [50, 250, 800].forEach((ms) => setTimeout(pushShims, ms));
    iframe.__rikkaGuardTimer = guardChildGlobals(iframe.contentWindow);
    // [v245 BUGFIX F1] 定时器句柄挂在 iframe 上，拆除时由 teardownScriptIframe 取消。
    iframe.__rikkaHostVerifyTimer = setTimeout(() => {
        try {
            // 已从 DOM 摘掉（重建 / 切会话）→ 这不是失败，别刷假告警。
            if (!iframe.isConnected) return;
            const w = iframe.contentWindow;
            if (!w) {
                callBridge('log', 'error', `[TH-script] host-verify: ${iframe.id} iframe 仍在 DOM 但 contentWindow 为空`);
                return;
            }
            if (typeof w.eventOn === 'function') return;
            let diag = 'n/a';
            try { diag = JSON.stringify(w.__rikkaDiag ? w.__rikkaDiag('host-verify') : { eventOn: typeof w.eventOn }); } catch (_e) { diag = 'diag-failed'; }
            callBridge('log', 'error', `[TH-script] host-verify FAILED: ${iframe.id} eventOn missing; ${diag}`);
        } catch (_e) { /* noop */ }
    }, 2500);

    scriptIframes.set(id, iframe);
    console.log(`${LOG_PREFIX} script iframe mounted: ${iframe.id} (${script.name || ''})`);
}

let scriptsLoadGen = 0;

async function loadTavernScripts(force) {
    let raw = '';
    try { raw = callBridge('getTavernScriptsJson') || '{}'; } catch (_e) { raw = '{}'; }
    let helper = null;
    try { helper = (JSON.parse(raw) || {}).tavern_helper || null; } catch (_e) { helper = null; }
    const list = (helper && Array.isArray(helper.scripts)) ? helper.scripts : [];
    const scripts = list.filter((s) => s && s.type === 'script' && scriptEnabled(s.enabled));
    const signature = scripts.map((s) => `${String(s.id)}:${String(s.name)}`).join('|');
    const convTag = String(state.chatId == null ? '' : state.chatId);
    if (!force && signature === lastScriptsSignature) {
        try { callBridge('log', 'debug', `[scripts] skip (same signature, conv=${convTag}, n=${scripts.length})`); } catch (_e) { /* noop */ }
        return false;
    }
    const myGen = ++scriptsLoadGen;
    // 集合变化：全部重建（pagehide 会自清理监听与 Mvu）
    // [v245 BUGFIX F1] 走统一拆除入口：必须取消各自的 host-verify / guard 定时器，
    // 否则被摘掉的 iframe 会在 2.5s 后误报 `host-verify FAILED … {"eventOn":"object"}`。
    for (const [id, frame] of scriptIframes) {
        teardownScriptIframe(frame);
        scriptIframes.delete(id);
    }
    lastScriptsSignature = signature;
    // [P0-1 根因1修复] 识别 MagVarUpdate（如大小姐 var_update 脚本），避免重复创建内置 mvuIframe 引发双份 MVU 冲突
    mvuFromScripts = scripts.some((s) => /mvu_bundle_full|MVU-offline|MagVarUpdate/i.test(String(s.content || '')));
    // [P0-1 根因1修复] 串行分帧挂载脚本 iframe（每两个重型 iframe 之间让出主线程 80ms），
    // 避免 6 个 blob: iframe 在同一毫秒并发初始化冲击共享的 CrRendererMain 造成渲染线程死锁。
    for (let i = 0; i < scripts.length; i++) {
        if (myGen !== scriptsLoadGen) return false;
        mountScriptIframe(scripts[i]);
        if (i + 1 < scripts.length) {
            await new Promise((r) => setTimeout(r, 80));
        }
    }
    if (myGen !== scriptsLoadGen) return false;
    try { callBridge('log', 'info', `[scripts] loaded ${scripts.length}${scripts.length ? ': ' + scripts.map((s) => s.name).join(', ') : ''}, mvu_from_scripts=${mvuFromScripts} (conv=${convTag}, force=${!!force})`); } catch (_e) { /* noop */ }
    emitToHost('scripts_loaded', { count: scripts.length, mvu_from_scripts: mvuFromScripts });
    return true;
}

// ---------------------------------------------------------------- 第三方扩展（ST third-party loader）
// ST 语义：扩展以 ES module 注入（<script type="module" src="/scripts/extensions/third-party/<folder>/<js>">），
// CSS 以 <link> 注入；模块内的相对 import（../../../../script.js 等）经 URL 归一化后，
// 由宿主 shouldInterceptRequest 路由到 st-compat shim（/script.js、/scripts/<name>.js）。
const thirdPartyStyleEls = [];
const thirdPartyScriptEls = [];
const thirdPartyLoadedUrls = new Set();
let lastThirdPartySignature = null;

function thirdPartyAssetUrl(folder, rel) {
    const segs = String(rel).split('/').map((s) => encodeURIComponent(s));
    return `/scripts/extensions/third-party/${encodeURIComponent(folder)}/${segs.join('/')}`;
}

function injectThirdPartyStyles(list) {
    for (const ext of list) {
        const css = String(ext.css || '');
        if (!css) continue;
        const el = document.createElement('link');
        el.rel = 'stylesheet';
        el.type = 'text/css';
        el.href = thirdPartyAssetUrl(ext.folder, css);
        el.setAttribute('data-rikka-third-party', ext.folder);
        document.head.appendChild(el);
        thirdPartyStyleEls.push(el);
    }
}

function parseSimpleSemver(v) {
    const m = String(v || '').trim().match(/^(\d+)\.(\d+)\.(\d+)/);
    if (!m) return [0, 0, 0];
    return [Number(m[1]) || 0, Number(m[2]) || 0, Number(m[3]) || 0];
}

function isSemverAtLeast(currentVer, minVer) {
    if (!minVer) return true;
    const cur = parseSimpleSemver(currentVer);
    const req = parseSimpleSemver(minVer);
    for (let i = 0; i < 3; i++) {
        if (cur[i] > req[i]) return true;
        if (cur[i] < req[i]) return false;
    }
    return true;
}

function injectThirdPartyModule(ext, js) {
    const src = thirdPartyAssetUrl(ext.folder, js);
    if (thirdPartyLoadedUrls.has(src)) return Promise.resolve();
    thirdPartyLoadedUrls.add(src);
    return Promise.resolve()
        .then(() => Promise.race([
            import(src),
            new Promise((_, reject) => setTimeout(() => reject(new Error(`extension import timeout after 5000ms: ${src}`)), 5000)),
        ]))
        .then(async (mod) => {
            loadedExtensionHooks[ext.folder] = {
                mod: mod || null,
                hooks: (ext && ext.hooks && typeof ext.hooks === 'object') ? ext.hooks : {},
            };
            if (ext && ext.generateInterceptor) {
                const existingIdx = loadedInterceptors.findIndex((x) => x.folder === ext.folder);
                if (existingIdx !== -1) loadedInterceptors.splice(existingIdx, 1);
                loadedInterceptors.push({
                    folder: ext.folder,
                    name: String(ext.generateInterceptor),
                    loadingOrder: Number(ext.loadingOrder) || 100,
                    mod: mod || null,
                });
                loadedInterceptors.sort((a, b) => a.loadingOrder - b.loadingOrder);
            }
            await invokeExtensionHook(ext.folder, 'enable');
            await invokeExtensionHook(ext.folder, 'activate');
            try { callBridge('log', 'info', `[third-party] loaded ${ext.folder}/${js}`); } catch (_e) { /* noop */ }
        })
        .catch(async (err) => {
            let detail = 'http=?';
            try {
                const res = await fetch(src, { cache: 'no-store' });
                detail = `http=${res.status}`;
            } catch (_e2) { detail = 'http=ERR'; }
            const msg = (err && (err.stack || (err.name + ': ' + err.message))) || String(err);
            try {
                callBridge('log', 'error', `[third-party] 加载失败: ${ext.folder}/${js} ${detail} url=${src} → ${msg}`);
            } catch (_e3) { /* noop */ }
        });
}

function normalizeExtFolderAliasJs(raw) {
    return String(raw == null ? '' : raw)
        .trim()
        .replace(/^third-party\//i, '')
        .replace(/-(?:main|master|dev|release|v?\d+(?:\.\d+)*(?:-[a-z0-9._-]+)?)$/i, '')
        .toLowerCase();
}

async function loadThirdPartyExtensions(force) {
    let settingsFolderForLoad = '';
    try { settingsFolderForLoad = new URLSearchParams(location.search).get('ext') || ''; } catch (_e) { settingsFolderForLoad = ''; }
    let raw = '[]';
    try {
        if (settingsFolderForLoad) {
            raw = callBridge('getAllThirdPartyExtensionsJson') || callBridge('getThirdPartyExtensionsJson') || '[]';
        } else {
            raw = callBridge('getThirdPartyExtensionsJson') || '[]';
        }
    } catch (_e) { raw = '[]'; }
    let list = [];
    try { const parsed = JSON.parse(raw); if (Array.isArray(parsed)) list = parsed; } catch (_e) { list = []; }
    if (settingsFolderForLoad) {
        const beforeFilter = list.length;
        const targetAlias = normalizeExtFolderAliasJs(settingsFolderForLoad);
        list = list.filter((e) => {
            const f = String((e && e.folder) || '');
            return f === settingsFolderForLoad || (targetAlias && normalizeExtFolderAliasJs(f) === targetAlias);
        });
        try {
            if (list.length === 0) {
                callBridge('log', 'error', `[third-party] 设置页目标扩展不存在或已停用: ${settingsFolderForLoad} (installed=${beforeFilter})`);
            } else {
                callBridge('log', 'info', `[third-party] 设置页过滤 ext=${settingsFolderForLoad} matched=${list.length}/${beforeFilter} (${list.map(x => x.folder).join(',')})`);
            }
        } catch (_e) { /* noop */ }
    }

    const activeFolders = new Set(list.map((e) => String(e.folder || '')).filter(Boolean));
    list = list.filter((ext) => {
        if (!ext || !ext.folder) return false;
        if (ext.minimumClientVersion && !isSemverAtLeast(TAVERN_ST_COMPAT_VERSION, ext.minimumClientVersion)) {
            try {
                callBridge('log', 'warn', `[third-party] 跳过扩展 ${ext.folder}: 需要客户端版本 >= ${ext.minimumClientVersion} (当前 ${TAVERN_ST_COMPAT_VERSION})`);
            } catch (_e) { /* noop */ }
            return false;
        }
        if (Array.isArray(ext.requires) && ext.requires.length > 0 && !settingsFolderForLoad) {
            const missing = ext.requires.filter((req) => !activeFolders.has(String(req)));
            if (missing.length > 0) {
                try {
                    callBridge('log', 'warn', `[third-party] 跳过扩展 ${ext.folder}: 缺少依赖扩展 ${missing.join(', ')}`);
                } catch (_e) { /* noop */ }
                return false;
            }
        }
        return true;
    });

    list.sort((a, b) => (Number(a.loadingOrder) || 100) - (Number(b.loadingOrder) || 100));

    const signature = list.map((e) => `${String(e.folder)}:${String(e.js)}:${String(e.css || '')}`).join('|');
    if (!force && signature === lastThirdPartySignature) return;
    lastThirdPartySignature = signature;

    for (const el of thirdPartyStyleEls.splice(0)) { try { el.remove(); } catch (_e) { /* noop */ } }
    for (const el of thirdPartyScriptEls.splice(0)) { try { el.remove(); } catch (_e) { /* noop */ } }

    try {
        window.__rikkaSt = window.__rikkaSt || {};
        window.__rikkaSt.extensions = list;
    } catch (_e) { /* noop */ }

    if (list.length === 0) return;

    injectThirdPartyStyles(list);
    for (const ext of list) {
        const js = String(ext.js || '');
        if (!js || !ext.folder) continue;
        await injectThirdPartyModule(ext, js);
    }

    try { window.dispatchEvent(new Event('rikka-st-refresh')); } catch (_e) { /* noop */ }
    try {
        callBridge('log', 'info', `[third-party] loaded ${list.length}: ${list.map((e) => e.displayName || e.folder).join(', ')}`);
    } catch (_e) { /* noop */ }
    emitToHost('third_party_extensions_loaded', { count: list.length, folders: list.map((e) => e.folder) });
}

window.__rikkaOnExtensionDisabled = async function (folder) {
    const f = String(folder || '');
    await invokeExtensionHook(f, 'disable');
    MessageFormatter.clearHooksByOwner(f);
    for (let i = loadedInterceptors.length - 1; i >= 0; i--) {
        if (loadedInterceptors[i].folder === f) loadedInterceptors.splice(i, 1);
    }
};

window.__rikkaOnExtensionDeleted = async function (folder) {
    const f = String(folder || '');
    await invokeExtensionHook(f, 'delete');
    MessageFormatter.clearHooksByOwner(f);
    delete loadedExtensionHooks[f];
    for (let i = loadedInterceptors.length - 1; i >= 0; i--) {
        if (loadedInterceptors[i].folder === f) loadedInterceptors.splice(i, 1);
    }
};

// Kotlin 侧触发（安装/启停/删除扩展后）：eval("__rikkaReloadThirdPartyExtensions()")
window.__rikkaReloadThirdPartyExtensions = () => loadThirdPartyExtensions(true);

// ---------------------------------------------------------------- #tavern_helper DOM（MVU unique_check 依赖）
// ST/JSR 语义：`#tavern_helper` 是「脚本清单容器」；MVU 的 unique_check 用
//   `$('#tavern_helper').find('div[data-script-id]')` 判断「谁是被选中的 MVU 实现」：
//   取「注册过的 id 里、在 DOM 中排最后的那一个」，命中自己才 should_enable=true，才会发布 window.Mvu。
//
// ⚠️ v206 真机根因（诊断埋点实测 diag={"helperLen":1,"idsViaParentJq":[]}）：
//   JSR 也在它的 jQuery ready 回调里 `$('<div id="tavern_helper">').appendTo('#extensions_settings')`
//   （见 dist: `$(...{let e=$('<div id="tavern_helper">').appendTo('#extensions_settings');$9.mount(e[0])}`）。
//   而 `getElementById` / `$('#id')` 只返回【文档序第一个】——`#extensions_settings` 在 body 前部、
//   我方容器在 body 末尾 → MVU 查到的永远是 JSR 那个「里面没有 div[data-script-id]」的容器 →
//   getPreferredScriptId()=undefined ≠ 'mvu-standalone' → should_enable=false →
//   **window.Mvu 永不发布** → 真机 30s 后 `MVU init timeout`（红字），卡内脚本同时报
//   「未检测到MVU框架，核心引擎无法启动！」。
// 修法：**每一个** #tavern_helper 容器里都要有我方脚本身份 div（不碰 JSR 容器的可见性/内容）。
function ensureTavernHelperDom(scriptIds) {
    let holders = Array.from(document.querySelectorAll('#tavern_helper'));
    if (holders.length === 0) {
        const created = document.createElement('div');
        created.id = 'tavern_helper';
        created.style.display = 'none';
        document.body.appendChild(created);
        holders = [created];
    }
    for (const holder of holders) {
        for (const sid of scriptIds) {
            if (!holder.querySelector(`div[data-script-id="${sid}"]`)) {
                const d = document.createElement('div');
                d.setAttribute('data-script-id', sid);
                holder.appendChild(d);
            }
        }
    }
    return holders.length;
}

// v206：JSR 的容器可能在任何时刻被创建/被 Vue 重渲染清空 → 短期内轮询补齐（比全文档 MutationObserver 便宜）
function keepTavernHelperDom(scriptIds, ms = 20000) {
    const deadline = Date.now() + ms;
    const t = setInterval(() => {
        if (Date.now() > deadline) { clearInterval(t); return; }
        try { ensureTavernHelperDom(scriptIds); } catch (_e) { /* noop */ }
    }, 500);
    return t;
}

// v206：把「首选 MVU 实现」重新广播一次，逼 MVU bundle 内的 listenPreferenceState 回调重算 should_enable。
// 背景：bundle 的 should_enable 只在 listenPreferenceState 回调里赋值一次（= 取 DOM 里排最后的那个实现），
// 那个时刻如果 DOM 还不正确，它就永远停在 false。事件总线是同一个（我方 shims 的 eventEmit/eventOn）。
// [v218.1] 从脚本 iframe 里「认领」卡片自带 MVU。
// 背景（v218 真机 diag 实证）：魔法少女卡的 MVU-offline bundle 在**脚本 iframe 自己的 window** 上
// 建立 Mvu —— 我们的 predefine 把 `Mvu` 定义成 proxy（set → p.Mvu），但 bundle 若用
// Object.defineProperty 重建 'Mvu' 就会绕过 setter，父窗口永远看不到；宿主只能 6s 后回退内置 MVU
// （面板「vars wait timeout」那一族就是这么来的）。
// 父→子读是同源的，直接把子窗口那份取过来即可（不依赖子窗口的 setter）。
function adoptScriptIframeMvu() {
    const pick = (w) => {
        if (!w) return null;
        let cand = null;
        try { cand = w.Mvu; } catch (_e) { return null; }
        if (!cand) return null;
        if (typeof cand !== 'object' && typeof cand !== 'function') return null;
        return cand;
    };
    // [v235 I4] 认领时登记 owner：卸载清理只允许「自己发布的」Mvu 被删，
    // 否则旧 iframe 的 pagehide 会把新卡刚认领的 Mvu 一起删掉（面板 vars 恒空）。
    const claim = (w, cand) => {
        try { window.__rikkaMvuOwner = (w && w.name) || ''; } catch (_e) { /* noop */ }
        return cand;
    };
    try {
        for (const [, frame] of scriptIframes) {
            const w = frame && frame.contentWindow;
            const got = pick(w);
            if (got) return claim(w, got);
        }
    } catch (_e) { /* noop */ }
    try { const w = mvuIframe && mvuIframe.contentWindow; return claim(w, pick(w)); } catch (_e) { return null; }
}

function forceMvuPreference(scriptId = 'mvu-standalone', label = 'MVU变量框架') {
    try {
        shimEvent.eventEmit(`th_unique_check.${label}`, scriptId);
        console.log(`${LOG_PREFIX} re-emit ${`th_unique_check.${label}`} = ${scriptId}`);
    } catch (_e) { /* noop */ }
}

// ---------------------------------------------------------------- MVU iframe（blob 宿主）
const MVU_BUNDLE_URL = 'https://appassets.androidplatform.net/st-runtime/mvu/bundle.js';
let mvuIframe = null;
let mvuReady = false;
let mvuPollTimer = null;
let mvuAutoRetries = 0;

function buildMvuIframeHtml() {
    const shimNames = Object.keys(window.__mvu_shims__ || {});
    const shimCopyCode = shimNames
        .map((name) => `window[${JSON.stringify(name)}] = p.__mvu_shims__[${JSON.stringify(name)}];`)
        .join('\n            ');
    const parentOrigin = window.location.origin;
    return `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<title>MVU Runtime</title>
<script>
(function () {
    var p = window.parent;
    var parentOrigin = ${JSON.stringify(parentOrigin)};
    window.parentOrigin = parentOrigin;
    // ── [v205] 诊断：iframe 内的 console / 未捕获错误一律转发到宿主日志。
    //    此前这一层是 0 可见性（真机日志里一条 MVU-Iframe 都没有），
    //    所以「MVU init timeout」永远查不下去。
    function __rikkaFwd(level, text) {
        try {
            window.parent.postMessage({ type: 'mvu-iframe-log', level: level, text: String(text == null ? '' : text).slice(0, 900) }, '*');
        } catch (e) { /* noop */ }
    }
    ['log', 'warn', 'error'].forEach(function (lv) {
        var orig = console[lv] ? console[lv].bind(console) : function () {};
        console[lv] = function () {
            var args = Array.prototype.slice.call(arguments);
            try { orig.apply(null, args); } catch (e) { /* noop */ }
            try {
                __rikkaFwd(lv, args.map(function (a) { return String(a && a.message ? a.message : a); }).join(' '));
            } catch (e) { /* noop */ }
        };
    });
    window.addEventListener('error', function (e) {
        __rikkaFwd('error', 'window.onerror: ' + (e && e.message) + ' @' + (e && e.filename) + ':' + (e && e.lineno));
    });
    window.addEventListener('unhandledrejection', function (e) {
        var r = e && e.reason;
        __rikkaFwd('error', 'unhandledrejection: ' + ((r && (r.stack || r.message)) || r));
    });
    // ── [v205] 脚本身份显式声明（与 script iframe 同款）：不再依赖「从 parent 复制过来的闭包」
    window.getScriptId = function () { return 'mvu-standalone'; };
    window.getScriptName = function () { return 'MVU变量框架'; };
    window.getIframeName = function () { return window.name; };
    // ── [v205] 自检：宿主侧超时/复现时可随时调用，一次性上报所有关键中间量
    window.__rikkaMvuSelfCheck = function () {
        var out = {};
        try { out.iframeName = window.name; } catch (e) { out.iframeName = 'ERR ' + e.message; }
        try { out.scriptId = (typeof getScriptId === 'function') ? getScriptId() : 'NO-FN'; } catch (e) { out.scriptId = 'ERR ' + e.message; }
        try { out.isSelfOverride = String(getScriptId) !== String(p.__mvu_shims__ && p.__mvu_shims__.getScriptId); } catch (e) { out.isSelfOverride = 'ERR ' + e.message; }
        try { out.jq = typeof $; out.lodash = typeof _; } catch (e) { /* noop */ }
        try { out.helperLen = $('#tavern_helper').length; } catch (e) { out.helperLen = 'ERR ' + e.message; }
        try {
            out.idsViaParentJq = $('#tavern_helper').find('div[data-script-id]').toArray()
                .map(function (el) { return String($(el).attr('data-script-id')); });
        } catch (e) { out.idsViaParentJq = 'ERR ' + e.message; }
        try { out.hasMvuNow = !!window.Mvu; } catch (e) { out.hasMvuNow = 'ERR ' + e.message; }
        try { out.parentMvu = !!(window.parent && window.parent.Mvu); } catch (e) { out.parentMvu = 'ERR ' + e.message; }
        try {
            var st = _.get(window.parent, 'th_unique_check.MVU变量框架');
            out.registered = st ? Array.from(st) : null;
        } catch (e) { out.registered = 'ERR ' + e.message; }
        try {
            out.preferred = (function () {
                var st2 = _.get(window.parent, 'th_unique_check.MVU变量框架', new Set());
                return _($('#tavern_helper').find('div[data-script-id]').toArray())
                    .map(function (el) { return String($(el).attr('data-script-id')); })
                    .filter(function (id) { return st2.has(id); })
                    .last();
            })();
        } catch (e) { out.preferred = 'ERR ' + e.message; }
        return JSON.stringify(out);
    };
    try {
        // 1) shim API 复制
        ${shimCopyCode}
        // 2) 全局库复制
        window.$ = window.jQuery = p.$;
        window._ = p._;
        window.toastr = p.toastr;
        window.Vue = p.Vue;
        window.VueRouter = p.VueRouter;
        if (p.z) window.z = p.z;
        if (p.YAML) window.YAML = p.YAML;
        window.klona = p.klona || function klona(obj) {
            if (typeof structuredClone === 'function') {
                try { return structuredClone(obj); } catch (e) {}
            }
            if (p._ && p._.cloneDeep) return p._.cloneDeep(obj);
            return JSON.parse(JSON.stringify(obj));
        };
        // 3) TavernHelper 对象（MVU bundle 通过 globalThis.TavernHelper 访问 API）
        window.TavernHelper = {};
        var shimObj = p.__mvu_shims__ || {};
        for (var key in shimObj) {
            if (Object.prototype.hasOwnProperty.call(shimObj, key)) {
                window.TavernHelper[key] = shimObj[key];
            }
        }
        window.TavernHelper._bind = window.TavernHelper._bind || {};
        // [v221] 同款补全：_bind 去下划线挂裸全局（对齐 JSR predefine.js:14-18）
        try {
            var __mvubind = window.TavernHelper._bind;
            for (var __mvubk in __mvubind) {
                var __mvubare = String(__mvubk).replace(/^_/, '');
                if (__mvubare === __mvubk) continue;
                if (typeof window[__mvubare] !== 'function') window[__mvubare] = __mvubind[__mvubk].bind(window);
            }
        } catch (__mvue) { /* noop */ }
        // 4) fetch 修复（blob iframe 中根相对路径）
        var originalFetch = window.fetch;
        window.fetch = function (url, options) {
            try {
                if (typeof url === 'string' && url.startsWith('/')) {
                    url = parentOrigin + url;
                }
            } catch (e) {}
            return originalFetch.call(window, url, options);
        };
        // 5) SillyTavern 代理（ctx + 顶层静态成员合并）
        var __stCtx = null, __stProxy = null;
        Object.defineProperty(window, 'SillyTavern', {
            get: function () {
                var ST = p.SillyTavern;
                if (!ST) return undefined;
                var ctx = ST.getContext();
                if (!__stProxy || __stCtx !== ctx) {
                    __stCtx = ctx;
                    __stProxy = new Proxy(ctx, {
                        get: function (target, prop) {
                            if (prop in target) return target[prop];
                            var v = ST[prop];
                            return (typeof v === 'function') ? v.bind(ST) : v;
                        },
                        has: function (target, prop) {
                            return (prop in target) || (prop in ST);
                        }
                    });
                }
                return __stProxy;
            },
            configurable: true
        });
        // 6) Mvu 代理（MVU 设置后暴露到 parent）
        Object.defineProperty(window, 'Mvu', {
            get: function () { return p.Mvu; },
            set: function (v) { p.Mvu = v; try { p.__rikkaMvuOwner = window.name; } catch (e) { /* noop */ } },
            configurable: true
        });
        // 7) iframe 名称（对齐 JS-Slash-Runner 的 script iframe 命名 → getScriptId 解析）
        window.name = 'TH-script--MVU变量框架--mvu-standalone';
        window.addEventListener('pagehide', function () {
            try { if (p.__rikkaMvuOwner === window.name) { delete p.Mvu; delete p.__rikkaMvuOwner; } } catch (e) {}
        });
        console.log('[MVU-Iframe] shim globals injected');
    } catch (err) {
        console.error('[MVU-Iframe] bootstrap failed', err);
    }
})();
<\/script>
<script src="${parentOrigin}/st-runtime/vendor/lib/vue.runtime.global.prod.js"><\/script>
<script src="${parentOrigin}/st-runtime/vendor/lib/vue-router.global.prod.js"><\/script>
</head>
<body>
<script type="module">
try {
    try {
        const zod_module = await import(${JSON.stringify(parentOrigin + '/st-runtime/vendor/lib/zod.esm.js')});
        window.z = zod_module; // 命名空间本体（含 .z 与全部顶层成员，JSR 同款语义）
    } catch (e) { console.warn('[MVU-Iframe] zod load failed:', e.message); }
    try {
        const YAML_module = await import(${JSON.stringify(parentOrigin + '/st-runtime/vendor/lib/yaml.esm.js')});
        window.YAML = YAML_module.default || YAML_module;
    } catch (e) { console.warn('[MVU-Iframe] yaml load failed:', e.message); }
    await import(${JSON.stringify(MVU_BUNDLE_URL)});
    console.log('[MVU-Iframe] MVU bundle loaded');
    window.parent.postMessage({ type: 'mvu-standalone-loaded' }, '*');
} catch (err) {
    console.error('[MVU-Iframe] bundle load failed:', err);
    window.parent.postMessage({ type: 'mvu-standalone-error', error: String((err && err.message) || err) }, '*');
}
<\/script>
</body>
</html>`;
}

function createMvuIframe() {
    if (mvuIframe) {
        // [v245 BUGFIX F1] 同款收口：清掉上一个 MVU iframe 的 guard 定时器再摘除。
        try { if (mvuIframe.__rikkaGuardTimer) clearInterval(mvuIframe.__rikkaGuardTimer); } catch (_e) { /* noop */ }
        try { mvuIframe.remove(); } catch (_e) { /* noop */ }
        mvuIframe = null;
    }
    const html = buildMvuIframeHtml();
    const blob = new Blob([html], { type: 'text/html' });
    const blobUrl = URL.createObjectURL(blob);
    const iframe = document.createElement('iframe');
    iframe.src = blobUrl;
    iframe.id = 'mvu-runtime-iframe';
    iframe.style.cssText = 'display:none;width:0;height:0;border:none;position:absolute;pointer-events:none;';
    document.body.appendChild(iframe);
    iframe.addEventListener('load', () => {
        try { URL.revokeObjectURL(blobUrl); } catch (_e) { /* noop */ }
        console.log(`${LOG_PREFIX} MVU iframe loaded`);
    });
    // [v218] 同款宿主侧兜底注入：MVU bundle 顶层也裸调 eventOn(...)，不能只靠 iframe 内 self-copy。
    const pushMvuShims = () => pushRuntimeGlobals(iframe.contentWindow);
    pushMvuShims();
    iframe.addEventListener('load', pushMvuShims);
    [50, 250, 800].forEach((ms) => setTimeout(pushMvuShims, ms));
    iframe.__rikkaGuardTimer = guardChildGlobals(iframe.contentWindow);

    mvuIframe = iframe;
    return iframe;
}

// MVU/脚本语义：会话就绪通知（chat_changed / chat_id_changed）。
// 注意：chat_completion_settings_ready 是「生成前由宿主填充 messages」的语义，
// 不能在 chat ready 时无参 emit；旧的无参 emit 会让 ST-Prompt-Template 的 handler 取 data.messages 直接崩。
function notifyChatReady() {
    // [batch17] ST 里 CHAT_CHANGED 的常量值是 'chat_id_changed'（见 shims/constants.js:21），
    // 以前这里只发 'chat_changed' 且载荷只有 chatId（可能还是 undefined）→ 两边的名字都对不上。
    // 现在两个名字都发，并带上 { chatId, messages }，监听方拿到的至少是个对象。
    const chatChangedPayload = { chatId: String(state.chatId == null ? '' : state.chatId), messages: Array.isArray(state.chat) ? state.chat : [] };
    try { eventSource.emit('chat_changed', chatChangedPayload); } catch (_e) { /* noop */ }
    try { eventSource.emit('chat_id_changed', chatChangedPayload); } catch (_e) { /* noop */ }
}

function waitForMvu(timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
        if (window.Mvu) { resolve(window.Mvu); return; }
        const started = Date.now();
        let earlyError = null;
        // 移植自 mvu-sillytavern-extension：监听 MVU iframe 的加载/错误消息，
        // 让「bundle 加载失败」立即可见（而不是静默等超时）。
        const onMessage = (ev) => {
            const d = ev && ev.data;
            if (!d || typeof d !== 'object') return;
            if (d.type === 'mvu-iframe-log') {
                // [v205] MVU iframe 的 console/未捕获错误 → 宿主日志（此前完全不可见）
                try { callBridge('log', String(d.level || 'debug'), `[MVU-iframe] ${String(d.text || '')}`); } catch (_e) { /* noop */ }
                return;
            }
            if (d.type === 'mvu-standalone-loaded') {
                console.log(`${LOG_PREFIX} MVU iframe reported loaded, waiting for window.Mvu ...`);
                try { ensureTavernHelperDom(['mvu-standalone']); } catch (_e) { /* noop */ }
                forceMvuPreference();
                // [v205] 快速自检：bundle 加载后 3s 若仍无 window.Mvu，立刻上报中间量（不等 30s 超时）
                setTimeout(() => {
                    if (window.Mvu) return;
                    try {
                        const diag = mvuIframe && mvuIframe.contentWindow && mvuIframe.contentWindow.__rikkaMvuSelfCheck
                            ? mvuIframe.contentWindow.__rikkaMvuSelfCheck() : 'no-selfcheck';
                        console.warn(`${LOG_PREFIX} MVU selfcheck (no window.Mvu 3s after bundle loaded): ${diag}`);
                    } catch (e) { console.warn(`${LOG_PREFIX} MVU selfcheck failed: ${(e && e.message) || e}`); }
                }, 3000);
            } else if (d.type === 'mvu-standalone-error') {
                earlyError = new Error(String(d.error || 'mvu-standalone-error'));
            }
        };
        window.addEventListener('message', onMessage);
        const cleanup = () => {
            window.removeEventListener('message', onMessage);
            if (mvuPollTimer) { clearInterval(mvuPollTimer); mvuPollTimer = null; }
        };
        mvuPollTimer = setInterval(() => {
            if (window.Mvu) {
                cleanup();
                mvuReady = true;
                resolve(window.Mvu);
                return;
            }
            // [v218.1] 卡片自带 MVU 的采纳路径（见 adoptScriptIframeMvu 注释）。
            // 只在父窗口还看不到 Mvu 时才走，避免抢掉正常路径。
            try {
                const adopted = adoptScriptIframeMvu();
                if (adopted) {
                    window.Mvu = adopted;
                    cleanup();
                    mvuReady = true;
                    console.log(`${LOG_PREFIX} MVU adopted from script iframe (version=${String(adopted && adopted.version || '')})`);
                    emitToHost('mvu_ready', { source: 'script-adopted', version: String(adopted && adopted.version || '') });
                    resolve(window.Mvu);
                    return;
                }
            } catch (_e) { /* noop */ }
            if (earlyError) {
                cleanup();
                reject(earlyError);
                return;
            }
            if (Date.now() - started > timeoutMs) {
                cleanup();
                // [v205] 超时不再只有一句话：把 iframe 自检结果一起带出来
                let diag = 'n/a';
                try {
                    diag = (mvuIframe && mvuIframe.contentWindow && mvuIframe.contentWindow.__rikkaMvuSelfCheck)
                        ? mvuIframe.contentWindow.__rikkaMvuSelfCheck() : 'no-selfcheck';
                } catch (e) { diag = 'ERR ' + ((e && e.message) || e); }
                reject(new Error(`MVU init timeout after ${timeoutMs}ms (window.Mvu not found; iframe=${mvuIframe ? 'created' : 'none'}; diag=${diag})`));
            }
        }, 80);
    });
}

let mvuLoading = false;
let mvuReloadQueued = false;
async function loadMvu() {
    if (mvuLoading) {
        // [v235 I4] 旧实现直接 return：会话切换时若上一次装载仍在飞，新卡这一路会被彻底丢掉。
        // 改为记一个补跑标记，等 in-flight 结束后再跑一次。
        mvuReloadQueued = true;
        console.log(`${LOG_PREFIX} loadMvu queued (already in flight)`);
        return;
    }
    mvuLoading = true;
    try {
    mvuReady = false;
    if (window.Mvu) {
        mvuReady = true;
        console.log(`${LOG_PREFIX} MVU already available (provided by card scripts)`);
        emitToHost('mvu_ready', { source: 'script', version: String(window.Mvu?.version || '') });
        setTimeout(() => notifyChatReady(), 150);
        return;
    }
    if (mvuFromScripts) {
        console.log(`${LOG_PREFIX} waiting MVU from card scripts ...`);
        // [v218.1] 先试一次认领：卡片 bundle 可能已经建好，只是没走我们的 setter
        try {
            const pre = adoptScriptIframeMvu();
            if (pre) {
                window.Mvu = pre;
                mvuReady = true;
                console.log(`${LOG_PREFIX} MVU adopted from script iframe (early, version=${String(pre.version || '')})`);
                emitToHost('mvu_ready', { source: 'script-adopted', version: String(pre.version || '') });
                setTimeout(() => notifyChatReady(), 150);
                return;
            }
        } catch (_e) { /* noop */ }
        try {
            await waitForMvu(2500);
            mvuReady = true;
            console.log(`${LOG_PREFIX} MVU ready (card scripts)`);
            emitToHost('mvu_ready', { source: 'script', version: String(window.Mvu?.version || '') });
        setTimeout(() => notifyChatReady(), 150);
            return;
        } catch (e) {
            console.warn(`${LOG_PREFIX} card-script MVU not ready (${(e && e.message) || e}), fallback to bundled MVU`);
        }
    }
    console.log(`${LOG_PREFIX} loading bundled MVU bundle ...`);
    // v206：身份 div 必须在 bundle 初始化 store 之前就位（它只在那一次读 DOM 决定 should_enable）
    try { ensureTavernHelperDom(['mvu-standalone']); } catch (_e) { /* noop */ }
    createMvuIframe();
    // v206：再加一道保险——把首选实现广播几次（覆盖 store 晚建 / 容器被重建的情况）
    for (const d of [600, 1500, 3000, 6000, 12000]) setTimeout(() => forceMvuPreference(), d);
    try {
        await waitForMvu();
        mvuReady = true;
        console.log(`${LOG_PREFIX} MVU ready (bundled)`);
        emitToHost('mvu_ready', { source: 'bundle', version: String(window.Mvu?.version || '') });
        setTimeout(() => notifyChatReady(), 150);
    } catch (e) {
        console.warn(`${LOG_PREFIX} bundled MVU not ready:`, e);
        emitToHost('mvu_error', {
            error: String((e && e.message) || e || 'unknown'),
            hasIframe: !!mvuIframe,
            hasMvu: !!window.Mvu,
        });
        // 瞬态失败自愈：自动重试一次（再失败则等待用户手动 __rikkaMvuReload）
        if (mvuAutoRetries < 1) {
            mvuAutoRetries += 1;
            console.log(`${LOG_PREFIX} scheduling MVU auto-retry #${mvuAutoRetries} in 8s ...`);
            setTimeout(() => { if (!window.Mvu) loadMvu(); }, 4000);
        }
    }
    } finally {
        mvuLoading = false;
        if (mvuReloadQueued) {
            mvuReloadQueued = false;
            if (!window.Mvu) setTimeout(() => { try { loadMvu(); } catch (_e) { /* noop */ } }, 300);
        }
    }
}

window.__rikkaMvuReload = () => { mvuAutoRetries = 0; return loadMvu(); };
window.__rikkaMvuStatus = () => ({ ready: mvuReady, hasIframe: !!mvuIframe, hasMvu: !!window.Mvu });

// ---------------------------------------------------------------- Panel 模式（C4：消息内嵌渲染宿主）
/**
 * [v214] 面板模式补 ST 聊天 DOM 桩：#send_textarea / #send_but / #send_form。
 * 卡内表单（如魔法少女 RS[0]）写 `window.parent.document.querySelector('#send_textarea')`，
 * 面板 WebView 里 window.parent === window；补桩后点 #send_but 即经 RikkaBridge.sendUserMessage
 * 交宿主以 user 身份入栈（此前必走剪贴板兜底 + throw 'SillyTavern UI not found.'）。
 */
function ensurePanelChatDomStubs() {
    try {
        const host = document.body || document.documentElement;
        if (!host) return;
        if (!document.getElementById('send_textarea')) {
            const ta = document.createElement('textarea');
            ta.id = 'send_textarea';
            ta.setAttribute('rows', '1');
            ta.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:1px;opacity:0;pointer-events:none;';
            host.appendChild(ta);
        }
        if (!document.getElementById('send_form')) {
            // 必须是真正的 <form>：卡的提交按钮经常调用 form.submit()/requestSubmit()，
            // 旧实现用 <div> 根本没有 submit 方法，点击只会变颜色。
            const form = document.createElement('form');
            form.id = 'send_form';
            form.style.cssText = 'display:none;';
            host.appendChild(form);
        }
        const panelToast = (message) => {
            try {
                let el = document.getElementById('rikka-panel-toast');
                if (!el) {
                    el = document.createElement('div');
                    el.id = 'rikka-panel-toast';
                    el.style.cssText = 'position:fixed;left:12px;right:12px;bottom:12px;z-index:2147483647;padding:8px 12px;border-radius:8px;background:#5c1f24;color:#ffe4e6;font:12px/1.5 sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.35);';
                    (document.body || document.documentElement).appendChild(el);
                }
                el.textContent = String(message || '操作失败');
                el.style.display = 'block';
                clearTimeout(el.__rikkaHideTimer);
                el.__rikkaHideTimer = setTimeout(() => { el.style.display = 'none'; }, 4200);
            } catch (_e) { /* noop */ }
        };
        const submitPanelSend = async (source) => {
            const ta = document.getElementById('send_textarea');
            const btn = document.getElementById('send_but');
            const text = ta && typeof ta.value === 'string' ? ta.value : '';
            if (!text.trim()) return false;
            if (btn && btn.dataset.rikkaBusy === '1') return false;
            if (btn) btn.dataset.rikkaBusy = '1';
            try {
                const result = await stCtx.sendMessage(text);
                if (result && result.ok) {
                    if (ta) ta.value = '';
                    try { if (window.RikkaPanel && RikkaPanel.log) RikkaPanel.log('debug', '[panel] ' + source + ' → host ok len=' + text.length); } catch (_e) { /* noop */ }
                    return true;
                }
                panelToast('发送失败：' + ((result && result.error) || '宿主未响应'));
                return false;
            } catch (e) {
                panelToast('发送失败：' + String((e && e.message) || e));
                return false;
            } finally {
                if (btn) btn.dataset.rikkaBusy = '0';
            }
        };
        const form = document.getElementById('send_form');
        if (form && !form.__rikkaSubmitBound) {
            form.__rikkaSubmitBound = true;
            form.addEventListener('submit', (ev) => {
                try { ev.preventDefault(); } catch (_e) { /* noop */ }
                submitPanelSend('form.submit-event');
            });
            // HTMLFormElement.submit() 原生实现不会触发 submit 事件：卡的按钮经常直接调它，
            // 必须覆盖成我们的命令入口，否则仍然“只变颜色没有动作”。
            try {
                form.submit = function () { submitPanelSend('form.submit()'); };
                form.requestSubmit = function () { form.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true })); };
            } catch (_e) { /* noop */ }
        }
        if (!document.getElementById('send_but')) {
            const btn = document.createElement('button');
            btn.id = 'send_but';
            btn.type = 'button';
            btn.style.cssText = 'display:none;';
            btn.addEventListener('click', () => { submitPanelSend('send_but'); });
            host.appendChild(btn);
        }
    } catch (_e) { /* noop */ }
}


// ---------------------------------------------------------------- [v243 BUGFIX D1] 面板 vh 转换
/**
 * 把面板 HTML 里的 `min-height: <n>vh` 换算成 `--rikka-panel-vh`（固定 px），
 * 打断「面板越高 -> vh 越大 -> 内容越高 -> 面板越高」的自激回路。
 *
 * 真机取证（tavern-runtime.log 10:00:59.082 起，每 ~15ms 一跳、每跳 +64px，6 秒涨到 21389 才停）：
 *   [panel] height=1475 -> 1539 -> 1603 -> ... -> 21379 -> 21389
 * 卡的面板 CSS 含 `min-height: 100vh`；面板 WebView 的高度由 ResizeObserver 上报，
 * 而 vh 又是相对**面板自身**的高度计算的 -> 结构上不可能收敛。
 *
 * 酒馆助手（JS-Slash-Runner/src/panel/render/iframe.ts:5-79）就是这么做的：
 * 把所有 min-height 里的 vh 换成 `var(--TH-viewport-height)`，
 * 而该变量由 adjust_viewport.js 固定为 **父窗口** 的 innerHeight（一个不随面板高度变化的值）。
 * rikkaST 的面板没有父窗口，改用宿主注入的稳定视口高度（见 installPanelMode 开头）。
 */
function convertPanelVh(content) {
    if (typeof content !== 'string' || content.indexOf('vh') < 0) return content;
    const hasCss = /min-height\s*:\s*[^;{}]*\d+(?:\.\d+)?vh/i.test(content);
    const hasInline = /style\s*=\s*(["'])[\s\S]*?min-height\s*:\s*[^;]*?\d+(?:\.\d+)?vh[\s\S]*?\1/i.test(content);
    const hasJs = /(\.style\.minHeight\s*=\s*(["']))([\s\S]*?vh)(\2)/i.test(content)
        || /(setProperty\s*\(\s*(["'])min-height\2\s*,\s*(["']))([\s\S]*?vh)(\3\s*\))/i.test(content);
    if (!hasCss && !hasInline && !hasJs) return content;

    const toVar = (value) => String(value).replace(/(\d+(?:\.\d+)?)vh\b/gi, (match, num) => {
        const parsed = parseFloat(num);
        if (!isFinite(parsed)) return match;
        const expr = 'var(--rikka-panel-vh)';
        if (parsed === 100) return expr;
        return 'calc(' + expr + ' * ' + (parsed / 100) + ')';
    });

    // 1) CSS 声明块 / <style> / 内联样式串：min-height: ...vh
    content = content.replace(/(min-height\s*:\s*)([^;{}]*?\d+(?:\.\d+)?vh)(?=\s*[;}])/gi,
        (_m, prefix, value) => prefix + toVar(value));
    // 2) 行内 style="...min-height:...vh..."
    content = content.replace(/(style\s*=\s*(["']))([^"']*?)(\2)/gi,
        (match, prefix, _q, styleContent, suffix) => {
            if (!/min-height\s*:\s*[^;]*vh/i.test(styleContent)) return match;
            const replaced = styleContent.replace(/(min-height\s*:\s*)([^;]*?\d+(?:\.\d+)?vh)/gi,
                (_m, p1, p2) => p1 + toVar(p2));
            return prefix + replaced + suffix;
        });
    // 3) JS: element.style.minHeight = "...vh"
    content = content.replace(/(\.style\.minHeight\s*=\s*(["']))([\s\S]*?)(\2)/gi,
        (match, prefix, _q, val, suffix) => {
            if (!/\b\d+(?:\.\d+)?vh\b/i.test(val)) return match;
            return prefix + toVar(val) + suffix;
        });
    // 4) JS: element.style.setProperty('min-height', "...vh")
    content = content.replace(/(setProperty\s*\(\s*(["'])min-height\2\s*,\s*(["']))([\s\S]*?)(\3\s*\))/gi,
        (match, prefix, _q1, _q2, val, suffix) => {
            if (!/\b\d+(?:\.\d+)?vh\b/i.test(val)) return match;
            return prefix + toVar(val) + suffix;
        });
    return content;
}

/**
 * [v243 BUGFIX C1] 把已安装第三方扩展的**元数据**同步进当前 realm。
 * 面板是独立 WebView，主运行时的 window.__rikkaSt.extensions 不会同步过来，
 * 于是卡脚本的「插件是否已安装」探测（getExtensionManifest 等）永远查不到 →
 * 用户明明装了酒馆助手/提示词模板也被判为未安装。
 * 只同步元数据，不重新执行插件模块（避免重复注册全局事件/计时器）。
 */
function syncThirdPartyExtensionMetadata() {
    try {
        const raw = callBridge('getThirdPartyExtensionsJson');
        const list = raw ? JSON.parse(raw) : [];
        if (!Array.isArray(list)) return;
        window.__rikkaSt = window.__rikkaSt || {};
        window.__rikkaSt.extensions = list;
        // [v243 BUGFIX C2] 广播刷新：st-compat/extensions.js 的 extensionTypes（live 快照）
        // 与各 shim 的 live binding 都靠这个事件重新取数。
        try { window.dispatchEvent(new Event('rikka-st-refresh')); } catch (_e2) { /* noop */ }
    } catch (_e) { /* noop */ }
}

function installPanelMode() {
    const root = document.getElementById('rikka-panel-root') || document.body;
    // [v243 BUGFIX D1] 稳定视口高度：绝不能用 window.innerHeight（那就是被 ResizeObserver
    // 反复改写的面板自身高度，用它做 vh 基准 = 自激回路）。优先用宿主注入值，其次屏幕高度。
    try {
        let vhPx = 0;
        if (typeof window.__rikkaViewportHeightPx === 'number' && window.__rikkaViewportHeightPx > 0) {
            vhPx = window.__rikkaViewportHeightPx;
        } else if (window.screen && window.screen.height > 0) {
            vhPx = Math.round(window.screen.height);
        }
        if (vhPx > 0) {
            document.documentElement.style.setProperty('--rikka-panel-vh', vhPx + 'px');
        }
    } catch (_e) { /* noop */ }
    // [v243 BUGFIX C1] 面板独立 realm：同步已安装扩展元数据（不重新执行插件模块）
    syncThirdPartyExtensionMetadata();
    // [问题 2-D] panel 的 eventSource 是独立 realm：关键事件必须经宿主转到主运行时，
    // 否则卡脚本 emit('message_sent') 后主运行时的扩展永远收不到。宿主回灌事件用 flag 防环。
    try {
        if (!eventSource.__rikkaPanelForwardPatched) {
            eventSource.__rikkaPanelForwardPatched = true;
            const origEmit = eventSource.emit.bind(eventSource);
            const forwardedEvents = new Set([
                'message_sent', 'message_received', 'message_updated', 'message_deleted',
                'message_swiped', 'message_swipe_deleted', 'generation_started', 'generation_stopped',
                'generation_ended',
            ]);
            eventSource.emit = function (eventName, ...args) {
                try {
                    if (!window.__rikkaIncomingHostEvent &&
                        forwardedEvents.has(String(eventName)) &&
                        window.RikkaBridge && typeof RikkaBridge.emitHostEvent === 'function') {
                        const payload = args.length <= 1 ? (args[0] == null ? {} : args[0]) : { args: args };
                        RikkaBridge.emitHostEvent(String(eventName), JSON.stringify(payload));
                    }
                } catch (_eFwd) { /* noop */ }
                return origEmit(eventName, ...args);
            };
        }
    } catch (_eFwdPatch) { /* noop */ }
    // ---------------------------------------------------------------- [v243 BUGFIX A4] 面板自识别
    /**
     * 面板是「某条消息」的渲染宿主：本条消息 id 由 Kotlin 侧注入
     * （MessageHtmlBlock 在挂 HTML 前 evaluateJavascript 写 window.__rikkaPanelMessageKey）。
     * 用它把 chat 数组里对应的那条消息找出来；写死 message_id: -1 永远只指向最后一条消息。
     *
     * ⚠️ [v243 BUGFIX A4] 这个函数原来被误插在 `probe` 的**函数体内部**，
     * 于是 installPanelMode 作用域里的三处调用全部 ReferenceError（都被 catch 吞掉）：
     *   · readMvuVars()  → 永远返回 null → varsWait 永远超时 → 面板脚本永远拿不到变量、写不回变量；
     *   · emitRenderedEvent() → 永远不派发 panel_rendered → 扩展视图（记忆表格等）不刷新；
     *   · wrapPanelVarApi().note() → `[panel] varreq …` 诊断日志一条都不打。
     * 真机取证：tavern-runtime.log / MiniMax 版日志里
     * `[panel] varreq` / `[panel] rendered id=` **一条都没有**（而 `vars wait: timeout` 出现 3 次），
     * 正是这三处全部抛 ReferenceError 的特征。
     * 另外 `vars wait: timeout` 里打印的 `selfId=-1` 也是同一个原因（`typeof panelSelfId` 拿到 'undefined'），
     * 属于**假象**，不代表真的没匹配上楼层。
     */
    function panelSelfId() {
        try {
            const key = window.__rikkaPanelMessageKey;
            if (key) {
                const chat = (window.SillyTavern.getContext().chat) || [];
                for (let i = 0; i < chat.length; i++) {
                    if (chat[i] && String(chat[i].id) === String(key)) {
                        window.__rikkaPanelMessageKeyIndex = i;
                        return i;
                    }
                }
                if (typeof window.__rikkaPanelMessageKeyIndex === 'number') return window.__rikkaPanelMessageKeyIndex;
            }
        } catch (_e) { /* noop */ }
        return -1;
    }

    ensurePanelChatDomStubs();
    // 高度上报（以 #rikka-panel-root 真实内容几何为准，避免 documentElement.scrollHeight 被旧视口高度锁死导致折叠后无法收缩）
    let lastReportedHeight = -1;
    const measure = () => {
        if (!root) return 0;
        let maxChildBottom = 0;
        let maxDescendantBottom = 0;
        try {
            const rootRect = root.getBoundingClientRect();
            const rootTop = rootRect ? rootRect.top : 0;
            const children = Array.from(root.children || []);
            for (let i = 0; i < children.length; i++) {
                const c = children[i];
                if (!c) continue;
                const style = window.getComputedStyle ? window.getComputedStyle(c) : null;
                if (style && style.display === 'none') continue;
                const marginBottom = style ? (parseFloat(style.marginBottom) || 0) : 0;
                const bottom = (c.offsetTop || 0) + Math.max(c.scrollHeight || 0, c.offsetHeight || 0) + marginBottom;
                if (bottom > maxChildBottom) maxChildBottom = bottom;
                const cr = c.getBoundingClientRect ? c.getBoundingClientRect() : null;
                if (cr && cr.height > 0) {
                    const relBottom = (cr.bottom - rootTop) + marginBottom;
                    if (relBottom > maxDescendantBottom) maxDescendantBottom = relBottom;
                }
            }
        } catch (_e) { /* noop */ }
        // [10-19-21 通用悬浮窗适配] 检测楼层面板内展开的 position:fixed / position:absolute / <dialog open> 悬浮窗（如角色卡自带手机、地图、背包、状态弹窗）
        let maxFloatingOverlayH = 0;
        try {
            const candidates = document.querySelectorAll('dialog[open], [style*="fixed"], [style*="absolute"], .modal, .popup, [class*="modal"], [class*="popup"], [class*="phone"], [class*="map"], [id*="modal"], [id*="phone"], [id*="map"]');
            for (let i = 0; i < candidates.length; i++) {
                const el = candidates[i];
                if (!el || el === root || el.id === 'send_textarea') continue;
                const cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
                if (!cs || cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') === 0) continue;
                const pos = cs.position;
                const isDialog = el.tagName === 'DIALOG' && el.hasAttribute('open');
                if (pos === 'fixed' || pos === 'absolute' || isDialog) {
                    const r = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                    const hBox = r ? Math.ceil(Math.max(r.height || 0, r.bottom || 0)) : 0;
                    const hScroll = el.scrollHeight || 0;
                    const bestH = Math.max(hBox, hScroll);
                    if (bestH > 140) {
                        // 若是全屏或大型悬浮窗（手机/地图），确保至少给足 560px 可视高度，防止被折叠态 48px 高度裁切
                        const targetH = (pos === 'fixed' || isDialog) ? Math.max(bestH, 560) : bestH;
                        if (targetH > maxFloatingOverlayH) maxFloatingOverlayH = targetH;
                    }
                }
            }
        } catch (_eFloat) { /* noop */ }
        const rootBoxH = Math.ceil((root.getBoundingClientRect && root.getBoundingClientRect().height) || 0);
        const rootScrollH = root.scrollHeight || 0;
        const rootOffsetH = root.offsetHeight || 0;
        // 若 root 有子元素，优先使用子元素包络与 root 自身盒高度，避免 body/documentElement 因 WebView 视口高度锁死在旧高值
        const hasChildren = root.children && root.children.length > 0;
        const parts = hasChildren
            ? [rootBoxH, rootOffsetH, Math.ceil(maxChildBottom), Math.ceil(maxDescendantBottom), rootScrollH, maxFloatingOverlayH]
            : [rootScrollH, rootBoxH, rootOffsetH, maxFloatingOverlayH];
        const valid = parts.filter((v) => typeof v === 'number' && isFinite(v) && v >= 0);
        return valid.length ? Math.max.apply(null, valid) : 0;
    };
    // [v229 P0-1] 面板宽度测量/上报**整条链路已删除**（主人已拍板：渲染/面板气泡恢复「正常气泡模式」）。
    //   v228 的实现是「测量 -> 约束 -> 再测量」的闭环：ResizeObserver 量到的是**被约束后**的宽度，
    //   回写又让它更窄，结构上不可能稳定 -> 真机「宽->细->宽」自激振荡（宽->细->突然变宽->再缩）。
    //   这里连 measureWidth/lastReportedWidth 都不再保留，杜绝任何形式的宽度反馈回归。
    const report = () => {
        // [v229 P0-1] 只上报高度。宽度不允许参与任何布局（高度上报保留）。
        try {
            const h = measure() | 0;
            if (h === lastReportedHeight) return;
            lastReportedHeight = h;
            if (window.RikkaPanel && typeof RikkaPanel.panelResize === 'function') RikkaPanel.panelResize(h);
            if (window.RikkaPanel && typeof RikkaPanel.log === 'function') RikkaPanel.log('debug', '[panel] height=' + h);
        } catch (_e) { /* noop */ }
    };
    window.__rikkaPanelReport = report;
    // [问题 7/性能] KaTeX 按需加载：静态 HUD/表单面板不再为 270KB 数学库付出解析成本。
    let panelKatexPromise = null;
    const ensurePanelKatex = () => {
        if (window.katex) return Promise.resolve();
        if (panelKatexPromise) return panelKatexPromise;
        panelKatexPromise = new Promise((resolve) => {
            try {
                if (!document.querySelector('link[data-rikka-katex]')) {
                    const link = document.createElement('link');
                    link.rel = 'stylesheet';
                    link.href = './vendor/katex/katex.min.css';
                    link.setAttribute('data-rikka-katex', '1');
                    document.head.appendChild(link);
                }
                const script = document.createElement('script');
                script.src = './vendor/katex/katex.min.js';
                const done = () => {
                    try { mathRender.setKatex(window.katex); } catch (_eKatex) { /* noop */ }
                    resolve();
                };
                script.onload = done;
                script.onerror = done;
                setTimeout(done, 2500);
                document.head.appendChild(script);
            } catch (_e) { resolve(); }
        });
        return panelKatexPromise;
    };
    // [问题 7] 真实资源就绪：DOM readyState + fonts + 图片 complete + 高度连续两帧稳定。
    // 最多等 2.5s；超时仍回报 timedOut=true，让占位文案说真话（“仍有资源在加载”）。
    let panelSettleToken = 0;
    const nextFrame = () => new Promise((r) => {
        try { requestAnimationFrame(() => r()); } catch (_e) { setTimeout(r, 16); }
    });
    const waitPanelResourcesSettled = async () => {
        const token = ++panelSettleToken;
        const started = Date.now();
        try {
            await nextFrame();
            await nextFrame();
            try {
                if (document.fonts && document.fonts.ready) {
                    await Promise.race([document.fonts.ready, new Promise((r) => setTimeout(r, 800))]);
                }
            } catch (_eFonts) { /* noop */ }
            let lastHeight = -1;
            let stable = 0;
            while (Date.now() - started < 2500) {
                const imgs = Array.from(document.images || []).filter((img) => {
                    try { return img && !img.complete && img.loading !== 'lazy'; } catch (_e) { return false; }
                });
                const h = measure();
                if (h === lastHeight) stable++; else { stable = 0; lastHeight = h; }
                if (imgs.length === 0 && document.readyState === 'complete' && stable >= 2) break;
                await new Promise((r) => setTimeout(r, 80));
            }
            await nextFrame();
            if (token !== panelSettleToken) return;
            const pendingImages = Array.from(document.images || []).some((img) => {
                try { return img && !img.complete && img.loading !== 'lazy'; } catch (_e) { return false; }
            });
            const timedOut = pendingImages || document.readyState !== 'complete' || Date.now() - started >= 2500;
            try {
                if (window.RikkaPanel && typeof RikkaPanel.onPanelResourcesSettled === 'function') {
                    RikkaPanel.onPanelResourcesSettled(!!timedOut);
                }
            } catch (_e) { /* noop */ }
        } catch (_e) {
            try {
                if (window.RikkaPanel && typeof RikkaPanel.onPanelResourcesSettled === 'function') {
                    RikkaPanel.onPanelResourcesSettled(true);
                }
            } catch (_e2) { /* noop */ }
        }
    };
    window.__rikkaPanelAwaitSettled = () => { waitPanelResourcesSettled(); };
    const runScripts = async (container) => {
        // 消息挂载时页面早已 loaded：r0 类「DOMContentLoaded 后初始化」的脚本原生监听
        // 不会再触发，导致表单点不动。这里做补偿式补丁：已加载状态下注册的
        // DOMContentLoaded/load 监听直接异步补执行一次。
        const readyPatch = (t) => {
            if (!t || t.__rikkaReadyPatched) return;
            try { t.__rikkaReadyPatched = true; } catch (_e) { /* noop */ }
            const origAdd = t.addEventListener;
            t.addEventListener = function (type, listener, opts) {
                if ((type === 'DOMContentLoaded' || type === 'load') && typeof listener === 'function' && document.readyState !== 'loading') {
                    setTimeout(() => {
                        try { listener.call(t, new Event(type)); } catch (e) { safeWarn('deferred ' + type + ' handler failed', e); }
                    }, 0);
                    return;
                }
                return origAdd.call(t, type, listener, opts);
            };
        };
        try { readyPatch(document); readyPatch(window); } catch (_e) { /* noop */ }
        const scripts = Array.from(container.querySelectorAll('script'));
        for (const s of scripts) {
            const ns = document.createElement('script');
            for (const attr of Array.from(s.attributes)) ns.setAttribute(attr.name, attr.value);
            if (s.src) {
                await new Promise((res) => {
                    ns.onload = res;
                    ns.onerror = res;
                    setTimeout(res, 8000);
                    s.replaceWith(ns);
                });
            } else {
                ns.text = s.textContent || '';
                s.replaceWith(ns);
            }
        }
    };
    const stripFences = (s) => String(s == null ? '' : s)
        .replace(/^\s*```[a-zA-Z0-9_-]*\s*\r?\n/, '')
        .replace(/\r?\n```\s*$/, '')
        .replace(/^\s*```[a-zA-Z0-9_-]*\s*$/gm, '');
    const rewriteEmbeddedAssetUrls = (s) => String(s == null ? '' : s)
        .replace(
            /(\b(?:src|href|poster)\s*=\s*["'])(?:embeded:\/\/|embedded:\/\/|ccdefault:)([^"']+)(["'])/gi,
            (_m, p1, rel, p3) => p1 + 'https://appassets.androidplatform.net/charx_assets/lookup/' + encodeURIComponent(rel.replace(/^\/+/, '')) + p3
        )
        .replace(
            /(["'\(])(?:embeded:\/\/|embedded:\/\/|ccdefault:)([^"'\)\r\n]+)(["'\)])/gi,
            (_m, p1, rel, p3) => p1 + 'https://appassets.androidplatform.net/charx_assets/lookup/' + encodeURIComponent(rel.replace(/^\/+/, '')) + p3
        );
    const injectImgNoReferrerAndOrigSrc = (s) => String(s == null ? '' : s).replace(
        /<img\b([^>]*?)>/gi,
        (full, attrs) => {
            let newAttrs = attrs;
            if (!/\breferrerpolicy\s*=/i.test(newAttrs)) {
                newAttrs += ' referrerpolicy="no-referrer"';
            }
            if (!/\bdata-rikka-orig-src\s*=/i.test(newAttrs)) {
                const srcMatch = newAttrs.match(/\bsrc\s*=\s*(["'])([^"']+)\1/i);
                if (srcMatch && srcMatch[2]) {
                    const safeOrig = srcMatch[2].replace(/"/g, '&quot;');
                    newAttrs += ' data-rikka-orig-src="' + safeOrig + '"';
                }
            }
            return '<img' + newAttrs + '>';
        }
    );
    const stripBlockingExternalFonts = (s) => String(s == null ? '' : s)
        .replace(/@import\s+url\(\s*['"]?https?:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|use\.fontawesome\.com|kit\.fontawesome\.com)[^)'"]*['"]?\s*\)\s*;?/gi, '/* offline-font-stripped */')
        .replace(/<link\b[^>]*href\s*=\s*["']https?:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|use\.fontawesome\.com|kit\.fontawesome\.com)[^"']*["'][^>]*>/gi, '');
    const normalizeHtml = (html) => {
        const unfenced = stripBlockingExternalFonts(injectImgNoReferrerAndOrigSrc(rewriteEmbeddedAssetUrls(stripFences(html))));
        if (/<!doctype|<html[\s>]/i.test(unfenced)) {
            try {
                const doc = new DOMParser().parseFromString(unfenced, 'text/html');
                const headBits = Array.from(doc.head.querySelectorAll('style,link[rel="stylesheet"]')).map((n) => n.outerHTML).join('\n');
                return convertPanelVh(headBits + '\n' + doc.body.innerHTML);
            } catch (_e) { /* fallthrough */ }
        }
        return convertPanelVh(unfenced);
    };
    // [DIAG B + 立绘/CG自愈] 资源加载失败捕获与 <img> 自动多级回退自愈（capture 阶段）：
    // 1. 首屏已在 normalizeHtml 中注入 referrerpolicy="no-referrer" 与 data-rikka-orig-src，确保外部图床首帧即无 Referer 加载；
    // 2. 若仍失败，始终以 data-rikka-orig-src 作为基准构建候选链（绝不因中间尝试 lookup URL 而丢失原始外部图床地址）：
    //    - 外部图床 URL 优先重试跨扩展名（.webp/.png/.jpg），再回退本地 /charx_assets/lookup/<baseName>；
    //    - 相对路径 / 内嵌资产优先走 /charx_assets/lookup/<baseName>。
    const badLoads = [];
    window.addEventListener('error', (ev) => {
        try {
            const t2 = ev && ev.target;
            if (t2 && t2 !== window && t2.tagName === 'IMG') {
                const curSrc = String(t2.getAttribute('src') || t2.src || '');
                const origSrc = String(t2.dataset.rikkaOrigSrc || curSrc || '');
                if (origSrc && !t2.dataset.rikkaOrigSrc) {
                    t2.dataset.rikkaOrigSrc = origSrc;
                }
                const step = Number(t2.dataset.rikkaImgRetry || '0');
                if (!t2.referrerPolicy || t2.referrerPolicy !== 'no-referrer') {
                    try { t2.referrerPolicy = 'no-referrer'; } catch (_e) { /* noop */ }
                }
                if (origSrc && step < 5) {
                    let baseName = '';
                    try {
                        const cleanPath = decodeURIComponent(origSrc.split('?')[0].split('#')[0]);
                        baseName = cleanPath.split('/').pop() || '';
                    } catch (_e) {
                        baseName = origSrc.split('?')[0].split('#')[0].split('/').pop() || '';
                    }
                    const candidates = [];
                    const isExternalHttp = /^https?:\/\//i.test(origSrc) &&
                        !origSrc.includes('appassets.androidplatform.net') &&
                        !origSrc.includes('st.rikka.local');
                    if (isExternalHttp) {
                        const noQuery = origSrc.split('?')[0];
                        const extMatch = noQuery.match(/\.(png|webp|jpg|jpeg|gif)$/i);
                        if (extMatch) {
                            const curExt = extMatch[1].toLowerCase();
                            const stemUrl = noQuery.slice(0, -extMatch[0].length);
                            for (const altExt of ['webp', 'png', 'jpg']) {
                                if (altExt !== curExt) candidates.push(stemUrl + '.' + altExt);
                            }
                        }
                    }
                    if (baseName && baseName.length < 120 && !/[\r\n]/.test(baseName)) {
                        const lookupUrl = 'https://appassets.androidplatform.net/charx_assets/lookup/' + encodeURIComponent(baseName);
                        if (origSrc !== lookupUrl && !candidates.includes(lookupUrl)) {
                            candidates.push(lookupUrl);
                        }
                    }
                    const nextUrl = candidates[step];
                    if (nextUrl && nextUrl !== curSrc) {
                        t2.dataset.rikkaImgRetry = String(step + 1);
                        try { ev.stopImmediatePropagation(); } catch (_e) { /* noop */ }
                        t2.src = nextUrl;
                        setTimeout(report, 150);
                        return;
                    }
                }
            }
            if (t2 && t2 !== window && t2.tagName && (t2.tagName === 'LINK' || t2.tagName === 'SCRIPT' || t2.tagName === 'IMG')) {
                const url = String(t2.href || t2.src || '');
                let host = '';
                try { host = new URL(url).host; } catch (_e) { host = url.slice(0, 60); }
                const tag = String(t2.tagName) + ':' + host + url.split('/').pop();
                if (badLoads.indexOf(tag) < 0) badLoads.push(tag);
            }
        } catch (_e) { /* noop */ }
    }, true);
    // [DIAG B] 挂载后体检：MVU 是否可见、getMvuData 能否取到变量、脚本/样式数量、最终高度。
    // 排查「面板渲染异常」的关键事实，全部写进 tavern-runtime.log（[panel] 前缀）。
    const probe = (tag) => {
        try {
            if (!window.RikkaPanel || typeof RikkaPanel.log !== 'function') return;


            const nScripts = root.querySelectorAll('script').length;
            const nLinks = root.querySelectorAll('link[rel="stylesheet"]').length;
            let vars = 'no-Mvu';
            if (window.Mvu && typeof window.Mvu.getMvuData === 'function') {
                try {
                    const v = window.Mvu.getMvuData({ type: 'message', message_id: panelSelfId() });
                    if (v === null || v === undefined) { vars = 'null'; }
                    else {
                        const ks = Object.keys(v);
                        vars = 'keys=[' + ks.slice(0, 8).join(',') + (ks.length > 8 ? ',+more' : '') + '] hasStat=' + !!(v && v.stat_data);
                        if (v && v.stat_data && typeof v.stat_data === 'object') {
                            vars += ' stat=[' + Object.keys(v.stat_data).slice(0, 10).join(',') + ']';
                        }
                    }
                } catch (e2) { vars = 'ERR ' + String((e2 && e2.message) || e2); }
            }
            RikkaPanel.log('debug', '[panel] probe(' + tag + ') scripts=' + nScripts + ' cssLinks=' + nLinks +
                ' mvu=' + !!window.Mvu + ' vars=' + vars + ' height=' + measure() +
                (badLoads.length ? ' failedLoads=[' + badLoads.join('|') + ']' : ''));
        } catch (_e) { /* noop */ }
    };
    // [T4] 面板变量就绪后重渲染（批次十二）。
    // 现场：真机日志 probe(mount+1500) 报 vars=keys=[]，而 MVU 变量要到 ~6 秒后才就绪 ——
    // 面板脚本（状态栏类）在挂载那一刻读到的是空变量，之后也没有任何人再通知它，于是面板一直是空壳。
    // 面板模式下的 Mvu 只是 stub（见本文件末尾的 Mvu stub），Mvu.events.* 从来没有被派发过。
    // 处理：挂载后短时轮询变量（400ms × 最多 20 次 ≈ 8 秒），一旦非空就
    //   ① 派发 Mvu 事件（mag_variable_initialized / mag_variable_update_ended）—— 照顾监听式卡脚本；
    //   ② 只重挂一次（整棵面板重新执行）—— 照顾一次性渲染的卡脚本。
    const hasMvuVars = (v) => !!v && typeof v === 'object' && (Object.keys(v).length > 0 || !!v.stat_data);
    const readMvuVars = () => {
        // 优先通过轻量级 getMessageVariablesJson 同步变量，避免每次轮询触发 218KB 全量 getChatJson 跨桥序列化
        try { syncMessageVariablesOnly(); } catch (_e) { /* noop */ }
        try {
            if (window.Mvu && typeof window.Mvu.getMvuData === 'function') {
                return window.Mvu.getMvuData({ type: 'message', message_id: panelSelfId() });
            }
        } catch (_e) { /* noop */ }
        return null;
    };
    let varsReady = false;
    let varsWaitTimer = null;
    let lastPanelHtml = null;
    const varsWait = (html) => {
        lastPanelHtml = html;
        if (varsReady || varsWaitTimer) return;
        // 若面板根本未引用 MVU / getVariables / stat_data（例如纯立绘/CG卡片），无需启动 200ms 变量轮询与重挂载！
        if (!/Mvu|getMvuData|getVariables|stat_data|mag_variable/i.test(String(html || ''))) {
            varsReady = true;
            return;
        }
        // 挂载时变量就已经就绪 → 卡脚本已经拿到数据，不必等待、不必重挂
        if (hasMvuVars(readMvuVars())) { varsReady = true; return; }
        let ticks = 0;
        try { if (window.RikkaPanel && RikkaPanel.log) RikkaPanel.log('debug', '[panel] vars wait: start'); } catch (_e) { /* noop */ }
        varsWaitTimer = setInterval(() => {
            ticks++;
            const v = readMvuVars();
            if (hasMvuVars(v)) {
                clearInterval(varsWaitTimer); varsWaitTimer = null;
                varsReady = true;
                try {
                    if (window.RikkaPanel && RikkaPanel.log) {
                        RikkaPanel.log('debug', '[panel] vars ready after ' + ticks + ' ticks keys=[' +
                            Object.keys(v).slice(0, 8).join(',') + '] → remount');
                    }
                } catch (_e) { /* noop */ }
                try { window.dispatchEvent(new CustomEvent('mag_variable_initialized', { detail: v })); } catch (_e) { /* noop */ }
                try { window.dispatchEvent(new CustomEvent('mag_variable_update_ended', { detail: v })); } catch (_e) { /* noop */ }
                // 只重挂一次：整棵面板重新执行，让「一次性渲染」的卡脚本拿到变量
                try { if (lastPanelHtml && window.__rikkaPanelMount) window.__rikkaPanelMount(lastPanelHtml, 'remount'); } catch (_e) { /* noop */ }
                return;
            }
            if (ticks >= 30) {
                clearInterval(varsWaitTimer); varsWaitTimer = null;
                try {
                    if (window.RikkaPanel && RikkaPanel.log) {
                        let mvuState = 'n/a';
                        try { mvuState = window.__rikkaMvuStatus ? JSON.stringify(window.__rikkaMvuStatus()) : 'no-status'; } catch (_e2) { mvuState = 'ERR'; }
                        let selfId = -1;
                        try { selfId = (typeof panelSelfId === 'function') ? panelSelfId() : -1; } catch (_e3) { selfId = -1; }
                        RikkaPanel.log('debug', '[panel] vars wait: timeout (no vars) mvuStatus=' + mvuState + ' selfId=' + selfId);
                    }
                } catch (_e) { /* noop */ }
            }
        }, 200);
    };
        // [v218.1] 面板渲染完成后补发 ST 的 RENDERED 事件（见 __rikkaPanelMount 里的调用点）
        function emitRenderedEvent(tag) {
            try {
                const mid = panelSelfId();
                if (mid < 0) return;
                const chat = (window.SillyTavern && window.SillyTavern.getContext && window.SillyTavern.getContext().chat) || [];
                const msg = chat[mid];
                const isUser = !!(msg && msg.is_user);
                // 面板与主运行时是两个 realm（eventSource 各一份）→ 必须经 Kotlin 桥转到主运行时发射
                if (window.RikkaBridge && typeof RikkaBridge.emitEvent === 'function') {
                    RikkaBridge.emitEvent('panel_rendered', JSON.stringify({ messageId: mid, isUser: isUser }));
                }
                try { RikkaPanel.log('debug', '[panel] rendered id=' + mid + ' isUser=' + isUser + ' (' + tag + ')'); } catch (_e2) { /* noop */ }
            } catch (_e) { /* noop */ }
        }

    window.__rikkaPanelMount = (html, tag) => {
        const t = tag || 'mount';
        if (t === 'mount' && window.__rikkaPanelMountedHtml === html) {
            report();
            waitPanelResourcesSettled();
            return;
        }
        window.__rikkaPanelMountedHtml = html;
        try {
            if (!window.__rikkaPanelHtmlDumps) window.__rikkaPanelHtmlDumps = [];
            const dus = window.__rikkaPanelHtmlDumps;
            if (dus.length < 3) {
                const head = String(html).replace(/\s+/g, ' ').slice(0, 500);
                if (dus.indexOf(head) < 0) { dus.push(head); RikkaPanel.log('debug', '[panel] html head: ' + head); }
            }
        } catch (_e) { /* noop */ }
        try {
            root.innerHTML = normalizeHtml(html);
            Promise.resolve()
                .then(() => (/katex|\$\$|\\\(|\\\[/i.test(String(html || '')) ? ensurePanelKatex() : null))
                .then(() => runScripts(root))
                .then(() => {
                    report();
                    setTimeout(report, 120);
                    setTimeout(report, 600);
                    setTimeout(() => { waitPanelResourcesSettled(); }, 120);
                    probe(t);
                    setTimeout(() => probe(t + '+1500'), 1500);
                    // [v218.1] recon-ext-compat §2.3：RENDERED 类事件由面板渲染完成后自发。
                    // 扩展侧（记忆增强表格 index.js:1072、ST-PT handler.ts:986/964、JSR macro_like.ts:127-128）
                    // 全靠 USER/CHARACTER_MESSAGE_RENDERED 刷新视图；此前宿主一条都不发 → 表格视图不刷新。
                    // 载荷对齐 ST：单个 message_id。
                    emitRenderedEvent(t);
                    varsWait(html);
                })
                .catch((e) => {
                    console.warn(`${LOG_PREFIX} panel scripts failed:`, e);
                    try { if (window.RikkaPanel) RikkaPanel.onPanelError(String((e && e.message) || e)); } catch (_e2) { /* noop */ }
                });
        } catch (e) {
            console.error(`${LOG_PREFIX} panel mount failed:`, e);
            try { if (window.RikkaPanel) RikkaPanel.onPanelError(String((e && e.message) || e)); } catch (_e2) { /* noop */ }
        }
    };
    // [BUGFIX B] 面板内脚本报错必须带位置与栈，否则只剩一句无信息量的 message
    const describe = (ev, kind) => {
        try {
            const msg = String((ev && (ev.message || (ev.error && ev.error.message) || ev.reason || ev.error)) || 'unknown');
            const stack = (ev && ev.error && ev.error.stack) || (ev && ev.reason && ev.reason.stack) || '';
            const where = ev && ev.filename ? ' @ ' + String(ev.filename).split('/').pop() + ':' + ev.lineno + ':' + ev.colno : '';
            const tail = stack ? ' | ' + String(stack).split('\n').slice(0, 3).join(' <- ') : '';
            return '[' + kind + '] ' + msg + where + tail;
        } catch (_e) { return '[' + kind + '] unprintable'; }
    };
    window.addEventListener('error', (ev) => {
        try { if (window.RikkaPanel && RikkaPanel.onPanelError) RikkaPanel.onPanelError(describe(ev, 'panel-error')); } catch (_e) { /* noop */ }
    });
    window.addEventListener('unhandledrejection', (ev) => {
        try { if (window.RikkaPanel && RikkaPanel.onPanelError) RikkaPanel.onPanelError(describe(ev, 'panel-rejection')); } catch (_e) { /* noop */ }
    });
    if (window.ResizeObserver) {
        try { new ResizeObserver(report).observe(document.documentElement); } catch (_e) { /* noop */ }
        try { new ResizeObserver(report).observe(document.body); } catch (_e) { /* noop */ }
        try { new ResizeObserver(report).observe(root); } catch (_e) { /* noop */ }
    }
    if (window.MutationObserver) {
        try {
            let scheduled = false;
            const mo = new MutationObserver(() => {
                if (scheduled) return;
                scheduled = true;
                requestAnimationFrame(() => { scheduled = false; report(); });
            });
            mo.observe(root, { childList: true, subtree: true, attributes: true, characterData: true });
        } catch (_e) { /* noop */ }
    }
    try { if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => report()).catch(() => {}); } catch (_e) { /* noop */ }
    window.addEventListener('load', () => { report(); setTimeout(report, 250); });
    // 捕获阶段监听 details 展开/收起（toggle 不冒泡）、图片/子资源加载、CSS 过渡/动画完成与表单输入，确保高度实时双向更新
    const scheduleDynamicReport = () => {
        try {
            requestAnimationFrame(() => {
                report();
                setTimeout(report, 80);
            });
        } catch (_e) { report(); }
    };
    document.addEventListener('toggle', scheduleDynamicReport, true);
    document.addEventListener('load', scheduleDynamicReport, true);
    document.addEventListener('transitionend', scheduleDynamicReport, true);
    document.addEventListener('animationend', scheduleDynamicReport, true);
    document.addEventListener('input', scheduleDynamicReport, true);
    document.addEventListener('click', () => {
        scheduleDynamicReport();
        setTimeout(report, 180);
        setTimeout(report, 420);
    }, true);
    document.addEventListener('pointerup', () => {
        setTimeout(report, 60);
        setTimeout(report, 240);
    }, true);
    const panelIntervals = new Set();
    const origSetInterval = window.setInterval.bind(window);
    const origClearInterval = window.clearInterval.bind(window);
    window.setInterval = function (fn, ms, ...args) {
        const id = origSetInterval(fn, ms, ...args);
        panelIntervals.add(id);
        return id;
    };
    window.clearInterval = function (id) {
        panelIntervals.delete(id);
        return origClearInterval(id);
    };
    window.__rikkaQuiescePanel = () => {
        try {
            if (varsWaitTimer) { origClearInterval(varsWaitTimer); varsWaitTimer = null; }
            for (const id of Array.from(panelIntervals)) {
                origClearInterval(id);
            }
            panelIntervals.clear();
            report();
        } catch (_e) { /* noop */ }
    };
    window.__rikkaSyncAfterHostRestart = () => {
        try {
            syncThirdPartyExtensionMetadata();
            invalidateChatCache();
            refreshAll();
            report();
        } catch (_e) { /* noop */ }
    };
    // [P0-2 修复] 高度稳定 6 次后自动停止 1500ms 轮询（后续由 ResizeObserver / MutationObserver 驱动），消除静置状态下永久强制重排
    let stableReportTicks = 0;
    const reportInterval = setInterval(() => {
        const prev = lastReportedHeight;
        report();
        if (lastReportedHeight > 48 && lastReportedHeight === prev) {
            stableReportTicks++;
            if (stableReportTicks >= 6) clearInterval(reportInterval);
        } else {
            stableReportTicks = 0;
        }
    }, 1500);
    setTimeout(report, 100);
    // Mvu stub：与 MagVarUpdate bundle 同形（getMvuData → getVariables / events 常量表）。
    // panel 模式不加载 MVU 引擎，但面板脚本（魔法少女状态栏等）会直接引用 Mvu.*
    if (!window.Mvu) {
        const mvuEvents = {
            VARIABLE_INITIALIZED: 'mag_variable_initialized',
            VARIABLE_UPDATE_STARTED: 'mag_variable_update_started',
            COMMAND_PARSED: 'mag_command_parsed',
            VARIABLE_UPDATE_ENDED: 'mag_variable_update_ended',
            BEFORE_MESSAGE_UPDATE: 'mag_before_message_update',
            SINGLE_VARIABLE_UPDATED: 'mag_variable_updated',
        };
        window.Mvu = {
            events: mvuEvents,
            getMvuData: (option) => TavernHelper.getVariables(option),
            setMvuData: (data, option) => TavernHelper.replaceVariables(data, option),
            replaceMvuData: (data, option) => TavernHelper.replaceVariables(data, option),
            getCurrentMvuData: () => TavernHelper.getVariables({ type: 'message', message_id: TavernHelper.getCurrentMessageId() }),
            replaceCurrentMvuData: async (data) => TavernHelper.replaceVariables(data, { type: 'message', message_id: TavernHelper.getCurrentMessageId() }),
            parseMessage: async (_message, data) => data,
            UpdateData: async (_message, data) => data,
            reloadInitVar: async () => {},
        };
        console.log(`${LOG_PREFIX} PANEL mode: Mvu stub installed (→ TavernHelper)`);
    }
    // 处理早于 runtime 就绪的挂载请求：Kotlin 侧在 __rikkaPanelMount 未定义时会暂存 HTML，
    // 这里在全部环境（含 Mvu stub）就绪后补挂，避免“首次挂载静默丢失”。
    if (typeof window.__rikkaPanelPendingHtml === 'string' && window.__rikkaPanelPendingHtml) {
        const pendingHtml = window.__rikkaPanelPendingHtml;
        window.__rikkaPanelPendingHtml = null;
        try { window.__rikkaPanelMount(pendingHtml); } catch (_e) { /* noop */ }
    } else if (window.RikkaPanel && typeof window.RikkaPanel.getPendingHtml === 'function') {
        try {
            const pendingKey = (typeof window.RikkaPanel.getPendingKey === 'function')
                ? String(window.RikkaPanel.getPendingKey() || '') : '';
            if (pendingKey && !window.__rikkaPanelMessageKey) {
                window.__rikkaPanelMessageKey = pendingKey;
            }
            const pendingHtml = String(window.RikkaPanel.getPendingHtml() || '');
            if (pendingHtml) {
                window.__rikkaPanelMount(pendingHtml);
            }
        } catch (_e) { /* noop */ }
    }
        // [批次十三·诊断] 卡脚本在面板里到底向变量系统要了什么、又拿到了什么。
    // 只记前 24 次，避免刷爆日志（tavern-runtime.log 上限 256KB）。
    (function wrapPanelVarApi() {
        try {
            if (window.__rikkaPanelVarLog) return;
            window.__rikkaPanelVarLog = { n: 0 };
            const CAP = 24;
            const note = (label, opt, res) => {
                const st = window.__rikkaPanelVarLog;
                if (!st || st.n >= CAP) return;
                st.n++;
                let keys = '?', stat = '';
                try { keys = (res && typeof res === 'object') ? Object.keys(res).slice(0, 10).join(',') : String(res); } catch (_e) { /* noop */ }
                try { stat = (res && res.stat_data && typeof res.stat_data === 'object') ? ' stat=[' + Object.keys(res.stat_data).slice(0, 10).join(',') + ']' : ''; } catch (_e) { /* noop */ }
                try { RikkaPanel.log('debug', '[panel] varreq ' + label + ' ' + JSON.stringify(opt) + ' key=' + String(window.__rikkaPanelMessageKey) + ' selfId=' + panelSelfId() + ' → keys=[' + keys + ']' + stat); } catch (_e) { /* noop */ }
            };
            if (window.Mvu) {
                ['getMvuData', 'getCurrentMvuData'].forEach((fn) => {
                    const orig = window.Mvu[fn];
                    if (typeof orig !== 'function') return;
                    window.Mvu[fn] = function (opt) {
                        const res = orig.apply(this, arguments);
                        try { note('Mvu.' + fn, opt, res); } catch (_e) { /* noop */ }
                        return res;
                    };
                });
            }
            const th = (typeof TavernHelper !== 'undefined') ? TavernHelper : window.TavernHelper;
            if (th && typeof th.getVariables === 'function' && !th.__rikkaWrapped) {
                const origTh = th.getVariables;
                th.getVariables = function (opt) {
                    const res = origTh.apply(this, arguments);
                    try { note('TavernHelper.getVariables', opt, res); } catch (_e) { /* noop */ }
                    return res;
                };
                th.__rikkaWrapped = true;
            }
            RikkaPanel.log('debug', '[panel] var-API wrapped (Mvu=' + !!window.Mvu + ' TavernHelper=' + !!th + ')');
        } catch (e) {
            try { RikkaPanel.log('debug', '[panel] var-API wrap failed: ' + e); } catch (_e) { /* noop */ }
        }
    })();
    try {
        if (window.RikkaPanel && typeof window.RikkaPanel.onPanelRuntimeReady === 'function') {
            window.RikkaPanel.onPanelRuntimeReady();
        }
    } catch (_e) { /* noop */ }
    console.log(`${LOG_PREFIX} PANEL mode ready (mount API: __rikkaPanelMount)`);
}

// ---------------------------------------------------------------- [10-19-21] 通用悬浮窗舞台与双通道 TTS 隔离桥
function ensureRikkaIphonieFullscreenStyle() {
    try {
        if (document.getElementById('rikka-iphonie-fullscreen-override')) return;
        const st = document.createElement('style');
        st.id = 'rikka-iphonie-fullscreen-override';
        st.textContent = `
            #sttts-panel, #sttts-panel.floating {
                position: fixed !important;
                inset: 0 !important;
                left: 0 !important;
                top: 0 !important;
                width: 100vw !important;
                height: 100vh !important;
                max-width: 100vw !important;
                max-height: 100vh !important;
                margin: 0 !important;
                padding: 0 !important;
                border-radius: 0 !important;
                transform: none !important;
                z-index: 9999 !important;
            }
            #sttts-panel > iframe, #sttts-panel.floating > iframe {
                width: 100% !important;
                height: 100% !important;
                max-width: 100% !important;
                max-height: 100% !important;
                border-radius: 0 !important;
                transform: none !important;
            }
        `;
        (document.head || document.documentElement).appendChild(st);
    } catch (_e) { /* noop */ }
}

function seedStIphonieRikkaHubImageBridgeIfNeeded() {
    try {
        const rawStatus = callBridge('getImageGenerationStatusJson');
        if (!rawStatus) return;
        const status = JSON.parse(rawStatus);
        if (!status || !status.configured) return;
        const rawKeys = window.localStorage ? window.localStorage.getItem('st-iphonie-keys') : null;
        const keysObj = rawKeys ? JSON.parse(rawKeys) : {};
        const stSettings = (state.thirdPartySettings && state.thirdPartySettings.stTtsDialogue) || null;
        const hasNaiKey = Boolean(keysObj && keysObj.nai && String(keysObj.nai).trim());
        const hasGptKey = Boolean(keysObj && keysObj.gpt && String(keysObj.gpt).trim());
        if (!hasNaiKey && !hasGptKey && window.localStorage) {
            const presetId = (stSettings && stSettings.draw && stSettings.draw.connections && stSettings.draw.connections.gpt && stSettings.draw.connections.gpt.active) || 'default';
            keysObj.gpt = presetId + '\trikkahub-native-bridge';
            window.localStorage.setItem('st-iphonie-keys', JSON.stringify(keysObj));
            if (stSettings && stSettings.draw) {
                const origin = (window.location && window.location.origin && window.location.origin !== 'null')
                    ? window.location.origin
                    : 'https://appassets.androidplatform.net';
                const bridgeUrl = origin + '/api/rikkahub/v1';
                const modelId = String(status.modelId || 'gpt-image-1');
                stSettings.draw.engine = 'gpt';
                stSettings.draw.gpt = Object.assign({}, stSettings.draw.gpt || {}, {
                    url: bridgeUrl,
                    model: modelId,
                    ask: false,
                });
                if (stSettings.draw.connections && stSettings.draw.connections.gpt && Array.isArray(stSettings.draw.connections.gpt.presets)) {
                    const p = stSettings.draw.connections.gpt.presets.find((x) => x && x.id === presetId) || stSettings.draw.connections.gpt.presets[0];
                    if (p) {
                        p.url = bridgeUrl;
                        p.model = modelId;
                        p.name = 'RikkaHub 生图桥';
                    }
                }
            }
        }
    } catch (_e) { /* noop */ }
}

function openRealStIphoniePanel(forceReopen = false) {
    try {
        ensureRikkaIphonieFullscreenStyle();
        seedStIphonieRikkaHubImageBridgeIfNeeded();
        const existingDlg = document.getElementById('sttts-panel');
        if (!forceReopen && existingDlg && (existingDlg.open || existingDlg.hasAttribute('open')) && existingDlg.querySelector('iframe')) {
            return true;
        }
        const wand = document.getElementById('sttts-wand-entry');
        const realOpenBtn = document.querySelector('#sttts-extension-entry #sttts-open');
        if (realOpenBtn && realOpenBtn.getAttribute('data-rikka-fallback') !== 'true') {
            realOpenBtn.click();
        } else if (wand) {
            wand.click();
        }
        const dlg = document.getElementById('sttts-panel');
        if (dlg) {
            // 绝不移除 .floating class：ST-iPhonie index.js 的 panelResized() 在 180ms/500ms 会检查
            // `float !== panel.classList.contains('floating')`，一旦不一致就会执行 `panel.close(); openPanel(undefined, true)`，
            // 触发 stTtsPanelVisibility(false) 从而强行关闭二级菜单/sheet！全屏铺满由 ensureRikkaIphonieFullscreenStyle() 的 CSS !important 负责。
            if (!dlg.open) {
                try { dlg.showModal(); } catch (_e) { dlg.setAttribute('open', 'true'); dlg.style.display = 'block'; }
            }
            return true;
        }
    } catch (_e) { /* noop */ }
    return false;
}

function installUniversalFloatingAndTtsBridge() {
    if (window.__rikkaFloatingBridgeInstalled) return;
    window.__rikkaFloatingBridgeInstalled = true;

    // 1) 双通道 TTS 隔离控制：停止/暂停/楼层触发 ST-iPhonie 角色语音
    window.__rikkaStopIphonieTts = function () {
        try {
            const stopBtn = document.getElementById('sttts-stop');
            if (stopBtn && !stopBtn.disabled) {
                stopBtn.click();
                return true;
            }
            document.querySelectorAll('audio').forEach((a) => {
                try { a.pause(); } catch (_e) { /* noop */ }
            });
        } catch (_e) { /* noop */ }
        return false;
    };

    window.__rikkaToggleIphonieTts = function () {
        try {
            const toggleBtn = document.getElementById('sttts-toggle');
            if (toggleBtn && !toggleBtn.disabled) {
                toggleBtn.click();
                return true;
            }
            const allBtn = document.getElementById('sttts-all');
            if (allBtn) {
                allBtn.click();
                return true;
            }
        } catch (_e) { /* noop */ }
        return false;
    };

    window.__rikkaPlayFloorIphonieTts = function (floorIndex) {
        try {
            refreshAll();
            const idx = Number(floorIndex);
            const targetMes = Number.isFinite(idx) && idx >= 0
                ? document.querySelector(`#chat .mes[mesid="${idx}"]`)
                : null;
            if (targetMes) {
                const waveBtn = targetMes.querySelector('[data-sttts-action="all"], [data-sttts-action="line"]');
                if (waveBtn) {
                    waveBtn.click();
                    return true;
                }
            }
            const allBtn = document.getElementById('sttts-all');
            if (allBtn) {
                allBtn.click();
                return true;
            }
        } catch (_e) { /* noop */ }
        return false;
    };

    // 监听 ST-iPhonie 播放状态变化，一旦开始播放立即通知宿主停止上游系统原生 TTS，杜绝双通道声音冲突
    let lastTtsStatusText = '';
    let lastTtsPlaying = false;
    const checkIphonieTtsStatus = () => {
        try {
            const statusSpan = document.querySelector('#sttts-extension-entry .sttts-status');
            const stopBtn = document.getElementById('sttts-stop');
            const toggleBtn = document.getElementById('sttts-toggle');
            const statusText = statusSpan ? String(statusSpan.textContent || '').trim() : '';
            const isPlayingOrGen = Boolean(stopBtn && !stopBtn.disabled);
            const isPaused = Boolean(toggleBtn && !toggleBtn.disabled && String(toggleBtn.textContent || '').includes('继续'));
            const phase = isPlayingOrGen ? (isPaused ? 'paused' : 'playing') : 'idle';
            if (statusText !== lastTtsStatusText || isPlayingOrGen !== lastTtsPlaying) {
                lastTtsStatusText = statusText;
                lastTtsPlaying = isPlayingOrGen;
                emitToHost('iphonie_tts_state', {
                    phase,
                    playing: isPlayingOrGen && !isPaused,
                    message: statusText || (isPlayingOrGen ? '正在播放角色语音' : '等待播放'),
                });
            }
        } catch (_e) { /* noop */ }
    };
    setInterval(checkIphonieTtsStatus, 600);

    // 2) 扫描宿主与卡脚本 iframe 中的可用悬浮窗（小手机 / 记忆表格 / 小白X / 角色卡自定义手机或地图悬浮窗）
    const KNOWN_INTERNAL_IDS = new Set([
        'st-runtime-root',
        'rikka-st-compat-dom',
        'rikka-hidden-st-stubs',
        'mvu-runtime-iframe',
        'sheet_cell_style_container',
        'sttts-panel',
        'sttts-floating',
    ]);

    function detectCardFloatingNodes() {
        const customNodes = [];
        try {
            Array.from(document.body.children || []).forEach((el) => {
                if (!el || !el.tagName) return;
                const tag = el.tagName.toUpperCase();
                if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK') return;
                const id = String(el.id || '');
                if (KNOWN_INTERNAL_IDS.has(id)) return;
                if (tag === 'IFRAME' && String(el.name || '').startsWith('TH-script--')) return;
                if (el.classList && (el.classList.contains('sttts-call-island') || el.classList.contains('sttts-msg-island') || el.classList.contains('sttts-music-island') || el.classList.contains('sttts-voice-island') || el.classList.contains('sttts-note-island') || el.classList.contains('toast-container'))) {
                    return;
                }
                customNodes.push({ kind: 'body_node', id: id || tag, el });
            });
            for (const [sid, frame] of scriptIframes) {
                try {
                    const doc = frame && frame.contentDocument;
                    const body = doc && doc.body;
                    if (!body) continue;
                    const visibleChildren = Array.from(body.children || []).filter((c) => {
                        if (!c || !c.tagName) return false;
                        const t = c.tagName.toUpperCase();
                        return t !== 'SCRIPT' && t !== 'STYLE' && t !== 'LINK';
                    });
                    if (visibleChildren.length > 0) {
                        customNodes.push({ kind: 'script_iframe', id: String(sid), frame });
                    }
                } catch (_e2) { /* noop */ }
            }
        } catch (_e) { /* noop */ }
        return customNodes;
    }

    let lastFloatingSummaryJson = '';
    const reportFloatingWindows = () => {
        try {
            // [问题 3] 只认“真实 DOM 已就绪”，不再用“扩展已安装”冒充可打开目标。
            const hasIphonie = !!document.getElementById('sttts-extension-entry') || !!document.getElementById('sttts-panel');
            const hasMemory = !!document.getElementById('table_database_settings_drawer') ||
                !!document.getElementById('table_drawer_content') ||
                !!document.getElementById('database-content');
            const hasXbx = !!document.querySelector('.littlewhitebox');
            const cardNodes = detectCardFloatingNodes();
            const summary = {
                hasIphonie,
                hasMemory,
                hasLittleWhiteBox: hasXbx,
                hasCardFloating: cardNodes.length > 0,
                cardFloatingCount: cardNodes.length,
            };
            const json = JSON.stringify(summary);
            if (json !== lastFloatingSummaryJson) {
                lastFloatingSummaryJson = json;
                emitToHost('floating_windows_changed', summary);
            }
        } catch (_e) { /* noop */ }
    };
    setInterval(reportFloatingWindows, 1500);
    setTimeout(reportFloatingWindows, 500);

    const floatingNodeVisible = (el) => {
        if (!el) return false;
        try {
            const cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
            if (cs && (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') === 0)) return false;
            const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
            return !rect || rect.width > 0 || rect.height > 0;
        } catch (_e) { return true; }
    };

    const setFloatingIsolationTarget = (target) => {
        try {
            if (target) {
                document.body.dataset.rikkaFloatingTarget = String(target);
            } else {
                delete document.body.dataset.rikkaFloatingTarget;
            }
            let style = document.getElementById('rikka-floating-isolation-style');
            if (!style) {
                style = document.createElement('style');
                style.id = 'rikka-floating-isolation-style';
                (document.head || document.documentElement).appendChild(style);
            }
            style.textContent =
                'body[data-rikka-floating-target]:not([data-rikka-floating-target="iphonie"]) #sttts-extension-entry,' +
                'body[data-rikka-floating-target]:not([data-rikka-floating-target="iphonie"]) #sttts-panel {' +
                'display:none !important;visibility:hidden !important;pointer-events:none !important;}';
        } catch (_e) { /* noop */ }
    };

    const hideIphonieFloatingPanel = () => {
        const dlg = document.getElementById('sttts-panel');
        if (dlg) {
            try { if (dlg.open) dlg.close(); } catch (_e) { dlg.removeAttribute('open'); }
            dlg.style.setProperty('display', 'none', 'important');
        }
        const entry = document.getElementById('sttts-extension-entry');
        if (entry) entry.style.setProperty('display', 'none', 'important');
    };

    // 共享 compatDom 隔离：只显示目标节点所在的祖先链，其余（尤其 iPhonie 快捷设置）显式隐藏。
    const isolateCompatDom = (targetNodes) => {
        const compatDom = document.getElementById('rikka-st-compat-dom');
        if (!compatDom) return;
        compatDom.style.setProperty('display', 'block', 'important');
        compatDom.removeAttribute('aria-hidden');
        const targets = new Set(targetNodes.filter(Boolean));
        const keep = new Set();
        targetNodes.filter(Boolean).forEach((node) => {
            let cur = node;
            while (cur && cur !== compatDom) {
                keep.add(cur);
                cur = cur.parentElement;
            }
        });
        // 共享 compatDom 里可能同时挂着 iPhonie 快捷设置：只保留目标节点所在的祖先链，
        // 对每个祖先的其他分支逐级 display:none（而不是只处理 compatDom 的直接子节点）。
        const prune = (parent) => {
            Array.from(parent.children || []).forEach((child) => {
                if (targets.has(child)) {
                    child.style.removeProperty('display');
                    return; // 目标子树内部不动
                }
                if (keep.has(child)) {
                    child.style.removeProperty('display');
                    prune(child);
                } else {
                    child.style.setProperty('display', 'none', 'important');
                }
            });
        };
        prune(compatDom);
    };

    const floatingResult = (target, ok, reason) => {
        const result = { ok: !!ok, target: target || '', reason: reason || '' };
        try {
            if (window.RikkaBridge && typeof RikkaBridge.onFloatingTargetResult === 'function') {
                RikkaBridge.onFloatingTargetResult(result.target, result.ok, result.reason);
            }
        } catch (_e) { /* noop */ }
        if (ok) {
            window.__rikkaLastGoodFloatingTarget = target;
        }
        return result;
    };

    // 预检：不执行任何打开动作，供开发者测试台判断 DOM 是否真的就绪。
    window.__rikkaFloatingTargetProbe = function (target) {
        const t = String(target || '');
        const memoryNodes = [
            document.getElementById('table_database_settings_drawer'),
            document.getElementById('table_drawer_content'),
            document.getElementById('database-content'),
        ].filter(Boolean);
        const result = { target: t, available: false, nodeCount: 0, reason: '' };
        if (t === 'iphonie') {
            result.nodeCount = document.querySelectorAll('#sttts-extension-entry, #sttts-panel').length;
            result.available = result.nodeCount > 0;
        } else if (t === 'memory_table' || t === 'memory') {
            result.nodeCount = memoryNodes.length;
            result.available = memoryNodes.length > 0;
        } else if (t === 'littlewhitebox' || t === 'lwb') {
            result.nodeCount = document.querySelectorAll('.littlewhitebox').length;
            result.available = result.nodeCount > 0;
        } else if (t === 'card_floating' || t === 'card') {
            result.nodeCount = detectCardFloatingNodes().length;
            result.available = result.nodeCount > 0;
        } else {
            result.reason = 'unknown target';
        }
        if (!result.available && !result.reason) result.reason = '目标 DOM 未创建';
        return JSON.stringify(result);
    };

    // 3) 切换主运行时 WebView 的可视悬浮窗舞台（当用户在聊天页点击右侧常驻悬浮球时直接挂载显示）
    window.__rikkaOpenFloatingTarget = async function (target, forceReopen = false) {
        try {
            window.__rikkaCurrentFloatingTarget = target || null;
            setFloatingIsolationTarget(target || null);
            refreshAll();
            const compatDom = document.getElementById('rikka-st-compat-dom');
            const dlg = document.getElementById('sttts-panel');
            // 先还原卡脚本 iframe 的临时全屏样式
            for (const [, frame] of scriptIframes) {
                if (frame && frame.__rikkaExpandedForFloating) {
                    frame.__rikkaExpandedForFloating = false;
                    frame.style.cssText = 'display:none;width:0;height:0;border:none;position:absolute;pointer-events:none;';
                }
            }

            if (target === 'iphonie') {
                if (compatDom) compatDom.style.setProperty('display', 'none', 'important');
                const iphEntry = document.getElementById('sttts-extension-entry');
                if (iphEntry) iphEntry.style.removeProperty('display');
                const iphPanel = document.getElementById('sttts-panel');
                if (iphPanel) iphPanel.style.removeProperty('display');
                let opened = openRealStIphoniePanel(forceReopen);
                if (!opened) {
                    await new Promise((r) => setTimeout(r, 250));
                    opened = openRealStIphoniePanel();
                }
                await new Promise((r) => setTimeout(r, 80));
                const panel = document.getElementById('sttts-panel');
                const entry = document.getElementById('sttts-extension-entry');
                const ready = opened && (floatingNodeVisible(panel) || (panel && panel.open) || floatingNodeVisible(entry));
                return floatingResult(target, ready, ready ? '' : '小手机入口存在但面板未显示');
            }

            // 任何非 iPhonie 目标都先显式关闭小手机（display:block/浮层状态也要关）。
            hideIphonieFloatingPanel();
            if (dlg && dlg.open) {
                try { dlg.close(); } catch (_e) { dlg.removeAttribute('open'); dlg.style.display = 'none'; }
            }

            if (target === 'memory_table' || target === 'memory') {
                const drawer = document.getElementById('table_database_settings_drawer');
                const content = document.getElementById('table_drawer_content');
                const database = document.getElementById('database-content');
                if (!drawer && !content && !database) {
                    if (compatDom) compatDom.style.setProperty('display', 'none', 'important');
                    return floatingResult(target, false, '记忆表格 DOM 未就绪：扩展尚未初始化或当前卡未加载表格');
                }
                isolateCompatDom([drawer, content, database]);
                if (drawer) drawer.style.setProperty('display', 'block', 'important');
                if (content) {
                    content.classList.remove('closedDrawer');
                    content.removeAttribute('data-slide-toggle');
                    content.style.setProperty('display', 'block', 'important');
                    content.style.setProperty('visibility', 'visible', 'important');
                }
                ['#database-content', '#editor-content', '#setting-content'].forEach((sel, idx) => {
                    const el = document.querySelector(sel);
                    if (el) {
                        el.style.setProperty('display', idx === 0 ? 'block' : 'none', 'important');
                        el.style.setProperty('height', 'auto', 'important');
                        el.style.setProperty('overflow', 'visible', 'important');
                    }
                });
                const ready = !!drawer || !!content || !!database;
                if (!ready) {
                    if (compatDom) compatDom.style.setProperty('display', 'none', 'important');
                    return floatingResult(target, false, '记忆表格节点存在但不可见');
                }
                return floatingResult(target, true);
            }

            if (target === 'littlewhitebox' || target === 'lwb') {
                const xbx = document.querySelector('.littlewhitebox');
                if (!xbx) {
                    if (compatDom) compatDom.style.setProperty('display', 'none', 'important');
                    return floatingResult(target, false, '小白X DOM 未就绪');
                }
                isolateCompatDom([xbx]);
                xbx.style.setProperty('display', 'block', 'important');
                xbx.style.setProperty('visibility', 'visible', 'important');
                xbx.scrollIntoView({ block: 'start' });
                return floatingResult(target, true);
            }

            if (target === 'card_floating' || target === 'card') {
                if (compatDom) compatDom.style.setProperty('display', 'none', 'important');
                const cardNodes = detectCardFloatingNodes();
                if (cardNodes.length === 0) {
                    return floatingResult(target, false, '角色卡悬浮窗未检测到可显示节点');
                }
                const bindFloatingCloseDelegates = (rootDoc) => {
                    if (!rootDoc || rootDoc.__rikkaCloseDelegateBound) return;
                    rootDoc.__rikkaCloseDelegateBound = true;
                    rootDoc.addEventListener('click', (ev) => {
                        const btn = ev.target && ev.target.closest ? ev.target.closest('button, a, [role="button"], .close, .btn-close') : null;
                        if (!btn) return;
                        const label = String(btn.textContent || btn.getAttribute('title') || btn.getAttribute('aria-label') || '').trim();
                        if (/不再显示|don't show again|do not show/i.test(label)) {
                            try { callBridge('closeFloatingWindow', 'card_floating', true); } catch (_e) {}
                            emitToHost('close_floating_window', { target: 'card_floating', hideForChat: true });
                        } else if (/^(关闭|收起|退出浮窗|close|dismiss)$/i.test(label)) {
                            try { callBridge('closeFloatingWindow', 'card_floating', false); } catch (_e) {}
                            emitToHost('close_floating_window', { target: 'card_floating', hideForChat: false });
                        }
                    }, true);
                };
                bindFloatingCloseDelegates(document);
                for (const item of cardNodes) {
                    if (item.kind === 'body_node' && item.el && item.el.style) {
                        item.el.style.setProperty('display', 'block', 'important');
                        item.el.style.setProperty('visibility', 'visible', 'important');
                        item.el.style.setProperty('z-index', '9500', 'important');
                    } else if (item.kind === 'script_iframe' && item.frame) {
                        item.frame.__rikkaExpandedForFloating = true;
                        item.frame.style.cssText = 'display:block !important;position:fixed !important;inset:0 !important;width:100vw !important;height:100vh !important;border:none !important;z-index:9500 !important;pointer-events:auto !important;background:transparent !important;';
                        try {
                            if (item.frame.contentDocument) {
                                bindFloatingCloseDelegates(item.frame.contentDocument);
                            }
                            if (item.frame.contentWindow) {
                                item.frame.contentWindow.close = () => {
                                    try { callBridge('closeFloatingWindow', 'card_floating', false); } catch (_e) {}
                                    emitToHost('close_floating_window', { target: 'card_floating', hideForChat: false });
                                };
                            }
                        } catch (_eFrame) {}
                    }
                }
                return floatingResult(target, true);
            }
            return floatingResult(target, false, '未知悬浮窗目标');
        } catch (e) {
            console.warn('[floating] __rikkaOpenFloatingTarget failed:', e);
            return floatingResult(target, false, '打开异常: ' + String((e && e.message) || e));
        }
    };

    window.__rikkaCloseFloatingStage = function () {
        try {
            window.__rikkaCurrentFloatingTarget = null;
            setFloatingIsolationTarget(null);
            const dlg = document.getElementById('sttts-panel');
            if (dlg) {
                try { if (dlg.open) dlg.close(); } catch (_e) { dlg.removeAttribute('open'); }
                dlg.style.setProperty('display', 'none', 'important');
            }
            const compatDom = document.getElementById('rikka-st-compat-dom');
            if (compatDom && !window.__RIKKA_EXT_SETTINGS_MODE__) {
                compatDom.style.setProperty('display', 'none', 'important');
            }
            const iphEntry = document.getElementById('sttts-extension-entry');
            if (iphEntry) iphEntry.style.removeProperty('display');
            for (const [, frame] of scriptIframes) {
                if (frame && frame.__rikkaExpandedForFloating) {
                    frame.__rikkaExpandedForFloating = false;
                    frame.style.cssText = 'display:none;width:0;height:0;border:none;position:absolute;pointer-events:none;';
                }
            }
        } catch (_e) { /* noop */ }
    };
}

// ---------------------------------------------------------------- 启动
(async function boot() {
    try { emitToHost('runtime_imported', { mode: window.__RIKKA_PANEL_MODE__ ? 'panel' : (window.__RIKKA_EXT_SETTINGS_MODE__ ? 'ext-settings' : 'host') }); } catch (_e) { /* noop */ }
    // [10-19-21 深度适配] 扩展设置页模式：
    // 1. 杜绝二次进入白屏（退出时主动关闭全部 IndexedDB 连接并调用扩展 disable 钩子）；
    // 2. 杜绝预插重复 DOM 导致事件绑定失效；
    // 3. 为全部五大扩展提供 100% 可读、直接读写 extension_settings 并即时持久化的交互式控制面板。
    if (window.__RIKKA_EXT_SETTINGS_MODE__) {
        console.log(`${LOG_PREFIX} booting in EXT SETTINGS mode ...`);
        const extFolder = new URLSearchParams(location.search).get('ext') || '';
        const extAlias = normalizeExtFolderAliasJs(extFolder);

        window.__rikkaCleanupExtSettings = function () {
            try { flushAll(); } catch (_e) { /* noop */ }
            try {
                for (const f of Object.keys(loadedExtensionHooks)) {
                    try { invokeExtensionHook(f, 'disable'); } catch (_e2) { /* noop */ }
                }
            } catch (_e) { /* noop */ }
            try {
                const dbs = window.__rikkaOpenedIdbDatabases;
                if (dbs && typeof dbs.forEach === 'function') {
                    dbs.forEach((db) => {
                        try { db.close(); } catch (_e2) { /* noop */ }
                    });
                    dbs.clear();
                }
            } catch (_e) { /* noop */ }
        };
        window.addEventListener('pagehide', () => {
            try { window.__rikkaCleanupExtSettings(); } catch (_e) { /* noop */ }
        });

        try {
            const statusEl = document.getElementById('rikka-ext-settings-status');
            if (statusEl) {
                statusEl.style.display = 'block';
                statusEl.textContent = `正在加载扩展设置：${extFolder || '(未指定扩展)'} …`;
            }
        } catch (_e) { /* noop */ }

        if (typeof $ === 'undefined') safeWarn('jQuery missing! shims may break.');
        refreshAll();
        emitToHost('ext_settings_ready', { version: '1.1.0' });

        function markImmediateSaved(badgeEl) {
            try {
                pushGlobalVars();
                pushThirdPartySettingsNow();
                if (badgeEl) {
                    badgeEl.textContent = '✅ 已实时保存并生效 (' + new Date().toLocaleTimeString('zh-CN', { hour12: false }) + ')';
                }
            } catch (_e) { /* noop */ }
        }

        const STPT_I18N_MAP = {
            'Prompt template settings': '提示词模板 (ST-Prompt-Template) 核心设置',
            'PT Enable': '启用提示词模板扩展 (总开关)',
            'PT Enable Generate-time evaluation': '处理生成内容 (Generate 阶段模板求值)',
            'PT Enable GENERATE evaluation': '启用 [GENERATE:BEFORE/AFTER] 世界书注入',
            'PT Enable @INJECT evaluation': '启用 @INJECT 世界书定向注入',
            'PT Enable Chat message evaluation': '处理聊天楼层消息 (Render 阶段模板求值)',
            'PT Enable RENDER evaluation': '启用 [RENDER:BEFORE/AFTER] 楼层状态栏注入',
            'PT Enable code blocks': '允许处理楼层消息中的 <pre> 代码块',
            'PT Permanent evaluation results': '预计算并固化原始消息模板结果 (避免重复求值)',
            'PT Filter chat message': '生成前隐藏楼层消息内的模板语句',
            'PT chat depth limit': '楼层处理最大深度 (-1 表示无限制)',
            'PT Enable Autosave': '模板处理完成后立即保存会话',
            'PT Enable Preload': '打开角色卡时立即预加载并执行世界书 (初始化变量/define)',
            'PT Disabling with statement': '禁用 with 语句块 (解决 getvar is not defined 冲突)',
            'PT Enable debug logging': '控制台输出详细模板调试日志',
            'PT Invert entry enable': '旧版兼容模式：将禁用的 GENERATE/RENDER/INJECT 条目视为启用',
            'PT Compile workers': '在 Web Worker 中编译模板',
            'PT Environmental isolation': '在隔离沙箱 (iframe) 中执行代码',
            'PT Enable cache': '模板编译缓存 (加速重复渲染)',
            'Disable': '禁用',
            'Enable': '启用',
            'Only worldinfo': '仅世界书',
            'PT cache size': '编译缓存容量上限 (0 为不限制)',
            'PT cache hasher': '缓存 Hash 算法',
        };

        const STPT_DEFAULTS = {
            '#pt_enabled': { name: 'enabled', value: true },
            '#pt_generate_enabled': { name: 'generate_enabled', value: true },
            '#pt_generate_loader_enabled': { name: 'generate_loader_enabled', value: true },
            '#pt_inject_loader_enabled': { name: 'inject_loader_enabled', value: true },
            '#pt_render_enabled': { name: 'render_enabled', value: true },
            '#pt_render_loader_enabled': { name: 'render_loader_enabled', value: true },
            '#pt_code_blocks': { name: 'code_blocks', value: true },
            '#pt_permanent_evaluation': { name: 'permanent_evaluation', value: false },
            '#pt_filter_chat_message': { name: 'filter_chat_message', value: false },
            '#pt_chat_depth': { name: 'chat_depth', value: -1 },
            '#pt_autosave_enabled': { name: 'autosave_enabled', value: false },
            '#pt_preload_worldinfo': { name: 'preload_worldinfo', value: true },
            '#pt_with_context_disabled': { name: 'with_context_disabled', value: false },
            '#pt_debug_enabled': { name: 'debug_enabled', value: false },
            '#pt_invert_enabled': { name: 'invert_enabled', value: false },
            '#pt_compile_workers': { name: 'compile_workers', value: false },
            '#pt_sandbox': { name: 'sandbox', value: false },
            '#pt_cache_enabled': { name: 'cache_enabled', value: 0 },
            '#pt_cache_size': { name: 'cache_size', value: 128 },
            '#pt_cache_hasher': { name: 'cache_hasher', value: 'h32ToString' },
        };

        function localizeDomI18n(rootEl) {
            if (!rootEl) return;
            rootEl.querySelectorAll('[data-i18n]').forEach((el) => {
                const key = String(el.getAttribute('data-i18n') || '').trim();
                if (STPT_I18N_MAP[key]) {
                    el.textContent = STPT_I18N_MAP[key];
                }
            });
        }

        function bindStptSettingsDom() {
            const root = document.getElementById('prompt_template_settings');
            if (!root) return false;
            localizeDomI18n(root);
            if (!state.thirdPartySettings.EjsTemplate || typeof state.thirdPartySettings.EjsTemplate !== 'object') {
                state.thirdPartySettings.EjsTemplate = Object.assign({}, state.thirdPartySettings.ST_Prompt_Template || {});
            }
            const cfg = state.thirdPartySettings.EjsTemplate;
            state.thirdPartySettings.ST_Prompt_Template = cfg;
            for (const [sel, meta] of Object.entries(STPT_DEFAULTS)) {
                if (cfg[meta.name] === undefined) cfg[meta.name] = meta.value;
                const el = root.querySelector(sel);
                if (!el) continue;
                if (typeof meta.value === 'boolean') {
                    el.checked = !!cfg[meta.name];
                } else {
                    el.value = String(cfg[meta.name]);
                    const counter = root.querySelector(`*[data-for="${sel.slice(1)}"]`);
                    if (counter) counter.value = String(cfg[meta.name]);
                }
                if (!el.__rikkaStptBound) {
                    el.__rikkaStptBound = true;
                    const syncSetting = (fromCounter) => {
                        if (typeof meta.value === 'boolean') {
                            cfg[meta.name] = !!el.checked;
                        } else if (typeof meta.value === 'number') {
                            const srcEl = fromCounter || el;
                            const num = Number(srcEl.value);
                            cfg[meta.name] = Number.isFinite(num) ? num : meta.value;
                            el.value = String(cfg[meta.name]);
                            const counter = root.querySelector(`*[data-for="${sel.slice(1)}"]`);
                            if (counter) counter.value = String(cfg[meta.name]);
                        } else {
                            cfg[meta.name] = String(el.value);
                        }
                        if (globalThis.EjsTemplate && typeof globalThis.EjsTemplate.setFeatures === 'function') {
                            try { globalThis.EjsTemplate.setFeatures({ [meta.name]: cfg[meta.name] }); } catch (_e) { /* noop */ }
                        }
                        pushThirdPartySettingsNow();
                    };
                    el.addEventListener('change', () => syncSetting(null));
                    const counter = root.querySelector(`*[data-for="${sel.slice(1)}"]`);
                    if (counter) {
                        counter.addEventListener('change', () => syncSetting(counter));
                    }
                }
            }
            const resetBtn = root.querySelector('#pt_reset');
            if (resetBtn && !resetBtn.__rikkaStptBound) {
                resetBtn.__rikkaStptBound = true;
                resetBtn.addEventListener('click', () => {
                    for (const [, meta] of Object.entries(STPT_DEFAULTS)) {
                        cfg[meta.name] = meta.value;
                    }
                    bindStptSettingsDom();
                    pushThirdPartySettingsNow();
                });
            }
            return true;
        }

        function mountStptTestBench() {
            const wbRoot = document.getElementById('rikka-ext-workbench-root') || document.getElementById('extensions_settings');
            if (!wbRoot || document.getElementById('rikka-stpt-modal')) return;
            const card = document.createElement('div');
            card.id = 'rikka-stpt-modal';
            card.className = 'rikka-ext-card';
            card.innerHTML = `
                <h3>🧪 EJS 模板实时测试台 (ST-Prompt-Template)</h3>
                <div class="notes" style="margin-bottom:8px;">支持直接测试 <code>&lt;% ... %&gt;</code>、<code>getvar()</code> / <code>setvar()</code> 与角色上下文变量，点击下方按钮立即求值：</div>
                <textarea id="rikka-stpt-test-input" rows="4" style="width:100%;font-family:monospace;" placeholder="示例：当前角色：<%= char %>，用户：<%= user %>，HP=<%= getvar('hp') || 100 %>">当前角色：<%= char %> | 用户：<%= user %> | 楼层数：<%= lastMessageId %></textarea>
                <div class="flex-container" style="margin-top:8px;">
                    <button type="button" id="rikka-stpt-run-btn" class="menu_button">▶ 立即执行 EJS 模板</button>
                    <button type="button" id="rikka-stpt-vars-btn" class="menu_button">📊 查看当前会话/全局变量</button>
                </div>
                <pre id="rikka-stpt-test-output">等待执行…</pre>
            `;
            wbRoot.appendChild(card);
            const inputEl = card.querySelector('#rikka-stpt-test-input');
            const outEl = card.querySelector('#rikka-stpt-test-output');
            card.querySelector('#rikka-stpt-run-btn').addEventListener('click', async () => {
                const tpl = String((inputEl && inputEl.value) || '');
                try {
                    let rendered = '';
                    if (globalThis.EjsTemplate && typeof globalThis.EjsTemplate.evalTemplate === 'function') {
                        const ctx = await globalThis.EjsTemplate.prepareContext({ runType: 'command' });
                        rendered = await globalThis.EjsTemplate.evalTemplate(tpl, ctx);
                    } else {
                        rendered = await ejsRender.renderEjsText(tpl, buildEjsContext());
                    }
                    outEl.textContent = `[执行成功]\n${rendered}`;
                } catch (err) {
                    outEl.textContent = `[执行出错] ${String((err && (err.stack || err.message)) || err)}`;
                }
            });
            card.querySelector('#rikka-stpt-vars-btn').addEventListener('click', () => {
                try {
                    refreshAll();
                    const chatVars = (state.chatMetadata && state.chatMetadata.variables) || {};
                    const globalVars = (state.extensionSettings && state.extensionSettings.variables && state.extensionSettings.variables.global) || {};
                    outEl.textContent = JSON.stringify({ chatVariables: chatVars, globalVariables: globalVars }, null, 2);
                } catch (err) {
                    outEl.textContent = String(err);
                }
            });
        }

        function mountJsrWorkbench() {
            const wbRoot = document.getElementById('rikka-ext-workbench-root') || document.getElementById('extensions_settings');
            if (!wbRoot || document.getElementById('rikka-jsr-workbench')) return;
            const card = document.createElement('div');
            card.id = 'rikka-jsr-workbench';
            card.className = 'rikka-ext-card';
            let scripts = [];
            try {
                const rawScripts = callBridge('getTavernScriptsJson') || '{}';
                const helper = (JSON.parse(rawScripts) || {}).tavern_helper || {};
                scripts = Array.isArray(helper.scripts) ? helper.scripts : [];
            } catch (_e) { scripts = []; }
            const scriptItemsHtml = scripts.length
                ? scripts.map((s, idx) => `<div class="list-group-item" style="justify-content:space-between;"><span><b>#${idx + 1}</b> ${String(s.name || s.id || '未命名脚本')}</span><span class="rikka-save-badge">${scriptEnabled(s.enabled) ? '✅ 已启用' : '⏸ 已停用'}</span></div>`).join('')
                : `<div class="notes">当前未导入额外的脚本条目（可通过顶部「导入 JSR 脚本 JSON」按钮一键导入）。</div>`;
            card.innerHTML = `
                <h3>🛠 酒馆助手 (JS-Slash-Runner) 脚本库与变量管理器</h3>
                <div class="notes" style="margin-bottom:6px;">已注册 TavernHelper API 数量：<b>${Object.keys(window.TavernHelper || {}).length}</b> 个 · 兼容版本：<b>${TAVERN_ST_COMPAT_VERSION}</b></div>
                <div style="margin:8px 0;">${scriptItemsHtml}</div>
                <div class="notes" style="margin:10px 0 4px;">当前会话变量 (chat_metadata.variables JSON)：</div>
                <textarea id="rikka-jsr-vars-editor" rows="5" style="width:100%;font-family:monospace;"></textarea>
                <div class="flex-container" style="margin-top:8px;">
                    <button type="button" id="rikka-jsr-save-vars" class="menu_button">💾 保存变量修改</button>
                    <button type="button" id="rikka-jsr-refresh-vars" class="menu_button">🔄 刷新变量</button>
                </div>
                <pre id="rikka-jsr-status" style="display:none;"></pre>
            `;
            wbRoot.appendChild(card);
            const editor = card.querySelector('#rikka-jsr-vars-editor');
            const statusBox = card.querySelector('#rikka-jsr-status');
            const loadVarsToEditor = () => {
                try {
                    refreshAll();
                    const vars = (state.chatMetadata && state.chatMetadata.variables) || {};
                    editor.value = JSON.stringify(vars, null, 2);
                } catch (_e) { editor.value = '{}'; }
            };
            loadVarsToEditor();
            card.querySelector('#rikka-jsr-refresh-vars').addEventListener('click', loadVarsToEditor);
            card.querySelector('#rikka-jsr-save-vars').addEventListener('click', () => {
                try {
                    const parsed = JSON.parse(editor.value || '{}');
                    if (window.TavernHelper && typeof window.TavernHelper.replaceVariables === 'function') {
                        window.TavernHelper.replaceVariables(parsed, { type: 'chat' });
                    } else {
                        state.chatMetadata.variables = parsed;
                        flushAll();
                    }
                    statusBox.style.display = 'block';
                    statusBox.textContent = '✅ 会话变量已保存并同步至宿主';
                } catch (err) {
                    statusBox.style.display = 'block';
                    statusBox.textContent = '❌ JSON 格式错误：' + String((err && err.message) || err);
                }
            });
        }

        // [Task 2 核心修复] 记忆增强插件 (st-memory-enhancement) 直接设置与控制面板：
        // 1. 直接双向绑定 state.thirdPartySettings.muyoo_dataTable (及 power_user.muyoo_dataTable)；
        // 2. 点击任何开关/下拉框/输入框立即写入并调用 pushThirdPartySettingsNow() 落盘，同时同步插件原生 DOM 节点；
        // 3. 提供一键切换「对话数据表 / 表格模板编辑 / 完整高级设置」按钮。
        function mountMemoryEnhancementQuickSettings() {
            const holder = document.getElementById('top-settings-holder') || document.getElementById('rikka-ext-workbench-root');
            if (!holder) return;
            if (!state.thirdPartySettings.muyoo_dataTable || typeof state.thirdPartySettings.muyoo_dataTable !== 'object') {
                state.thirdPartySettings.muyoo_dataTable = Object.assign({
                    isExtensionAble: true,
                    isAiReadTable: true,
                    isAiWriteTable: true,
                    isTableToChat: false,
                    injection_mode: 'deep_system',
                    deep: 2,
                    step_by_step: false,
                    use_main_api: true,
                    step_by_step_use_main_api: true,
                    confirm_before_execution: true,
                    bool_silent_refresh: false,
                    table_to_chat_can_edit: false,
                    table_to_chat_mode: 'context_bottom',
                    table_cell_width_mode: 'wide1_2_cell',
                }, state.thirdPartySettings.st_memory_enhancement || {});
            }
            const cfg = state.thirdPartySettings.muyoo_dataTable;
            stCompatPowerUser.muyoo_dataTable = cfg;
            if (!state.thirdPartySettings.IMPORTANT_USER_PRIVACY_DATA || typeof state.thirdPartySettings.IMPORTANT_USER_PRIVACY_DATA !== 'object') {
                state.thirdPartySettings.IMPORTANT_USER_PRIVACY_DATA = {};
            }
            const priv = state.thirdPartySettings.IMPORTANT_USER_PRIVACY_DATA;
            stCompatPowerUser.IMPORTANT_USER_PRIVACY_DATA = priv;

            let card = document.getElementById('rikka-memory-quick-settings');
            if (!card) {
                card = document.createElement('div');
                card.id = 'rikka-memory-quick-settings';
                card.className = 'rikka-ext-card';
                card.innerHTML = `
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                        <h3 style="margin:0;">📊 记忆增强表格 · 核心开关与实时设置</h3>
                        <span id="rikka-mem-save-badge" class="rikka-save-badge">已就绪</span>
                    </div>
                    <div class="notes" style="margin-bottom:10px;">点击下方任意开关或修改数值将立即生效并自动持久化到酒馆会话：</div>
                    <div class="flex-container" style="margin-bottom:10px;">
                        <button type="button" data-mem-tab="setting" class="menu_button">⚙️ 展开完整设置项</button>
                        <button type="button" data-mem-tab="editor" class="menu_button">📐 表格模板编辑器</button>
                        <button type="button" data-mem-tab="database" class="menu_button">📊 当前对话数据表</button>
                        <button type="button" id="rikka-mem-rebuild-btn" class="menu_button">🔄 立即整理表格</button>
                        <button type="button" id="rikka-mem-step-btn" class="menu_button">▶ 手动触发分步填表</button>
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_enable"><b>启用记忆增强表格插件 (总开关)</b><span class="rikka-setting-desc">开启后在生成与接收消息时自动维护记忆表格</span></label>
                        <input type="checkbox" id="rk_mem_enable">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_read"><b>允许 AI 读取表格数据 (读表开关)</b><span class="rikka-setting-desc">将当前记忆表格注入给模型参考</span></label>
                        <input type="checkbox" id="rk_mem_read">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_write"><b>允许 AI 修改/写入表格 (写表开关)</b><span class="rikka-setting-desc">解析模型回复中的 tableEdit 指令自动更新表格</span></label>
                        <input type="checkbox" id="rk_mem_write">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_to_chat"><b>推送表格状态栏至聊天界面</b><span class="rikka-setting-desc">在消息中直接展示可视化表格视图</span></label>
                        <input type="checkbox" id="rk_mem_to_chat">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_step"><b>启用独立分步填表模式 (回复后单独填表)</b><span class="rikka-setting-desc">关闭则随聊天回复同步填表；开启则在回复后单独请求填表</span></label>
                        <input type="checkbox" id="rk_mem_step">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_use_main_api"><b>使用主聊天 API 进行表格整理/填表</b><span class="rikka-setting-desc">默认复用当前主模型，无需额外交替配置 Key</span></label>
                        <input type="checkbox" id="rk_mem_use_main_api">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_mem_inject_mode"><b>表格注入模式与深度</b><span class="rikka-setting-desc">控制表格在提示词中的插入位置</span></label>
                        <div class="flex-container">
                            <select id="rk_mem_inject_mode" style="min-width:130px;">
                                <option value="deep_system">深层 System 注入</option>
                                <option value="deep_user">深层 User 注入</option>
                                <option value="deep_assistant">深层 Assistant 注入</option>
                                <option value="injection_off">关闭注入</option>
                            </select>
                            <input type="number" id="rk_mem_deep" min="0" max="99" style="width:70px;" title="注入深度">
                        </div>
                    </div>
                `;
                holder.insertBefore(card, holder.firstChild);

                const badge = card.querySelector('#rikka-mem-save-badge');
                const bindToggle = (rkId, propName, nativeSel, defaultVal) => {
                    const el = card.querySelector(rkId);
                    if (!el) return;
                    if (cfg[propName] === undefined) cfg[propName] = defaultVal;
                    el.checked = !!cfg[propName];
                    el.addEventListener('change', () => {
                        cfg[propName] = !!el.checked;
                        const nativeEl = document.querySelector(nativeSel);
                        if (nativeEl && nativeEl.checked !== el.checked) {
                            nativeEl.checked = el.checked;
                            try { $(nativeEl).trigger('change'); } catch (_e) { /* noop */ }
                        }
                        markImmediateSaved(badge);
                    });
                };

                bindToggle('#rk_mem_enable', 'isExtensionAble', '#table_switch', true);
                bindToggle('#rk_mem_read', 'isAiReadTable', '#table_read_switch', true);
                bindToggle('#rk_mem_write', 'isAiWriteTable', '#table_edit_switch', true);
                bindToggle('#rk_mem_to_chat', 'isTableToChat', '#table_to_chat', false);
                bindToggle('#rk_mem_step', 'step_by_step', '#fill_table_time', false);
                bindToggle('#rk_mem_use_main_api', 'use_main_api', '#use_main_api', true);

                const modeSel = card.querySelector('#rk_mem_inject_mode');
                if (modeSel) {
                    modeSel.value = String(cfg.injection_mode || 'deep_system');
                    modeSel.addEventListener('change', () => {
                        cfg.injection_mode = String(modeSel.value);
                        const nativeMode = document.querySelector('#dataTable_injection_mode');
                        if (nativeMode) {
                            nativeMode.value = cfg.injection_mode;
                            try { $(nativeMode).trigger('change'); } catch (_e) { /* noop */ }
                        }
                        markImmediateSaved(badge);
                    });
                }

                const deepInput = card.querySelector('#rk_mem_deep');
                if (deepInput) {
                    deepInput.value = String(cfg.deep ?? 2);
                    deepInput.addEventListener('change', () => {
                        cfg.deep = Math.abs(Number(deepInput.value) || 0);
                        deepInput.value = String(cfg.deep);
                        const nativeDeep = document.querySelector('#dataTable_deep');
                        if (nativeDeep) {
                            nativeDeep.value = String(cfg.deep);
                            try { $(nativeDeep).trigger('input'); } catch (_e) { /* noop */ }
                        }
                        markImmediateSaved(badge);
                    });
                }

                card.querySelectorAll('[data-mem-tab]').forEach((btn) => {
                    btn.addEventListener('click', () => {
                        const tab = btn.getAttribute('data-mem-tab');
                        showMemoryEnhancementSection(tab);
                    });
                });

                const rebuildBtn = card.querySelector('#rikka-mem-rebuild-btn');
                if (rebuildBtn) {
                    rebuildBtn.addEventListener('click', () => {
                        const nativeBtn = document.getElementById('table_clear_up');
                        if (nativeBtn) nativeBtn.click();
                        else if (window.toastr) window.toastr.info('请在对话页面或加载对话后执行整理表格');
                    });
                }
                const stepBtn = card.querySelector('#rikka-mem-step-btn');
                if (stepBtn) {
                    stepBtn.addEventListener('click', () => {
                        const nativeBtn = document.getElementById('trigger_step_by_step_button');
                        if (nativeBtn) nativeBtn.click();
                        else if (window.toastr) window.toastr.info('请在对话页面或加载对话后触发分步填表');
                    });
                }
            }
        }

        function showMemoryEnhancementSection(tab) {
            const drawer = document.getElementById('table_database_settings_drawer');
            const drawerContent = document.getElementById('table_drawer_content');
            if (drawer) drawer.style.setProperty('display', 'block', 'important');
            if (drawerContent) {
                drawerContent.classList.remove('closedDrawer');
                drawerContent.removeAttribute('data-slide-toggle');
                drawerContent.style.setProperty('display', 'block', 'important');
            }
            const dbDiv = document.getElementById('database-content');
            const edDiv = document.getElementById('editor-content');
            const stDiv = document.getElementById('setting-content');
            if (tab === 'database') {
                if (dbDiv) dbDiv.style.setProperty('display', 'block', 'important');
                if (edDiv) edDiv.style.setProperty('display', 'none', 'important');
                if (stDiv) stDiv.style.setProperty('display', 'none', 'important');
                if (dbDiv) dbDiv.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } else if (tab === 'editor') {
                if (dbDiv) dbDiv.style.setProperty('display', 'none', 'important');
                if (edDiv) edDiv.style.setProperty('display', 'block', 'important');
                if (stDiv) stDiv.style.setProperty('display', 'none', 'important');
                if (edDiv) edDiv.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } else {
                // 默认在设置页同时展示完整设置项与模板编辑器，彻底杜绝看到空白数据表
                if (stDiv) stDiv.style.setProperty('display', 'block', 'important');
                if (edDiv) edDiv.style.setProperty('display', 'block', 'important');
                if (dbDiv) dbDiv.style.setProperty('display', 'block', 'important');
                if (stDiv) stDiv.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        }

        // [Task 1 & Task 2 核心修复] 小手机 (ST-iPhonie) 直接设置与真机面板启动器：
        // 1. 直接读写 state.thirdPartySettings.stTtsDialogue（ST-iPhonie 真实命名空间 NAMESPACE='stTtsDialogue'）；
        // 2. 绝不伪造重复 #sttts-extension-entry / #sttts-open 节点，保证点击「打开小手机主屏幕」100% 触发 ST-iPhonie 真实闭包 openPanel()！
        function mountStIphonieQuickSettings() {
            const holder = document.getElementById('top-settings-holder') || document.getElementById('rikka-ext-workbench-root');
            if (!holder) return;
            if (!state.thirdPartySettings.stTtsDialogue || typeof state.thirdPartySettings.stTtsDialogue !== 'object') {
                state.thirdPartySettings.stTtsDialogue = {};
            }
            const cfg = state.thirdPartySettings.stTtsDialogue;
            if (cfg.enabled === undefined) cfg.enabled = true;
            if (!cfg.general || typeof cfg.general !== 'object') cfg.general = {};
            if (!cfg.narration || typeof cfg.narration !== 'object') cfg.narration = {};
            if (!cfg.sounds || typeof cfg.sounds !== 'object') cfg.sounds = {};
            if (!cfg.music || typeof cfg.music !== 'object') cfg.music = {};
            if (!cfg.draw || typeof cfg.draw !== 'object') cfg.draw = {};

            const g = cfg.general;
            if (g.voiceEnabled === undefined) g.voiceEnabled = true;
            if (g.hideStoryWaves === undefined) g.hideStoryWaves = false;
            if (g.floatingEnabled === undefined) g.floatingEnabled = true;
            if (g.voiceIsland === undefined) g.voiceIsland = false;
            if (g.autoVoice === undefined) g.autoVoice = false;
            if (g.pregenerate === undefined) g.pregenerate = false;
            if (g.stripVoice === undefined) g.stripVoice = true;
            if (!g.voiceMode) g.voiceMode = 'inline';

            let card = document.getElementById('rikka-iphonie-quick-settings');
            if (!card) {
                card = document.createElement('div');
                card.id = 'rikka-iphonie-quick-settings';
                card.className = 'rikka-ext-card';
                card.innerHTML = `
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                        <h3 style="margin:0;">📱 ST-iPhonie · 小手机与角色语音核心设置</h3>
                        <span id="rikka-iphonie-save-badge" class="rikka-save-badge">已就绪</span>
                    </div>
                    <div class="notes" style="margin-bottom:10px;">下方开关直接控制 ST-iPhonie 角色语音、聊天页右侧常驻悬浮窗与正文出图（与系统原生 TTS 独立双通道隔离，互不冲突）：</div>
                    <div class="flex-container" style="margin-bottom:12px;">
                        <button type="button" id="rk-iphonie-open-screen" class="menu_button" style="background:#355691;border-color:#8ab4f8;font-weight:600;">📱 打开小手机全屏主界面 (音色/API Key/微信/朋友圈)</button>
                        <button type="button" id="rk-iphonie-play-all" class="menu_button">🔊 整条播放当前台词</button>
                        <button type="button" id="rk-iphonie-stop" class="menu_button">⏹ 停止小手机语音</button>
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_voice_enabled"><b>开启正文角色语音配音 (<tts> 台词声波)</b><span class="rikka-setting-desc">开启后自动为助手回复注入角色配音规则并在气泡内渲染可点击声波</span></label>
                        <input type="checkbox" id="rk_ip_voice_enabled">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_floating_enabled"><b>启用聊天页右侧常驻「小手机/悬浮窗」按钮</b><span class="rikka-setting-desc">在助手聊天界面右侧常驻显示小手机与角色卡悬浮窗快捷入口</span></label>
                        <input type="checkbox" id="rk_ip_floating_enabled">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_auto_voice"><b>遇到新角色时自动挑选音色</b><span class="rikka-setting-desc">新出场角色尚未绑定音色时自动从候选池分配合适音色</span></label>
                        <input type="checkbox" id="rk_ip_auto_voice">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_pregenerate"><b>新回复生成后后台提前合成语音</b><span class="rikka-setting-desc">新楼层完成后自动在后台静默缓存语音，点击声波即刻秒播</span></label>
                        <input type="checkbox" id="rk_ip_pregenerate">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_strip_voice"><b>发送给模型前自动剥离历史 <tts> 标签 (省 Token)</b><span class="rikka-setting-desc">气泡内保留声波，但上下文历史只发纯文本译文</span></label>
                        <input type="checkbox" id="rk_ip_strip_voice">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_hide_waves"><b>隐藏正文内的声波图标 (仅通过小手机/灵动岛播放)</b><span class="rikka-setting-desc">适合重度美化卡防止声波破坏排版</span></label>
                        <input type="checkbox" id="rk_ip_hide_waves">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_voice_island"><b>开启台词灵动岛小窗</b><span class="rikka-setting-desc">在屏幕顶部显示当前朗读台词与逐句控制条</span></label>
                        <input type="checkbox" id="rk_ip_voice_island">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_narration"><b>开启整篇旁白朗读</b><span class="rikka-setting-desc">朗读角色台词的同时由旁白音色朗读台词之间的叙述正文</span></label>
                        <input type="checkbox" id="rk_ip_narration">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_sounds"><b>开启正文环境音效 (<sfx> 音效标签)</b><span class="rikka-setting-desc">自动识别并播放回复中的环境音与动作音效</span></label>
                        <input type="checkbox" id="rk_ip_sounds">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_draw"><b>开启正文插画生成 (<pic> 标签绘图)</b><span class="rikka-setting-desc">支持 NovelAI / GPT / ComfyUI 正文自动或点击出图</span></label>
                        <input type="checkbox" id="rk_ip_draw">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_ip_voice_mode"><b>角色配音生成模式</b><span class="rikka-setting-desc">随正文一次生成或正文完成后单独请求配音</span></label>
                        <select id="rk_ip_voice_mode" style="min-width:150px;">
                            <option value="inline">随正文一起生成 (默认)</option>
                            <option value="separate">回复后单独配音</option>
                        </select>
                    </div>
                `;
                holder.insertBefore(card, holder.firstChild);

                const badge = card.querySelector('#rikka-iphonie-save-badge');
                const bindObjCheck = (sel, targetObj, key, defVal) => {
                    const el = card.querySelector(sel);
                    if (!el) return;
                    if (targetObj[key] === undefined) targetObj[key] = defVal;
                    el.checked = !!targetObj[key];
                    el.addEventListener('change', () => {
                        targetObj[key] = !!el.checked;
                        markImmediateSaved(badge);
                    });
                };

                bindObjCheck('#rk_ip_voice_enabled', g, 'voiceEnabled', true);
                bindObjCheck('#rk_ip_floating_enabled', g, 'floatingEnabled', true);
                bindObjCheck('#rk_ip_auto_voice', g, 'autoVoice', false);
                bindObjCheck('#rk_ip_pregenerate', g, 'pregenerate', false);
                bindObjCheck('#rk_ip_strip_voice', g, 'stripVoice', true);
                bindObjCheck('#rk_ip_hide_waves', g, 'hideStoryWaves', false);
                bindObjCheck('#rk_ip_voice_island', g, 'voiceIsland', false);
                bindObjCheck('#rk_ip_narration', cfg.narration, 'enabled', false);
                bindObjCheck('#rk_ip_sounds', cfg.sounds, 'enabled', false);
                bindObjCheck('#rk_ip_draw', cfg.draw, 'enabled', false);

                const modeEl = card.querySelector('#rk_ip_voice_mode');
                if (modeEl) {
                    modeEl.value = String(g.voiceMode || 'inline');
                    modeEl.addEventListener('change', () => {
                        g.voiceMode = String(modeEl.value);
                        markImmediateSaved(badge);
                    });
                }

                card.querySelector('#rk-iphonie-open-screen').addEventListener('click', () => {
                    window.__rikkaExtSettingsAction('open_iphonie_app');
                });
                card.querySelector('#rk-iphonie-play-all').addEventListener('click', () => {
                    const btn = document.getElementById('sttts-all');
                    if (btn) btn.click();
                });
                card.querySelector('#rk-iphonie-stop').addEventListener('click', () => {
                    const btn = document.getElementById('sttts-stop');
                    if (btn) btn.click();
                });
            }
        }

        // [Task 2 核心修复] 小白X (LittleWhiteBox) 直接开关控制面板
        function mountLittleWhiteBoxQuickSettings() {
            const holder = document.getElementById('top-settings-holder') || document.getElementById('rikka-ext-workbench-root');
            if (!holder) return;
            if (!state.thirdPartySettings.LittleWhiteBox || typeof state.thirdPartySettings.LittleWhiteBox !== 'object') {
                state.thirdPartySettings.LittleWhiteBox = {
                    enabled: true,
                    renderEnabled: true,
                    maxRenderedMessages: 5,
                    drawProvider: 'disabled',
                    recorded: { enabled: true },
                    storySummary: { enabled: true },
                    storyOutline: { enabled: false },
                    variablesCore: { enabled: true },
                    variablesPanel: { enabled: false },
                    templateEditor: { enabled: true },
                    tasks: { enabled: true },
                    immersive: { enabled: false },
                    preview: { enabled: false },
                    tts: { enabled: false },
                };
            }
            const cfg = state.thirdPartySettings.LittleWhiteBox;
            let card = document.getElementById('rikka-xbx-quick-settings');
            if (!card) {
                card = document.createElement('div');
                card.id = 'rikka-xbx-quick-settings';
                card.className = 'rikka-ext-card';
                card.innerHTML = `
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                        <h3 style="margin:0;">📦 小白X (LittleWhiteBox) · 核心功能开关</h3>
                        <span id="rikka-xbx-save-badge" class="rikka-save-badge">已就绪</span>
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_xbx_enabled"><b>启用 LittleWhiteBox 总开关</b></label>
                        <input type="checkbox" id="rk_xbx_enabled">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_xbx_render"><b>启用前端 HTML 渲染器 (iframe 渲染)</b></label>
                        <input type="checkbox" id="rk_xbx_render">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_xbx_summary"><b>启用大小总结 (Story Summary)</b></label>
                        <input type="checkbox" id="rk_xbx_summary">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_xbx_vars"><b>启用变量核心 (Variables Core 2.0/1.0)</b></label>
                        <input type="checkbox" id="rk_xbx_vars">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_xbx_tasks"><b>启用定时/条件任务 (Scheduled Tasks)</b></label>
                        <input type="checkbox" id="rk_xbx_tasks">
                    </div>
                    <div class="rikka-setting-row">
                        <label for="rk_xbx_template"><b>启用角色卡模板编辑器 (Template Editor)</b></label>
                        <input type="checkbox" id="rk_xbx_template">
                    </div>
                `;
                holder.insertBefore(card, holder.firstChild);
                const badge = card.querySelector('#rikka-xbx-save-badge');
                const bindTop = (rkId, key, nativeId, defVal) => {
                    const el = card.querySelector(rkId);
                    if (!el) return;
                    if (cfg[key] === undefined) cfg[key] = defVal;
                    el.checked = !!cfg[key];
                    el.addEventListener('change', () => {
                        cfg[key] = !!el.checked;
                        const nativeEl = document.getElementById(nativeId);
                        if (nativeEl && nativeEl.checked !== el.checked) {
                            nativeEl.checked = el.checked;
                            try { $(nativeEl).trigger('change'); } catch (_e) { /* noop */ }
                        }
                        markImmediateSaved(badge);
                    });
                };
                const bindSub = (rkId, subKey, nativeId, defVal) => {
                    const el = card.querySelector(rkId);
                    if (!el) return;
                    if (!cfg[subKey] || typeof cfg[subKey] !== 'object') cfg[subKey] = { enabled: defVal };
                    el.checked = !!cfg[subKey].enabled;
                    el.addEventListener('change', () => {
                        cfg[subKey].enabled = !!el.checked;
                        const nativeEl = document.getElementById(nativeId);
                        if (nativeEl && nativeEl.checked !== el.checked) {
                            nativeEl.checked = el.checked;
                            try { $(nativeEl).trigger('change'); } catch (_e) { /* noop */ }
                        }
                        markImmediateSaved(badge);
                    });
                };
                bindTop('#rk_xbx_enabled', 'enabled', 'xiaobaix_enabled', true);
                bindTop('#rk_xbx_render', 'renderEnabled', 'xiaobaix_render_enabled', true);
                bindSub('#rk_xbx_summary', 'storySummary', 'xiaobaix_story_summary_enabled', true);
                bindSub('#rk_xbx_vars', 'variablesCore', 'xiaobaix_variables_core_enabled', true);
                bindSub('#rk_xbx_tasks', 'tasks', 'scheduled_tasks_enabled', true);
                bindSub('#rk_xbx_template', 'templateEditor', 'xiaobaix_template_enabled', true);
            }
        }

        function bindLittleWhiteBoxDom() {
            const grid = document.querySelector('.littlewhitebox.settings-grid');
            if (!grid) return false;
            const tabs = grid.querySelectorAll('.menu-tab[data-target]');
            const sections = grid.querySelectorAll('.settings-content > .settings-section');
            tabs.forEach((tab) => {
                if (tab.__rikkaXbxTabBound) return;
                tab.__rikkaXbxTabBound = true;
                tab.addEventListener('click', () => {
                    const target = tab.getAttribute('data-target');
                    tabs.forEach((t) => t.classList.toggle('active', t === tab));
                    sections.forEach((sec) => {
                        const match = target && sec.classList.contains(target);
                        sec.style.display = match ? 'block' : 'none';
                    });
                });
            });
            return true;
        }

        async function ensureExtensionSettingsUiMounted(targetFolder) {
            const alias = normalizeExtFolderAliasJs(targetFolder);
            const extSettingsRoot = document.getElementById('extensions_settings');
            if (!extSettingsRoot) return;
            const list = (window.__rikkaSt && Array.isArray(window.__rikkaSt.extensions)) ? window.__rikkaSt.extensions : [];
            const matchedExt = list.find((e) => e && (e.folder === targetFolder || normalizeExtFolderAliasJs(e.folder) === alias));
            const actualFolder = (matchedExt && matchedExt.folder) ? matchedExt.folder : targetFolder;

            if (alias.includes('st-prompt-template')) {
                if (!document.getElementById('prompt_template_settings') && actualFolder) {
                    const html = await renderExtensionTemplateAsync(`third-party/${actualFolder}`, 'settings');
                    if (html) {
                        extSettingsRoot.insertAdjacentHTML('beforeend', html);
                    }
                }
                bindStptSettingsDom();
                mountStptTestBench();
            } else if (alias.includes('js-slash-runner') || alias.includes('tavern_helper')) {
                mountJsrWorkbench();
            } else if (alias.includes('st-memory-enhancement')) {
                // 挂载直接可交互的记忆增强核心设置卡片，绝不重复注入 index.html 破坏原生 InitBinging()
                mountMemoryEnhancementQuickSettings();
                localizeDomI18n(document.body);
            } else if (alias.includes('littlewhitebox')) {
                mountLittleWhiteBoxQuickSettings();
                bindLittleWhiteBoxDom();
            } else if (alias.includes('st-iphonie')) {
                mountStIphonieQuickSettings();
            } else if (actualFolder && extSettingsRoot.children.length === 0) {
                const html = await renderExtensionTemplateAsync(`third-party/${actualFolder}`, 'settings');
                if (html) {
                    extSettingsRoot.insertAdjacentHTML('beforeend', html);
                }
            }
        }

        function normalizeExtensionSettingsDom() {
            try {
                // 0) 强制隐藏 #chat 与 #form_sheld，绝不让聊天正文出现在插件设置页
                ['#chat', '#form_sheld', '#send_form', '#rikka-hidden-st-stubs'].forEach((sel) => {
                    document.querySelectorAll(sel).forEach((el) => {
                        if (el && el.style) el.style.setProperty('display', 'none', 'important');
                    });
                });
                // 1) 确保所有顶层抽屉与设置面板可见（不强制覆盖 #database-content/#editor-content/#setting-content 的单页切换，除非三者全被隐藏）
                const forceVisibleSelectors = [
                    '#prompt_template_settings',
                    '#tavern_helper',
                    '#table_database_settings_drawer',
                    '#table_drawer_content',
                    '#memory_enhancement_settings_inline_drawer_content',
                    '.memory_enhancement_container',
                    '#xiaobaix_settings',
                    '.littlewhitebox',
                    '#sttts-extension-entry',
                    '.inline-drawer-content',
                    '.drawer-content',
                ];
                for (const sel of forceVisibleSelectors) {
                    document.querySelectorAll(sel).forEach((el) => {
                        if (el && el.style && el.getAttribute('data-rikka-collapsed') !== 'true') {
                            if (el.style.display === 'none') el.style.display = 'block';
                            el.removeAttribute('data-slide-toggle');
                            el.classList.remove('closedDrawer');
                        }
                    });
                }
                if (extAlias.includes('st-memory-enhancement')) {
                    const stDiv = document.getElementById('setting-content');
                    const edDiv = document.getElementById('editor-content');
                    const dbDiv = document.getElementById('database-content');
                    if (stDiv && !stDiv.__rikkaInitialShown) {
                        stDiv.__rikkaInitialShown = true;
                        stDiv.style.setProperty('display', 'block', 'important');
                        if (edDiv) edDiv.style.setProperty('display', 'block', 'important');
                        if (dbDiv) dbDiv.style.setProperty('display', 'block', 'important');
                    }
                } else if (extAlias.includes('st-prompt-template')) {
                    bindStptSettingsDom();
                } else if (extAlias.includes('littlewhitebox')) {
                    bindLittleWhiteBoxDom();
                }
                // 2) 全局监听扩展设置页内的任何 input/change 事件，确保插件原生控件修改后立即写回宿主
                if (!document.body.__rikkaGlobalInputSyncBound) {
                    document.body.__rikkaGlobalInputSyncBound = true;
                    document.body.addEventListener('change', () => {
                        setTimeout(() => {
                            try { pushGlobalVars(); pushThirdPartySettingsNow(); } catch (_e) { /* noop */ }
                        }, 20);
                    }, true);
                }
                // 3) 为 .inline-drawer-toggle / .inline-drawer-header 绑定点击折叠切换
                document.querySelectorAll('.inline-drawer-toggle, .inline-drawer-header').forEach((hdr) => {
                    if (hdr.__rikkaDrawerBound) return;
                    hdr.__rikkaDrawerBound = true;
                    hdr.addEventListener('click', (ev) => {
                        const tag = (ev.target && ev.target.tagName) ? ev.target.tagName.toLowerCase() : '';
                        if (tag === 'input' || tag === 'select' || tag === 'button' || tag === 'a' || tag === 'i') return;
                        const drawer = hdr.closest('.inline-drawer');
                        const content = drawer ? drawer.querySelector('.inline-drawer-content') : null;
                        if (content) {
                            const hidden = content.getAttribute('data-rikka-collapsed') === 'true';
                            content.setAttribute('data-rikka-collapsed', hidden ? 'false' : 'true');
                            content.style.setProperty('display', hidden ? 'block' : 'none', 'important');
                        }
                    });
                });
                const statusEl = document.getElementById('rikka-ext-settings-status');
                if (statusEl && statusEl.getAttribute('data-error') !== 'true') {
                    statusEl.textContent = '扩展设置已就绪';
                    statusEl.style.display = 'none';
                }
            } catch (_e) { /* noop */ }
        }

        window.__rikkaExtSettingsAction = async function (action, payloadJson) {
            try {
                const payload = payloadJson ? JSON.parse(payloadJson) : {};
                if (action === 'expand_all' || action === 'expand_all_drawers') {
                    document.querySelectorAll('[data-rikka-collapsed="true"]').forEach((el) => {
                        el.setAttribute('data-rikka-collapsed', 'false');
                        el.style.setProperty('display', 'block', 'important');
                    });
                    if (extAlias.includes('st-memory-enhancement')) {
                        showMemoryEnhancementSection('all');
                    }
                    normalizeExtensionSettingsDom();
                    return true;
                }
                if (action === 'open_stpt_modal') {
                    await ensureExtensionSettingsUiMounted(extFolder);
                    normalizeExtensionSettingsDom();
                    const modal = document.getElementById('rikka-stpt-modal') || document.getElementById('prompt_template_settings');
                    if (modal) {
                        modal.style.display = 'block';
                        modal.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        const input = document.getElementById('rikka-stpt-test-input');
                        if (input) input.focus();
                    }
                    return true;
                }
                if (action === 'open_jsr_scripts') {
                    await ensureExtensionSettingsUiMounted(extFolder);
                    normalizeExtensionSettingsDom();
                    const wb = document.getElementById('rikka-jsr-workbench') || document.getElementById('tavern_helper');
                    if (wb) {
                        wb.style.display = 'block';
                        wb.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    }
                    return true;
                }
                if (action === 'open_memory_table' || action === 'switch_memory_tab') {
                    await ensureExtensionSettingsUiMounted(extFolder);
                    normalizeExtensionSettingsDom();
                    showMemoryEnhancementSection(payload.tab || 'all');
                    return true;
                }
                if (action === 'open_xbx_panel') {
                    await ensureExtensionSettingsUiMounted(extFolder);
                    normalizeExtensionSettingsDom();
                    const grid = document.querySelector('.littlewhitebox') || document.getElementById('rikka-xbx-quick-settings');
                    if (grid) {
                        grid.style.display = 'block';
                        grid.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    }
                    return true;
                }
                if (action === 'open_iphonie' || action === 'open_iphonie_app') {
                    await ensureExtensionSettingsUiMounted(extFolder);
                    if (openRealStIphoniePanel()) return true;
                    // 若 ST-iPhonie 仍在异步初始化 IndexedDB，等待 350ms 后重试一次真实入口
                    await new Promise((r) => setTimeout(r, 350));
                    if (openRealStIphoniePanel()) return true;
                    // 通知宿主弹出常驻运行时小手机悬浮窗
                    emitToHost('request_open_floating_window', { target: 'iphonie' });
                    return true;
                }
                if (action === 'reload_settings') {
                    pullThirdPartySettings(true);
                    ensureStStateShapes();
                    await eventSource.emit(event_types.SETTINGS_UPDATED);
                    await ensureExtensionSettingsUiMounted(extFolder);
                    normalizeExtensionSettingsDom();
                    return true;
                }
            } catch (e) {
                console.warn('[ext-settings] action failed', action, e);
            }
            return false;
        };

        const t0 = Date.now();
        try {
            // 先立即挂载直接可交互的设置面板（0ms 首屏可见，绝不白屏）
            await ensureExtensionSettingsUiMounted(extFolder);
            normalizeExtensionSettingsDom();
            await loadThirdPartyExtensions(false);
            rebindHostTavernHelperShims();
            // 发射 ST 标准就绪事件链，驱动 JS-Slash-Runner / ST-Prompt-Template / st-memory-enhancement / LittleWhiteBox 挂载设置面板
            try { await eventSource.emit(event_types.SETTINGS_LOADED); } catch (_e) { /* noop */ }
            try { await eventSource.emit(event_types.EXTENSION_SETTINGS_LOADED); } catch (_e) { /* noop */ }
            try { await eventSource.emit(event_types.SETTINGS_UPDATED); } catch (_e) { /* noop */ }
            try { await eventSource.emit('chatLoaded'); } catch (_e) { /* noop */ }
            try { await eventSource.emit(event_types.CHAT_CHANGED, state.chatId || 'default'); } catch (_e) { /* noop */ }
            try { await eventSource.emit(event_types.APP_READY); } catch (_e) { /* noop */ }
            await ensureExtensionSettingsUiMounted(extFolder);
        } catch (e) {
            const msg = (e && (e.stack || e.message)) || String(e);
            try { callBridge("log", "error", `[ext-settings] load failed: ${msg}`); } catch (_e) { /* noop */ }
            await ensureExtensionSettingsUiMounted(extFolder);
        }
        console.log(`${LOG_PREFIX} ext settings phase done in ${Date.now() - t0}ms`);
        try {
            emitToHost('ext_settings_loaded', { folder: extFolder });
        } catch (_e) { /* noop */ }
        normalizeExtensionSettingsDom();
        setTimeout(() => { ensureExtensionSettingsUiMounted(extFolder).then(normalizeExtensionSettingsDom); }, 300);
        setTimeout(() => { ensureExtensionSettingsUiMounted(extFolder).then(normalizeExtensionSettingsDom); }, 900);
        return;
    }
    // Panel 模式（C4）：消息内嵌渲染宿主 —— 启动时仅轻量拉取元数据与变量（refreshForPanelMode），按需惰性拉取聊天正文
    if (window.__RIKKA_PANEL_MODE__) {
        console.log(`${LOG_PREFIX} booting in PANEL mode ...`);
        if (typeof $ === 'undefined') safeWarn('jQuery missing! shims may break.');
        refreshForPanelMode();
        emitToHost('panel_ready', { version: '1.1.0' });
        installPanelMode();
        return;
    }
    console.log(`${LOG_PREFIX} booting ...`);
    // DOM / 全局就绪
    if (typeof $ === 'undefined') {
        safeWarn('jQuery missing! shims may break.');
    }
    ensureTavernHelperDom(['mvu-standalone']);
    // v206：JSR 会在 ready 回调里另建一个同名容器（文档序在我方之前）→ 短期轮询把身份 div 补进去
    keepTavernHelperDom(['mvu-standalone'], 30000);
    refreshAll();
    installUniversalFloatingAndTtsBridge();
    emitToHost('script_ready', { version: '1.1.0' });
    console.log(`${LOG_PREFIX} shims installed: ${Object.keys(TavernHelper).length} keys`);
    // [P0-1 根因1修复] 重排冷启动顺序，消除冷启动直落重度脚本卡（如大小姐）时的 CrRendererMain 死锁：
    // 1) 先装载第三方扩展（ST-Prompt-Template / JS-Slash-Runner，本地实测 ~450ms），
    //    确保 window.TavernHelper（180 keys）完整就绪后再启动角色卡脚本 iframe，避免 JSR 覆盖 TavernHelper 与 6 个 iframe 并发争抢。
    const extT0 = Date.now();
    try { await loadThirdPartyExtensions(false); } catch (_e) { /* noop */ }
    rebindHostTavernHelperShims();
    try {
        if (typeof $ === 'function') {
            $(() => { rebindHostTavernHelperShims(); });
        }
        setTimeout(rebindHostTavernHelperShims, 80);
        setTimeout(rebindHostTavernHelperShims, 350);
        await eventSource.emit(event_types.EXTENSION_SETTINGS_LOADED);
        await eventSource.emit(event_types.APP_READY);
    } catch (_e) { /* noop */ }
    console.log(`${LOG_PREFIX} third-party extension phase done in ${Date.now() - extT0}ms (window.Mvu=${!!window.Mvu})`);

    // 2) 探测当前角色卡脚本是否自带 MVU（含 MagVarUpdate）：若不自带，则先启动内置 MVU，保证卡脚本启动时 window.Mvu 已就绪
    let preHasScriptMvu = false;
    try {
        const rawScripts = callBridge('getTavernScriptsJson') || '{}';
        const helper = (JSON.parse(rawScripts) || {}).tavern_helper || null;
        const list = (helper && Array.isArray(helper.scripts)) ? helper.scripts : [];
        preHasScriptMvu = list.some(
            (s) => s && s.type === 'script' && scriptEnabled(s.enabled) &&
                /mvu_bundle_full|MVU-offline|MagVarUpdate/i.test(String(s.content || ''))
        );
    } catch (_e) { preHasScriptMvu = false; }
    if (!preHasScriptMvu && !window.Mvu) {
        try { await loadMvu(); } catch (_e) { /* noop */ }
    }

    // 3) 在 TavernHelper 与 MVU 已就绪的环境下，按 80ms 间隔串行分帧挂载卡脚本 iframe
    try { await loadTavernScripts(); } catch (_e) { /* noop */ }
    console.log(`${LOG_PREFIX} card scripts mounted: mvuFromScripts=${mvuFromScripts}, window.Mvu=${!!window.Mvu}`);

    // 4) 若卡脚本自带 MVU（如大小姐 MagVarUpdate）或 MVU 仍未就绪，走 loadMvu() 认领/兜底流程
    if (!window.Mvu || !mvuReady) {
        setTimeout(() => { loadMvu(); }, 300);
    }
    emitToHost('boot_complete', { extMs: Date.now() - extT0, mvuReady: !!window.Mvu });
})();

// 页面卸载前冲刷变量
window.addEventListener('pagehide', () => { try { flushAll(); } catch (_e) { /* noop */ } });


// JS SlashCommandParser 自定义命令回退执行桥（供宿主 StSlashExecutor 调用）
if (typeof window.__rikkaExecuteJsSlashCommand !== 'function') {
    window.__rikkaExecuteJsSlashCommand = async function (name, namedArgsJson, unnamedArgs, pipe, seq) {
        const reg = window.__rikkaStSlashRegistry;
        const cmd = reg && typeof reg.get === 'function'
            ? (reg.get(String(name)) || reg.get(String(name).toLowerCase()) || null)
            : null;
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
