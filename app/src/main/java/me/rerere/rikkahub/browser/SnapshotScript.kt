package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonPrimitive

/**
 * 页面侧脚本。全部用 IIFE + JSON.stringify 收敛成单个字符串返回值，
 * 走 evaluateJavascript 才拿得到结构化的东西。
 */
internal object SnapshotScript {

    /** 元素索引表：动作按索引取元素，模型不用写选择器。 */
    const val ELEMENTS = "__rhEls"

    val INIT = """
        (function(){
          if (window.__rhInit) { return 'ok'; }
          window.__rhInit = true;
          window.__rhLastMutation = Date.now();
          window.__rhPending = 0;
          try {
            new MutationObserver(function(){ window.__rhLastMutation = Date.now(); })
              .observe(document.documentElement, {childList:true, subtree:true, attributes:true, characterData:true});
          } catch (e) {}
          try {
            var origFetch = window.fetch;
            if (origFetch) {
              window.fetch = function(){
                window.__rhPending++;
                window.__rhLastMutation = Date.now();
                var p = origFetch.apply(this, arguments);
                if (p && p.then) {
                  p.then(function(){ window.__rhPending--; window.__rhLastMutation = Date.now(); },
                         function(){ window.__rhPending--; window.__rhLastMutation = Date.now(); });
                }
                return p;
              };
            }
          } catch (e) {}
          try {
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(){
              window.__rhPending++;
              window.__rhLastMutation = Date.now();
              var self = this;
              try {
                this.addEventListener('loadend', function(){
                  window.__rhPending = Math.max(0, window.__rhPending - 1);
                  window.__rhLastMutation = Date.now();
                });
              } catch (e) {}
              return origSend.apply(self, arguments);
            };
          } catch (e) {}
          return 'ok';
        })()
    """.trimIndent()

    val WAIT_PROBE = """
        (function(){
          try {
            return JSON.stringify({
              ready: document.readyState,
              quiet: Date.now() - (window.__rhLastMutation || 0),
              pending: window.__rhPending || 0
            });
          } catch (e) { return JSON.stringify({ready:'complete', quiet:9999, pending:0}); }
        })()
    """.trimIndent()

    val SNAPSHOT = """
        (function(){
          try {
            var SEL = 'a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[onclick],[contenteditable=true]';
            function vis(el) {
              var r = el.getBoundingClientRect();
              if (r.width < 2 || r.height < 2) { return false; }
              var s = getComputedStyle(el);
              if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') { return false; }
              return r.bottom > -window.innerHeight && r.top < window.innerHeight * 2;
            }
            function clean(t) {
              return (t || '').replace(new RegExp('\\s+', 'g'), ' ').trim().slice(0, 40);
            }
            var nodes = Array.prototype.slice.call(document.querySelectorAll(SEL)).filter(vis).slice(0, 240);
            window.$ELEMENTS = nodes;
            var lines = [];
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              var tag = el.tagName.toLowerCase();
              var label = clean(el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.getAttribute('alt'));
              var extra = '';
              if (tag === 'a' && el.href) {
                try {
                  var u = new URL(el.href);
                  extra = u.host + u.pathname + (u.searchParams.get('id') ? '?id=' + u.searchParams.get('id') : '');
                } catch (e) {}
              }
              if (tag === 'input' || tag === 'textarea') {
                extra += ' (' + (el.value ? 'len:' + String(el.value).length : 'empty') + ')';
              }
              if (el.disabled) { extra += ' disabled'; }
              lines.push('[' + i + '] ' + tag + ' "' + label + '"' + (extra ? ' ' + extra : ''));
            }
            var body = document.body || document.documentElement;
            var text = (body.innerText || '').replace(new RegExp('\\n{3,}', 'g'), '\n\n').trim();
            var seed = '';
            for (var j = 0; j < nodes.length; j++) {
              seed += (nodes[j].innerText || nodes[j].value || '') + '|' + (nodes[j].href || '') + '\n';
            }
            var h = 2166136261;
            for (var k = 0; k < seed.length; k++) { h ^= seed.charCodeAt(k); h = Math.imul(h, 16777619); }
            return JSON.stringify({
              ok: true,
              title: document.title || '',
              url: location.href,
              hash: (h >>> 0).toString(16),
              scrollY: Math.round(window.scrollY),
              scrollHeight: Math.round(body.scrollHeight),
              viewport: Math.round(window.innerHeight),
              count: nodes.length,
              lines: lines,
              text: text.slice(0, 40000),
              textLen: text.length
            });
          } catch (e) {
            return JSON.stringify({ ok: false, error: String(e) });
          }
        })()
    """.trimIndent()

    fun click(index: Int): String = """
        (function(){
          var el = window.$ELEMENTS && window.$ELEMENTS[$index];
          if (!el) { return JSON.stringify({ok:false, code:'NOT_FOUND'}); }
          try { el.scrollIntoView({block:'center'}); } catch (e) {}
          try { el.focus(); } catch (e) {}
          try { el.click(); } catch (e) { return JSON.stringify({ok:false, code:'NOT_FOUND', error:String(e)}); }
          return JSON.stringify({ok:true, tag: el.tagName.toLowerCase(), text: (el.innerText||'').trim().slice(0,40)});
        })()
    """.trimIndent()

