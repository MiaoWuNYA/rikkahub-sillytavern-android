'use strict';

/**
 * rikkaST Shared Node.js Server Plugin & Host Bridge Daemon
 * =========================================================
 * Runs inside the shared Linux Workspace (PRoot on Android, or local Node.js during tests).
 * - Binds strictly to 127.0.0.1 (loopback only).
 * - Enforces Bearer / X-CSRF-Token / Basic auth token validation.
 * - Enforces request body size limit (default 50MB) and per-request timeout.
 * - Redacts sensitive headers/keys (API keys, Bearer tokens) in logs.
 * - Provides an Express-compatible Router and mounts SillyTavern server plugins
 *   (including bundled and dynamically discovered `littlewhitebox-server` at
 *   `/api/plugins/littlewhitebox-server`).
 * - Isolates context per request via `assistantId`, `conversationId`, and `req.user.profile.handle`.
 * - Bridges `/api/backends/chat-completions/{status,generate}` and `/api/host/*`
 *   to the rikkaST Android/Kotlin host callback endpoint (or local state store).
 */

const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const { URL } = require('node:url');

const BIND_HOST = '127.0.0.1';
const PORT = Number(process.env.RIKKA_NODE_PORT || 18181);
const AUTH_TOKEN = String(process.env.RIKKA_NODE_TOKEN || '').trim();
const PLUGINS_DIR = process.env.RIKKA_NODE_PLUGINS_DIR || path.join(__dirname, 'plugins');
const EXTRA_PLUGINS_DIR = process.env.RIKKA_NODE_EXTRA_PLUGINS_DIR || '';
const HOST_CALLBACK_URL = String(process.env.RIKKA_HOST_CALLBACK_URL || '').trim();
const MAX_BODY_BYTES = Number(process.env.RIKKA_NODE_MAX_BODY_BYTES || 50 * 1024 * 1024);
const STARTED_AT = Date.now();

