/*
 * rikkaST: 第三方扩展依赖的浏览器全局变量。
 *
 * JSR(酒馆助手) 的 dist 把 highlight.js / showdown / Popper 当成 external global 使用；
 * 我们的 st-runtime 宿主没有加载这三个库时，JSR 在 ESM 求值阶段直接
 * `ReferenceError: hljs is not defined`，整条扩展加载失败，设置页黑屏。
 *
 * v212（§5.F 真库替换）：
 *  - highlight.js 已升级为真库（vendor/lib/hljs.min.js，BSD-3-Clause，v11.9.0，
 *    在宿主页于本文件之前加载）；本文件的 hljs 段只剩「真库缺失环境」的占位兜底。
 *  - showdown 本地无真库副本（ST 走 npm 依赖，参考库内无 bundled 产物），
 *    从「原样返回 String(text)」升级为轻量 Markdown 渲染：
 *    围栏代码 / atx 标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 链接 / 图片 /
 *    无序有序列表 / 引用 / 水平线 / 段落；HTML 直通（与 showdown 默认一致）。
 *    行内代码先提取后回填，避免内部符号被二次格式化。
 *    取得真 showdown.min.js 后可无缝替换（同名 API：Converter#makeHtml）。
 *  - Popper 保持占位（API 兼容、无真实定位；JSR 仅 tavern_helper_types_popup 一处使用）。
 */
(function () {
    if (typeof window.hljs === "undefined") {
        window.hljs = {
            highlightElement: function () {},
            highlightAll: function () {},
            configure: function () {},
            registerLanguage: function () {},
            getLanguage: function () { return null; },
            initHighlighting: function () {},
            initHighlightingOnLoad: function () {},
        };
    }

    if (typeof window.showdown === "undefined") {
        function esc(s) {
            return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
        }
        function StubConverter(opts) {
            this._opts = opts || {};
        }
        StubConverter.prototype.makeHtml = function (text) {
            if (text == null) return "";
            var src = String(text).replace(/\r\n?/g, "\n");
            var out = [];
            var codes = [];
            var lines = src.split("\n");
            var inFence = false, fenceLang = "", buf = [];
            var listType = null, quote = false, para = [];
            function flushPara() { if (para.length) { out.push("<p>" + para.join("<br>") + "</p>"); para = []; } }
            function flushList() { if (listType) { out.push(listType === "ul" ? "</ul>" : "</ol>"); listType = null; } }
            function flushQuote() { if (quote) { out.push("</blockquote>"); quote = false; } }
            function inline(s) {
                return s
                    .replace(/`([^`\n]+)`/g, function (_m, c) { codes.push("<code>" + esc(c) + "</code>"); return "\x00" + (codes.length - 1) + "\x00"; })
                    .replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, function (_m, alt, url) { return "<img alt=\"" + alt + "\" src=\"" + url + "\">"; })
                    .replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, function (_m, txt, url) { return "<a href=\"" + url + "\">" + txt + "</a>"; })
                    .replace(/\*\*([^*]+)\*\*/g, function (_m, b) { return "<strong>" + b + "</strong>"; })
                    .replace(/__([^_]+)__/g, function (_m, b) { return "<strong>" + b + "</strong>"; })
                    .replace(/(^|[^*])\*([^*\n]+)\*/g, function (_m, pre, em) { return pre + "<em>" + em + "</em>"; })
                    .replace(/~~([^~]+)~~/g, function (_m, d) { return "<del>" + d + "</del>"; })
                    .replace(/\x00(\d+)\x00/g, function (_m, n) { return codes[Number(n)]; });
            }
            for (var i = 0; i < lines.length; i++) {
                var line = lines[i];
                var m = line.match(/^\s*```(.*)$/);
                if (m) {
                    if (!inFence) { flushPara(); flushList(); flushQuote(); inFence = true; fenceLang = m[1].trim(); buf = []; }
                    else {
                        inFence = false;
                        var cls = fenceLang ? " class=\"language-" + fenceLang + "\"" : "";
                        out.push("<pre><code" + cls + ">" + esc(buf.join("\n")) + "</code></pre>");
                    }
                    continue;
                }
                if (inFence) { buf.push(line); continue; }
                var t = line.trim();
                if (!t) { flushPara(); flushList(); flushQuote(); continue; }
                m = t.match(/^(#{1,6})\s+(.*)$/);
                if (m) { flushPara(); flushList(); flushQuote(); out.push("<h" + m[1].length + ">" + inline(m[2]) + "</h" + m[1].length + ">"); continue; }
                if (/^(?:-{3,}|\*{3,}|_{3,})$/.test(t)) { flushPara(); flushList(); flushQuote(); out.push("<hr>"); continue; }
                m = t.match(/^>\s?(.*)$/);
                if (m) { flushPara(); flushList(); if (!quote) { out.push("<blockquote>"); quote = true; } out.push("<p>" + inline(m[1]) + "</p>"); continue; }
                flushQuote();
                m = t.match(/^[-*+]\s+(.*)$/);
                if (m) { flushPara(); if (listType !== "ul") { flushList(); out.push("<ul>"); listType = "ul"; } out.push("<li>" + inline(m[1]) + "</li>"); continue; }
                m = t.match(/^\d+[.)]\s+(.*)$/);
                if (m) { flushPara(); if (listType !== "ol") { flushList(); out.push("<ol>"); listType = "ol"; } out.push("<li>" + inline(m[1]) + "</li>"); continue; }
                flushList();
                para.push(inline(t));
            }
            if (inFence && buf.length) out.push("<pre><code>" + esc(buf.join("\n")) + "</code></pre>");
            flushPara(); flushList(); flushQuote();
            return out.join("\n");
        };
        window.showdown = {
            Converter: StubConverter,
            setFlavor: function () {},
            getDefaultOptions: function () { return {}; },
        };
        if (typeof console !== "undefined") console.log("[v212] showdown enhanced stub in use (lightweight markdown)");
    }

    if (typeof window.Popper === "undefined") {
        window.Popper = {
            createPopper: function () {
                return { update: function () {}, destroy: function () {} };
            },
        };
    }
})();