    /** 兜底：模型确实给了 CSS 选择器时才用。 */
    fun clickSelector(selector: String): String = """
        (function(){
          try {
            var el = document.querySelector(${JsonPrimitive(selector)});
            if (!el) { return JSON.stringify({ok:false, code:'NOT_FOUND'}); }
            try { el.scrollIntoView({block:'center'}); } catch (e) {}
            el.click();
            return JSON.stringify({ok:true, tag: el.tagName.toLowerCase()});
          } catch (e) { return JSON.stringify({ok:false, code:'NOT_FOUND', error:String(e)}); }
        })()
    """.trimIndent()

    fun type(index: Int, text: String): String = """
        (function(){
          var el = window.$ELEMENTS && window.$ELEMENTS[$index];
          if (!el) { return JSON.stringify({ok:false, code:'NOT_FOUND'}); }
          try { el.scrollIntoView({block:'center'}); el.focus(); } catch (e) {}
          var value = ${JsonPrimitive(text)};
          try {
            var proto = (el.tagName === 'TEXTAREA') ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            window.__rhLastTyped = $index;
            return JSON.stringify({ok:true, len: String(el.value || '').length});
          } catch (e) { return JSON.stringify({ok:false, code:'NOT_FOUND', error:String(e)}); }
        })()
    """.trimIndent()

