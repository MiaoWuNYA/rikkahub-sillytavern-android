/**
 * rikkaST st-compat: chat-dom.js
 * ============================================================
 * R1 —— ST `#chat` 楼层 DOM 桩（让卡内脚本 / ST-Prompt-Template 能真实读写楼层节点）
 */

const MEDIA_TOKEN = 'mes_media_wrapper';
const FILE_TOKEN = 'mes_file_wrapper';

let _doc = null;
let _chatEl = null;
let _format = (text) => String(text == null ? '' : text);
let _getChat = () => [];

/** 注入 document（Node 行为测试用；浏览器不调用则自动取全局 document）。 */
export function setDocument(doc) {
    _doc = doc || null;
    _chatEl = null;
}

/** 注入 Markdown->HTML 渲染器（runtime.js 传 stMessageFormatting）。 */
export function setFormatter(fn) {
    if (typeof fn === 'function') _format = fn;
}

export function getFormatter() { return _format; }

/** 注入「当前会话」取值器（chat_changed 无载荷时兜底重渲染用）。 */
export function setChatProvider(fn) {
    if (typeof fn === 'function') _getChat = fn;
}

function doc() {
    if (_doc) return _doc;
    if (typeof document !== 'undefined' && document) return document;
    return null;
}

function bodyOf(d) {
    return (d && (d.body || d.documentElement)) || null;
}

/** 兼容 jQuery / 数组式包装（ST-PT 常传 `$(el)`）：取里面的真实元素。 */
function asElement(el) {
    if (!el) return null;
    if (typeof el.appendChild === 'function' || typeof el.querySelector === 'function') return el;
    if (typeof el.length === 'number') {
        return (el[0] && typeof el[0].appendChild === 'function') ? el[0] : null;
    }
    return el;
}

/** 消息正文取值：对齐 ST `message?.extra?.display_text ?? message.mes`。 */
function messageText(message) {
    if (message == null) return '';
    if (typeof message === 'string') return message;
    if (message.extra && message.extra.display_text != null) return String(message.extra.display_text);
    if (message.mes != null) return String(message.mes);
    if (message.content != null) return String(message.content);
    return '';
}

/**
 * 确保 `#chat` 容器存在（始终隐藏，不污染扩展设置页或面板）。
 */
export function ensureChatDom() {
    const d = doc();
    if (!d) return null;
    let el = null;
    try { el = d.querySelector('#chat'); } catch (_e) { el = null; }
    if (!el) {
        el = d.createElement('div');
        el.setAttribute('id', 'chat');
        try { el.id = 'chat'; } catch (_e) { /* noop */ }
        const body = bodyOf(d);
        if (body && typeof body.appendChild === 'function') body.appendChild(el);
    }
    try { el.style.display = 'none'; } catch (_e) { /* noop */ }
    _chatEl = el;
    return el;
}

export function getChatDom() { return _chatEl || ensureChatDom(); }

function clearChildren(el) {
    if (!el) return;
    try {
        while (el.firstChild) el.removeChild(el.firstChild);
    } catch (_e) {
        try { el.innerHTML = ''; } catch (_e2) { /* noop */ }
    }
}

function findFloor(root, mesId) {
    if (!root || typeof root.querySelector !== 'function') return null;
    try { return root.querySelector('[mesid="' + String(mesId) + '"]'); } catch (_e) { return null; }
}

function makeFloor(d, mesId, message) {
    const mes = d.createElement('div');
    mes.setAttribute('mesid', String(mesId));
    mes.classList.add('mes');
    if (message && message.is_user) mes.classList.add('is_user');
    if (message && message.is_system) mes.classList.add('is_system');
    const text = d.createElement('div');
    text.classList.add('mes_text');
    mes.appendChild(text);
    return mes;
}

/** 把一条消息渲染进（必要时新建的）楼层节点。返回楼层元素。 */
function renderFloor(d, root, mesId, message) {
    let floor = findFloor(root, mesId);
    if (!floor) {
        floor = makeFloor(d, mesId, message);
        if (root && typeof root.appendChild === 'function') root.appendChild(floor);
    }
    if (message && message.is_user && floor.classList) floor.classList.add('is_user');
    if (message && message.is_system && floor.classList) floor.classList.add('is_system');
    let textEl = null;
    try { textEl = floor.querySelector('.mes_text'); } catch (_e) { textEl = null; }
    if (!textEl) {
        textEl = d.createElement('div');
        textEl.classList.add('mes_text');
        floor.appendChild(textEl);
    }
    const idNum = Number(mesId);
    textEl.innerHTML = _format(
        messageText(message),
        message && message.name,
        !!(message && message.is_system),
        !!(message && message.is_user),
        Number.isFinite(idNum) ? idNum : 0,
        {},
        false,
    );
    return floor;
}

/**
 * 为每条消息建 `div.mes[mesid="i"]`（内含 `div.mes_text`），带 is_user / is_system class。
 */