function redactSensitive(text) {
    if (typeof text !== 'string') return String(text ?? '');
    return text
        .replace(/(Bearer\s+)[A-Za-z0-9._\-+/=]{6,}/gi, '$1[REDACTED]')
        .replace(/(pst-[A-Za-z0-9._\-]{4})[A-Za-z0-9._\-]+/g, '$1[REDACTED]')
        .replace(/(sk-[A-Za-z0-9._\-]{4})[A-Za-z0-9._\-]+/g, '$1[REDACTED]')
        .replace(/("key"\s*:\s*")([^"]{6,})(")/gi, '$1[REDACTED]$3')
        .replace(/("token"\s*:\s*")([^"]{6,})(")/gi, '$1[REDACTED]$3');
}

function logInfo(msg, ...rest) {
    console.log(`[rikka-node-host] ${redactSensitive(String(msg))}`, ...rest.map(r => redactSensitive(typeof r === 'string' ? r : JSON.stringify(r))));
}

function logWarn(msg, ...rest) {
    console.warn(`[rikka-node-host] [WARN] ${redactSensitive(String(msg))}`, ...rest.map(r => redactSensitive(typeof r === 'string' ? r : JSON.stringify(r))));
}

function logError(msg, ...rest) {
    console.error(`[rikka-node-host] [ERROR] ${redactSensitive(String(msg))}`, ...rest.map(r => redactSensitive(typeof r === 'string' ? r : JSON.stringify(r))));
}

/**
 * Compile an Express-style route pattern (e.g. `/v1/jobs/:jobId/results/:index`)
 * into a RegExp and parameter name list.
 */
function compileRoutePattern(pattern) {
    const paramNames = [];
    const normalized = String(pattern || '/').replace(/\/+$/, '') || '/';
    const regexStr = normalized
        .split('/')
        .map((segment) => {
            if (segment.startsWith(':') && segment.length > 1) {
                paramNames.push(segment.slice(1));
                return '([^/]+)';
            }
            return segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        })
        .join('/');
    return {
        regex: new RegExp(`^${regexStr}/?$`),
        paramNames,
    };
}

function createExpressCompatRouter(prefix = '') {
    const routes = [];
    const middlewares = [];

    function register(method, routePath, ...handlers) {
        const fullPattern = (prefix.replace(/\/+$/, '') + '/' + String(routePath || '/').replace(/^\/+/, '')).replace(/\/+$/, '') || '/';
        const compiled = compileRoutePattern(fullPattern);
        routes.push({
            method: method.toUpperCase(),
            pattern: fullPattern,
            regex: compiled.regex,
            paramNames: compiled.paramNames,
            handlers: handlers.filter((h) => typeof h === 'function'),
        });
    }

    return {
        routes,
        middlewares,
        use(...args) {
            for (const fn of args) {
                if (typeof fn === 'function') middlewares.push(fn);
            }
            return this;
        },
        get(p, ...h) { register('GET', p, ...h); return this; },
        post(p, ...h) { register('POST', p, ...h); return this; },
        put(p, ...h) { register('PUT', p, ...h); return this; },
        delete(p, ...h) { register('DELETE', p, ...h); return this; },
        patch(p, ...h) { register('PATCH', p, ...h); return this; },
    };
}

/**
 * Decorate Node's `http.ServerResponse` with Express-compatible helper methods:
 * `.status(code)`, `.type(mime)`, `.send(body)`, `.json(obj)`.
 */
function decorateResponse(res) {
    res.status = function (code) {
        res.statusCode = Number(code) || 200;
        return res;
    };
    res.type = function (mime) {
        const resolved = mime === 'json' ? 'application/json; charset=utf-8'
            : (mime === 'text' ? 'text/plain; charset=utf-8' : String(mime));
        res.setHeader('Content-Type', resolved);
        return res;
    };
    res.json = function (obj) {
        if (!res.headersSent) {
            res.setHeader('Content-Type', 'application/json; charset=utf-8');
        }
        res.end(JSON.stringify(obj));
        return res;
    };
    res.send = function (payload) {
        if (Buffer.isBuffer(payload) || payload instanceof Uint8Array) {
            if (!res.getHeader('Content-Type')) {
                res.setHeader('Content-Type', 'application/octet-stream');
            }
            res.end(Buffer.from(payload));
            return res;
        }
        if (typeof payload === 'string') {
            if (!res.getHeader('Content-Type')) {
                res.setHeader('Content-Type', 'text/plain; charset=utf-8');
            }
            res.end(payload);
            return res;
        }
        if (payload === null || payload === undefined) {
            res.end('');
            return res;
        }
        if (!res.getHeader('Content-Type')) {
            res.setHeader('Content-Type', 'application/json; charset=utf-8');
        }
        res.end(JSON.stringify(payload));
        return res;
    };
    return res;
}

/**
 * Per-session isolated context store (keyed by `${assistantId}::${conversationId}`).
 * Prevents cross-assistant / cross-conversation data bleed in the shared Node.js process.
 */
const isolatedSessionContexts = new Map();

function getSessionBucket(assistantId, conversationId) {
    const aId = String(assistantId || 'default-assistant').trim() || 'default-assistant';
    const cId = String(conversationId || 'default-conversation').trim() || 'default-conversation';
    const key = `${aId}::${cId}`;
    if (!isolatedSessionContexts.has(key)) {
        isolatedSessionContexts.set(key, {
            assistantId: aId,
            conversationId: cId,
            variables: {},
            metadata: {},
            chat: [],
            worldbooks: {},
            updatedAt: Date.now(),
        });
    }
    return isolatedSessionContexts.get(key);
}

function isAuthorized(req) {
    if (!AUTH_TOKEN) return true;
    const authHeader = String(req.headers['authorization'] || '');
    if (authHeader === `Bearer ${AUTH_TOKEN}`) return true;
    if (/^Basic\s+/i.test(authHeader)) {
        try {
            const decoded = Buffer.from(authHeader.replace(/^Basic\s+/i, '').trim(), 'base64').toString('utf8');
            if (decoded === `rikka:${AUTH_TOKEN}` || decoded.endsWith(`:${AUTH_TOKEN}`)) return true;
        } catch (_e) { /* noop */ }
    }
    const csrf = String(req.headers['x-csrf-token'] || '');
    if (csrf === AUTH_TOKEN) return true;
    const rikkaToken = String(req.headers['x-rikka-token'] || '');
    if (rikkaToken === AUTH_TOKEN) return true;
    const cookie = String(req.headers['cookie'] || '');
    if (cookie.includes(`rikka_token=${AUTH_TOKEN}`)) return true;
    return false;
}

function readRequestBody(req, maxBytes) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let total = 0;
        let tooLarge = false;
        req.on('data', (chunk) => {
            if (tooLarge) return;
            total += chunk.length;
            if (total > maxBytes) {
                tooLarge = true;
                const err = new Error('Request entity too large');
                err.status = 413;
                reject(err);
                return;
            }
            chunks.push(chunk);
        });
        req.on('end', () => {
            if (tooLarge) return;
            const raw = Buffer.concat(chunks);
            resolve(raw);
        });
        req.on('error', reject);
    });
}