    fun submitSearch(): String = """
        (function(){
          var el = window.$ELEMENTS && window.$ELEMENTS[window.__rhLastTyped];
          if (!el) { return JSON.stringify({ok:false}); }
          try {
            var form = el.form;
            if (form && form.requestSubmit) { form.requestSubmit(); return JSON.stringify({ok:true, via:'form'}); }
            var ev = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true});
            el.dispatchEvent(ev);
            return JSON.stringify({ok:true, via:'enter'});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    fun scrollTo(y: Int): String = """
        (function(){ window.scrollTo(0, $y); return JSON.stringify({ok:true, y: Math.round(window.scrollY)}); })()
    """.trimIndent()

    fun scrollBy(dy: Int): String = """
        (function(){ window.scrollBy(0, $dy); return JSON.stringify({ok:true, y: Math.round(window.scrollY)}); })()
    """.trimIndent()

    fun scrollToIndex(index: Int): String = """
        (function(){
          var el = window.$ELEMENTS && window.$ELEMENTS[$index];
          if (!el) { return JSON.stringify({ok:false, code:'NOT_FOUND'}); }
          try { el.scrollIntoView({block:'center'}); } catch (e) { return JSON.stringify({ok:false, code:'NOT_FOUND'}); }
          return JSON.stringify({ok:true, y: Math.round(window.scrollY)});
        })()
    """.trimIndent()

    fun navigate(action: String): String = """
        (function(){
          try {
            var action = '$action';
            if (action === 'back') { history.back(); }
            else if (action === 'forward') { history.forward(); }
            else { location.reload(); }
            return JSON.stringify({ok:true});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    val LOGIN_PROBE = """
        (function(){
          try {
            var cookie = document.cookie || '';
            var hasCookie = /(^|;\s*)(cookie2|_tb_token_|sgcookie|cna)=/.test(cookie);
            var hasLoginForm = !!document.querySelector('#login-form, .login-box, form[class*=login], input[type=password]');
            var host = location.host || '';
            var onLoginHost = host.indexOf('login.') === 0 || host.indexOf('login.') > 0;
            var nick = '';
            var el = document.querySelector('[class*=nick], [id*=nick], .site-nav-user');
            if (el) { nick = (el.innerText || '').trim().slice(0, 20); }
            var captcha = !!document.querySelector('#nc_1_wrapper, .nc-container, [class*=captcha], [id*=captcha], [class*=slider]');
            return JSON.stringify({
              hasCookie: hasCookie, hasLoginForm: hasLoginForm, onLoginHost: onLoginHost,
              nick: nick, captcha: captcha
            });
          } catch (e) { return JSON.stringify({error:String(e)}); }
        })()
    """.trimIndent()

    val HAS_PASSWORD_FIELD = """
        (function(){
          try { return JSON.stringify({n: document.querySelectorAll('input[type=password]').length}); }
          catch (e) { return JSON.stringify({n:0}); }
        })()
    """.trimIndent()

    fun fillCredentials(username: String, password: String): String = """
        (function(){
          try {
            var u = document.querySelector('input[name*=user], input[name*=login], input[id*=user], input[type=text]');
            var p = document.querySelector('input[type=password]');
            if (!u || !p) { return JSON.stringify({ok:false, code:'NOT_FOUND'}); }
            function set(el, v) {
              el.focus();
              var desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
              if (desc && desc.set) { desc.set.call(el, v); } else { el.value = v; }
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
            }
            set(u, ${JsonPrimitive(username)});
            set(p, ${JsonPrimitive(password)});
            return JSON.stringify({ok:true, user: ${JsonPrimitive(username.take(1) + "***")}});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    fun products(limit: Int): String = """
        (function(){
          try {
            var links = Array.prototype.slice.call(document.querySelectorAll('a[href*="item.taobao.com"], a[href*="detail.tmall.com"], a[href*="item.htm"]'));
            var seen = {};
            var out = [];
            for (var i = 0; i < links.length && out.length < $limit; i++) {
              var a = links[i];
              var href = a.href || '';
              var id = '';
              try { id = new URL(href).searchParams.get('id') || ''; } catch (e) {}
              if (!id) { continue; }
              var key = id;
              if (seen[key]) { continue; }
              var card = a.closest('[class*=item], [class*=card], li') || a;
              var text = (card.innerText || '').replace(new RegExp('\\s+', 'g'), ' ').trim();
              var priceMatch = text.match(new RegExp('¥\\s?\\d+(\\.\\d+)?'));
              var salesMatch = text.match(new RegExp('\\d+(\\.\\d+)?万?\\+?\\s*人(付款|已买)'));
              var title = (a.getAttribute('title') || a.innerText || '').replace(new RegExp('\\s+', 'g'), ' ').trim().slice(0, 60);
              if (!title) { continue; }
              seen[key] = 1;
              out.push({
                title: title,
                price: priceMatch ? priceMatch[0].replace(new RegExp('\\s+', 'g'), '') : '',
                sales: salesMatch ? salesMatch[0].replace(new RegExp('\\s+', 'g'), '') : '',
                id: id,
                host: (function(){ try { return new URL(href).host; } catch (e) { return ''; } })()
              });
            }
            return JSON.stringify({ok:true, count: out.length, items: out});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    val PRODUCT_DETAIL = """
        (function(){
          try {
            function pick(sel) {
              var el = document.querySelector(sel);
              return el ? (el.innerText || el.textContent || '').replace(new RegExp('\\s+', 'g'), ' ').trim() : '';
            }
            var title = pick('h1') || pick('[class*=title]') || document.title;
            var body = (document.body ? document.body.innerText : '') || '';
            var priceMatch = body.match(new RegExp('¥\\s?\\d+(\\.\\d+)?'));
            var shop = pick('[class*=shop]') || pick('[class*=store]');
            var sku = [];
            var skuNodes = document.querySelectorAll('[class*=sku] button, [class*=sku] li, [class*=sku] span');
            for (var i = 0; i < skuNodes.length && sku.length < 20; i++) {
              var t = (skuNodes[i].innerText || '').trim();
              if (t && t.length < 20) { sku.push(t); }
            }
            var reviews = [];
            var reviewNodes = document.querySelectorAll('[class*=comment] [class*=content], [class*=review] p');
            for (var j = 0; j < reviewNodes.length && reviews.length < 3; j++) {
              var rt = (reviewNodes[j].innerText || '').replace(new RegExp('\\s+', 'g'), ' ').trim();
              if (rt.length > 4) { reviews.push(rt.slice(0, 80)); }
            }
            var countMatch = body.match(new RegExp('(\\d+(\\.\\d+)?万?)\\+?\\s*条(评价|评论)'));
            return JSON.stringify({
              ok: true,
              title: title.slice(0, 80),
              price: priceMatch ? priceMatch[0].replace(new RegExp('\\s+', 'g'), '') : '',
              shop: shop.slice(0, 40),
              sku: sku,
              reviewCount: countMatch ? countMatch[0] : '',
              reviews: reviews
            });
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    fun text(limit: Int): String = """
        (function(){
          try {
            var body = document.body || document.documentElement;
            var t = (body.innerText || '').replace(new RegExp('\\n{3,}', 'g'), '\n\n').trim();
            return JSON.stringify({ok:true, len: t.length, text: t.slice(0, $limit)});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    fun tables(limit: Int): String = """
        (function(){
          try {
            var out = [];
            var tables = document.querySelectorAll('table');
            for (var i = 0; i < tables.length && out.length < $limit; i++) {
              var rows = tables[i].querySelectorAll('tr');
              var rowOut = [];
              for (var j = 0; j < rows.length && j < 20; j++) {
                var cells = rows[j].querySelectorAll('td, th');
                var line = [];
                for (var k = 0; k < cells.length && k < 8; k++) {
                  line.push((cells[k].innerText || '').replace(new RegExp('\\s+', 'g'), ' ').trim().slice(0, 24));
                }
                if (line.length > 0) { rowOut.push(line.join(' | ')); }
              }
              if (rowOut.length > 0) { out.push(rowOut); }
            }
            return JSON.stringify({ok:true, tables: out});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()

    fun evalScript(script: String): String = """
        (function(){
          try {
            var r = (function(){ $script })();
            return JSON.stringify({ok:true, result: (typeof r === 'object') ? r : String(r)});
          } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
        })()
    """.trimIndent()
}