export function renderFloors(chat) {
    const d = doc();
    const root = ensureChatDom();
    if (!d || !root) return 0;
    clearChildren(root);
    const list = Array.isArray(chat) ? chat : [];
    for (let i = 0; i < list.length; i++) {
        const floor = renderFloor(d, root, i, list[i]);
        addCopyToCodeBlocks(floor);
        appendMediaToMessage(list[i], floor);
    }
    return list.length;
}

/**
 * ST `updateMessageBlock(messageId, message, { rerenderMessage = true })`。
 */
export function updateMessageBlock(messageId, message, options) {
    const d = doc();
    const root = ensureChatDom();
    if (!d || !root) return false;
    const id = String(messageId);
    const opts = options || {};
    let floor = findFloor(root, id);
    if (!floor) {
        floor = makeFloor(d, id, message);
        if (typeof root.appendChild === 'function') root.appendChild(floor);
    }
    if (opts.rerenderMessage !== false) {
        renderFloor(d, root, id, message);
    }
    addCopyToCodeBlocks(floor);
    appendMediaToMessage(message, floor);
    return true;
}

function copyText(text) {
    try {
        if (typeof navigator !== 'undefined' && navigator && navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(String(text == null ? '' : text));
        }
    } catch (_e) { /* noop */ }
    return Promise.resolve(false);
}

export function addCopyToCodeBlocks(messageElement) {
    const d = doc();
    messageElement = asElement(messageElement);
    if (!d || !messageElement || typeof messageElement.querySelectorAll !== 'function') return 0;
    let blocks = [];
    try { blocks = Array.prototype.slice.call(messageElement.querySelectorAll('pre code')); } catch (_e) { blocks = []; }
    for (const code of blocks) {
        let has = null;
        try { has = code.querySelector('.code-copy'); } catch (_e) { has = null; }
        if (has) continue;
        const btn = d.createElement('i');
        btn.classList.add('fa-solid', 'fa-copy', 'code-copy', 'interactable');
        btn.setAttribute('title', 'Copy code');
        try {
            btn.addEventListener('click', (e) => { if (e && typeof e.stopPropagation === 'function') e.stopPropagation(); });
            btn.addEventListener('pointerup', () => { copyText(code.textContent); });
        } catch (_e) { /* noop */ }
        try { code.appendChild(btn); } catch (_e) { /* noop */ }
    }
    return blocks.length;
}

function mediaUrl(entry) {
    if (typeof entry === 'string') return entry;
    if (entry && typeof entry === 'object') return String(entry.url || entry.src || '');
    return '';
}

export function appendMediaToMessage(mes, messageElement) {
    const d = doc();
    messageElement = asElement(messageElement);
    if (!d || !messageElement) return 0;
    const extra = (mes && mes.extra) || {};
    const media = Array.isArray(extra.media) ? extra.media : [];
    const files = Array.isArray(extra.files) ? extra.files : [];
    let added = 0;
    if (media.length > 0) {
        let wrapper = null;
        try { wrapper = messageElement.querySelector('.' + MEDIA_TOKEN); } catch (_e) { wrapper = null; }
        if (!wrapper) {
            wrapper = d.createElement('div');
            wrapper.classList.add(MEDIA_TOKEN);
            try { messageElement.appendChild(wrapper); } catch (_e) { /* noop */ }
        }
        clearChildren(wrapper);
        for (let i = 0; i < media.length; i++) {
            const img = d.createElement('img');
            img.classList.add('mes_img');
            img.setAttribute('data-index', String(i));
            img.setAttribute('src', mediaUrl(media[i]));
            try { wrapper.appendChild(img); } catch (_e) { /* noop */ }
            added++;
        }
        try { messageElement.setAttribute('data-media-display', extra.inline_image === false ? 'none' : 'inline'); } catch (_e) { /* noop */ }
    }
    if (files.length > 0) {
        let fw = null;
        try { fw = messageElement.querySelector('.' + FILE_TOKEN); } catch (_e) { fw = null; }
        if (!fw) {
            fw = d.createElement('div');
            fw.classList.add(FILE_TOKEN);
            try { messageElement.appendChild(fw); } catch (_e) { /* noop */ }
        }
        clearChildren(fw);
        for (const f of files) {
            const a = d.createElement('a');
            a.classList.add('mes_file');
            a.setAttribute('href', mediaUrl(f));
            a.textContent = String((f && f.name) || '');
            try { fw.appendChild(a); } catch (_e) { /* noop */ }
            added++;
        }
    }
    return added;
}

export function attachChatListeners(eventSource) {
    if (!eventSource || typeof eventSource.on !== 'function') return false;
    const handler = (payload) => {
        try {
            const msgs = payload && Array.isArray(payload.messages) ? payload.messages : null;
            if (msgs) renderFloors(msgs);
            else renderFloors(typeof _getChat === 'function' ? _getChat() : []);
        } catch (_e) { /* noop */ }
    };
    try {
        eventSource.on('chat_changed', handler);
        eventSource.on('chat_id_changed', handler);
    } catch (_e) { return false; }
    return true;
}

export const __internals = { messageText, findFloor, makeFloor, asElement };