async function forwardToHostCallback(pathname, method, headers, rawBodyBuffer) {
    if (!HOST_CALLBACK_URL) return null;
    const targetUrl = new URL(pathname, HOST_CALLBACK_URL);
    const mod = targetUrl.protocol === 'https:' ? https : http;
    return await new Promise((resolve, reject) => {
        const reqOpts = {
            protocol: targetUrl.protocol,
            hostname: targetUrl.hostname,
            port: targetUrl.port,
            path: targetUrl.pathname + targetUrl.search,
            method,
            headers: Object.assign({}, headers, {
                host: targetUrl.host,
                'content-length': rawBodyBuffer ? String(rawBodyBuffer.length) : '0',
            }),
        };
        const upstreamReq = mod.request(reqOpts, (upstreamRes) => {
            const chunks = [];
            upstreamRes.on('data', (c) => chunks.push(c));
            upstreamRes.on('end', () => {
                resolve({
                    statusCode: upstreamRes.statusCode || 200,
                    headers: upstreamRes.headers || {},
                    body: Buffer.concat(chunks),
                });
            });
        });
        upstreamReq.on('error', reject);
        if (rawBodyBuffer && rawBodyBuffer.length > 0) upstreamReq.write(rawBodyBuffer);
        upstreamReq.end();
    });
}

const loadedPlugins = new Map();
const masterRoutes = [];

async function loadPluginFromDirectory(pluginDir) {
    const entryPath = path.join(pluginDir, 'index.js');
    if (!fs.existsSync(entryPath)) return null;
    try {
        let pluginMod = null;
        try {
            pluginMod = require(entryPath);
        } catch (_eCjs) {
            const { pathToFileURL } = require('node:url');
            const imported = await import(pathToFileURL(entryPath).href);
            pluginMod = (imported && imported.default && typeof imported.default.init === 'function') ? imported.default : imported;
        }
        const pluginId = String((pluginMod.info && pluginMod.info.id) || path.basename(pluginDir));
        if (loadedPlugins.has(pluginId)) return loadedPlugins.get(pluginId);

        const prefix = `/api/plugins/${pluginId}`;
        const router = createExpressCompatRouter(prefix);
        if (typeof pluginMod.init === 'function') {
            await pluginMod.init(router);
        }
        for (const r of router.routes) {
            masterRoutes.push(r);
        }
        const meta = {
            id: pluginId,
            name: (pluginMod.info && pluginMod.info.name) || pluginId,
            version: (pluginMod.info && pluginMod.info.version) || '1.0.0',
            description: (pluginMod.info && pluginMod.info.description) || '',
            prefix,
            dir: pluginDir,
            exit: typeof pluginMod.exit === 'function' ? pluginMod.exit : null,
            routeCount: router.routes.length,
        };
        loadedPlugins.set(pluginId, meta);
        logInfo(`Loaded server plugin: ${pluginId} v${meta.version} (${meta.routeCount} routes at ${prefix})`);
        return meta;
    } catch (err) {
        logError(`Failed to load plugin from ${pluginDir}: ${err.stack || err.message || err}`);
        return null;
    }
}

async function discoverAndMountPlugins() {
    const searchDirs = [PLUGINS_DIR, EXTRA_PLUGINS_DIR].filter(Boolean);
    for (const base of searchDirs) {
        if (!fs.existsSync(base)) continue;
        let entries = [];
        try { entries = fs.readdirSync(base, { withFileTypes: true }); } catch (_e) { continue; }
        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const candidateDirect = path.join(base, entry.name);
            if (fs.existsSync(path.join(candidateDirect, 'index.js')) && fs.existsSync(path.join(candidateDirect, 'manifest.json'))) {
                // Check if this is a server plugin (not a browser ES module extension)
                try {
                    const pkgPath = path.join(candidateDirect, 'package.json');
                    const isCommonJsServerPlugin = !fs.existsSync(pkgPath) || JSON.parse(fs.readFileSync(pkgPath, 'utf8')).type !== 'module';
                    const indexContent = fs.readFileSync(path.join(candidateDirect, 'index.js'), 'utf8');
                    if (isCommonJsServerPlugin && (indexContent.includes('module.exports') || indexContent.includes('exports.init'))) {
                        await loadPluginFromDirectory(candidateDirect);
                    }
                } catch (_e) { /* skip non-server dir */ }
            }
            // Also check `<ext>/server-plugin` or `<ext>/server` layouts (direct or nested sub-plugins)
            for (const subDirName of ['server-plugin', 'server']) {
                const nestedRoot = path.join(candidateDirect, subDirName);
                if (!fs.existsSync(nestedRoot)) continue;
                try {
                    if (fs.existsSync(path.join(nestedRoot, 'index.js'))) {
                        await loadPluginFromDirectory(nestedRoot);
                    }
                    const subEntries = fs.readdirSync(nestedRoot, { withFileTypes: true });
                    for (const sub of subEntries) {
                        if (sub.isDirectory()) {
                            await loadPluginFromDirectory(path.join(nestedRoot, sub.name));
                        }
                    }
                } catch (_e) { /* noop */ }
            }
        }
    }
}

const REQUEST_TIMEOUT_MS = Number(process.env.RIKKA_NODE_REQ_TIMEOUT_MS || 120000);

const server = http.createServer(async (req, res) => {
    req.setTimeout(REQUEST_TIMEOUT_MS);
    res.setTimeout(REQUEST_TIMEOUT_MS, () => {
        if (!res.headersSent) {
            res.statusCode = 504;
            res.setHeader('Content-Type', 'application/json; charset=utf-8');
            res.end(JSON.stringify({ ok: false, code: 'request_timeout', error: 'Node host request timed out' }));
        } else {
            res.destroy();
        }
    });
    decorateResponse(res);
    const parsedUrl = new URL(req.url || '/', `http://${req.headers.host || '127.0.0.1'}`);
    const pathname = parsedUrl.pathname;
    const method = String(req.method || 'GET').toUpperCase();

    // Always allow /health on loopback for liveness checks; require token on all other routes when AUTH_TOKEN is set
    if (pathname === '/health' && method === 'GET') {
        return res.status(200).send({
            ok: true,
            status: 'healthy',
            pid: process.pid,
            nodeVersion: process.version,
            arch: process.arch,
            platform: process.platform,
            uptimeMs: Date.now() - STARTED_AT,
            plugins: Array.from(loadedPlugins.values()).map((p) => ({
                id: p.id,
                name: p.name,
                version: p.version,
                prefix: p.prefix,
                routeCount: p.routeCount,
            })),
        });
    }

    if (!isAuthorized(req)) {
        return res.status(401).send({
            ok: false,
            code: 'unauthorized',
            error: 'Unauthorized loopback request: missing or invalid token',
        });
    }

    const assistantId = String(req.headers['x-rikka-assistant-id'] || 'default-assistant').trim() || 'default-assistant';
    const conversationId = String(req.headers['x-rikka-conversation-id'] || 'default-conversation').trim() || 'default-conversation';
    const canonicalHandle = (assistantId !== 'default-assistant' || conversationId !== 'default-conversation')
        ? `${assistantId}_${conversationId}`.replace(/[^A-Za-z0-9._:-]/g, '_')
        : 'default-user';
    const requestedProfileHeader = req.headers['x-rikka-profile-handle'] != null
        ? String(req.headers['x-rikka-profile-handle']).trim()
        : '';
    if (requestedProfileHeader && requestedProfileHeader !== canonicalHandle) {
        return res.status(403).send({
            ok: false,
            code: 'profile_identity_mismatch',
            error: `Rejected spoofed X-Rikka-Profile-Handle '${requestedProfileHeader}' for session '${canonicalHandle}'`,
        });
    }
    const profileHandle = canonicalHandle;

    req.user = {
        profile: {
            handle: profileHandle,
            name: 'User',
        },
    };
    req.rikkaContext = {
        assistantId,
        conversationId,
        profileHandle,
    };
    req.query = Object.fromEntries(parsedUrl.searchParams.entries());

    let rawBodyBuffer = Buffer.alloc(0);
    if (method !== 'GET' && method !== 'HEAD') {
        try {
            rawBodyBuffer = await readRequestBody(req, MAX_BODY_BYTES);
        } catch (err) {
            return res.status(err.status || 413).send({
                ok: false,
                code: 'payload_too_large',
                error: String(err.message || 'Payload too large'),
            });
        }
    }

    const contentType = String(req.headers['content-type'] || '').toLowerCase();
    if (rawBodyBuffer.length > 0 && contentType.includes('application/json')) {
        try {
            req.body = JSON.parse(rawBodyBuffer.toString('utf8'));
        } catch (err) {
            return res.status(400).send({
                ok: false,
                code: 'invalid_json',
                error: 'Malformed JSON body',
            });
        }
    } else if (rawBodyBuffer.length > 0) {
        try {
            req.body = JSON.parse(rawBodyBuffer.toString('utf8'));
        } catch (_e) {
            req.body = rawBodyBuffer;
        }
    } else {
        req.body = {};
    }

    // 1. Loopback Chat Completions endpoints (used by LittleWhiteBox draw-runs loopback-host-client.js)
    if (pathname === '/api/backends/chat-completions/status') {
        if (HOST_CALLBACK_URL) {
            try {
                const upstream = await forwardToHostCallback(pathname, method, req.headers, rawBodyBuffer);
                if (upstream) {
                    res.status(upstream.statusCode);
                    for (const [k, v] of Object.entries(upstream.headers)) {
                        if (v !== undefined) res.setHeader(k, v);
                    }
                    return res.end(upstream.body);
                }
            } catch (err) {
                logWarn(`Host callback /status error: ${err.message}`);
            }
        }
        return res.status(200).send({
            object: 'list',
            data: [{ id: 'rikka-host-model', name: 'rikka-host-model', object: 'model' }],
            model: 'rikka-host-model',
        });
    }

    if (pathname === '/api/backends/chat-completions/generate' && method === 'POST') {
        if (HOST_CALLBACK_URL) {
            try {
                const upstream = await forwardToHostCallback(pathname, method, req.headers, rawBodyBuffer);
                if (upstream) {
                    res.status(upstream.statusCode);
                    for (const [k, v] of Object.entries(upstream.headers)) {
                        if (v !== undefined) res.setHeader(k, v);
                    }
                    return res.end(upstream.body);
                }
            } catch (err) {
                logWarn(`Host callback /generate error: ${err.message}`);
            }
        }
        const reqBody = (req.body && typeof req.body === 'object') ? req.body : {};
        const msgs = Array.isArray(reqBody.messages) ? reqBody.messages : [];
        if (msgs.length === 0) {
            return res.status(400).send({ error: { message: 'messages is required', type: 'invalid_request_error' } });
        }
        const replyText = String(reqBody.mockReply || '[rikka-node-host loopback completion]');
        if (reqBody.stream === true) {
            res.status(200);
            res.setHeader('Content-Type', 'text/event-stream');
            res.setHeader('Cache-Control', 'no-cache');
            const chunk = JSON.stringify({
                id: 'chatcmpl-node',
                object: 'chat.completion.chunk',
                created: Math.floor(Date.now() / 1000),
                model: reqBody.model || 'rikka-host-model',
                choices: [{ index: 0, delta: { content: replyText }, finish_reason: null }],
            });
            const stopChunk = JSON.stringify({
                id: 'chatcmpl-node',
                object: 'chat.completion.chunk',
                created: Math.floor(Date.now() / 1000),
                model: reqBody.model || 'rikka-host-model',
                choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
            });
            res.write(`data: ${chunk}\n\n`);
            res.write(`data: ${stopChunk}\n\n`);
            res.write('data: [DONE]\n\n');
            return res.end();
        }
        return res.status(200).send({
            id: 'chatcmpl-node',
            object: 'chat.completion',
            created: Math.floor(Date.now() / 1000),
            model: reqBody.model || 'rikka-host-model',
            choices: [{
                index: 0,
                message: { role: 'assistant', content: replyText },
                finish_reason: 'stop',
            }],
        });
    }

    // 1.5 Dynamic plugin reload endpoint (/api/host/reload-plugins)
    if (pathname === '/api/host/reload-plugins' && method === 'POST') {
        await discoverAndMountPlugins();
        return res.status(200).send({
            ok: true,
            plugins: Array.from(loadedPlugins.values()).map((p) => ({
                id: p.id,
                name: p.name,
                version: p.version,
                prefix: p.prefix,
                routeCount: p.routeCount,
            })),
        });
    }

    // 2. Context-isolated Host State Bridge (/api/host/context)
    if (pathname === '/api/host/context') {
        const queryAssistant = parsedUrl.searchParams.get('assistantId');
        const queryConversation = parsedUrl.searchParams.get('conversationId');
        if ((queryAssistant && queryAssistant !== assistantId) || (queryConversation && queryConversation !== conversationId)) {
            return res.status(403).send({
                ok: false,
                code: 'session_identity_mismatch',
                error: 'Query session identity does not match authenticated header session identity',
            });
        }
        const bucket = getSessionBucket(assistantId, conversationId);
        if (method === 'GET') {
            return res.status(200).send({
                ok: true,
                assistantId: bucket.assistantId,
                conversationId: bucket.conversationId,
                profileHandle,
                variables: JSON.parse(JSON.stringify(bucket.variables)),
                metadata: JSON.parse(JSON.stringify(bucket.metadata)),
                chatLength: bucket.chat.length,
                updatedAt: bucket.updatedAt,
            });
        }
        if (method === 'POST') {
            const patch = (req.body && typeof req.body === 'object') ? req.body : {};
            if ((patch.assistantId && patch.assistantId !== assistantId) ||
                (patch.conversationId && patch.conversationId !== conversationId) ||
                (patch.profileHandle && patch.profileHandle !== profileHandle)) {
                return res.status(403).send({
                    ok: false,
                    code: 'session_identity_mismatch',
                    error: 'Body session identity override is forbidden',
                });
            }
            if (patch.variables && typeof patch.variables === 'object' && !Array.isArray(patch.variables)) {
                for (const [k, v] of Object.entries(patch.variables)) {
                    if (k === '__proto__' || k === 'constructor' || k === 'prototype') continue;
                    bucket.variables[k] = JSON.parse(JSON.stringify(v));
                }
            }
            if (patch.metadata && typeof patch.metadata === 'object' && !Array.isArray(patch.metadata)) {
                for (const [k, v] of Object.entries(patch.metadata)) {
                    if (k === '__proto__' || k === 'constructor' || k === 'prototype') continue;
                    bucket.metadata[k] = JSON.parse(JSON.stringify(v));
                }
            }
            if (Array.isArray(patch.chat)) {
                bucket.chat = JSON.parse(JSON.stringify(patch.chat));
            }
            bucket.updatedAt = Date.now();
            return res.status(200).send({
                ok: true,
                assistantId: bucket.assistantId,
                conversationId: bucket.conversationId,
                profileHandle,
                variables: JSON.parse(JSON.stringify(bucket.variables)),
                metadata: JSON.parse(JSON.stringify(bucket.metadata)),
                chatLength: bucket.chat.length,
                updatedAt: bucket.updatedAt,
            });
        }
    }

    // 3. Dispatch to mounted Server Plugins (/api/plugins/*)
    for (const route of masterRoutes) {
        if (route.method !== method) continue;
        const match = route.regex.exec(pathname);
        if (!match) continue;
        req.params = {};
        route.paramNames.forEach((name, idx) => {
            req.params[name] = decodeURIComponent(match[idx + 1] || '');
        });
        try {
            let idx = 0;
            const next = async (err) => {
                if (err) {
                    logError(`Route error on ${method} ${pathname}: ${err.stack || err}`);
                    if (!res.headersSent) {
                        res.status(err.status || 500).send({ ok: false, error: String(err.message || err) });
                    }
                    return;
                }
                const handler = route.handlers[idx++];
                if (!handler) return;
                await handler(req, res, next);
            };
            await next();
            return;
        } catch (err) {
            logError(`Unhandled route exception on ${method} ${pathname}: ${err.stack || err}`);
            if (!res.headersSent) {
                return res.status(err.status || 500).send({ ok: false, error: String(err.message || err) });
            }
            return;
        }
    }

    return res.status(404).send({
        ok: false,
        code: 'not_found',
        error: `No route matched ${method} ${pathname}`,
    });
});

server.on('error', (err) => {
    logError(`Server error (code=${err && err.code}): ${err && (err.stack || err.message || err)}`);
    process.exit(err && err.code === 'EADDRINUSE' ? 98 : 1);
});

async function start() {
    await discoverAndMountPlugins();
    server.listen(PORT, BIND_HOST, () => {
        const addr = server.address();
        const actualPort = (addr && typeof addr === 'object') ? addr.port : PORT;
        logInfo(`Listening on http://${BIND_HOST}:${actualPort} (pid=${process.pid}, node=${process.version})`);
    });
}

async function shutdown(signal) {
    logInfo(`Received ${signal}, shutting down plugins...`);
    for (const plugin of loadedPlugins.values()) {
        if (typeof plugin.exit === 'function') {
            try { await plugin.exit(); } catch (_e) { /* noop */ }
        }
    }
    server.close(() => {
        process.exit(0);
    });
    setTimeout(() => process.exit(0), 1500).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

start().catch((err) => {
    logError(`Fatal startup error: ${err.stack || err}`);
    process.exit(1);
});
