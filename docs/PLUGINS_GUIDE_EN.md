# RikkaHub Plus Plugin Development Guide

> This is the English translation of [PLUGINS_GUIDE.md](PLUGINS_GUIDE.md).

> This guide is derived from the orangechat/Tumin plugin system (AGPL-3.0), trimmed to match the RikkaHub Plus implementation:
> declarative UI pages (`ui`), WebView custom pages (`customPage`/`customPageWebView`), event hooks (`hooks`), and prompt-template injection (`promptTemplate`) are not supported.
> Tool-style plugins (`tools` + `config`) are fully functional.

> This document targets plugin developers and explains how to write, package, and install plugins for RikkaHub Plus.

---

## 1. Quick Start

### 1.1 What Is a Plugin

A plugin is a **ZIP archive** containing:

```
my-plugin.zip
├── manifest.json      ← plugin metadata (required)
└── main.js            ← entry script (required)
```

To install, pick the file via "Settings → Plugins → Import ZIP", and the app automatically extracts and loads it.

### 1.2 Hello World

**manifest.json**

```json
{
  "id": "com.example.hello",
  "name": "Hello plugin",
  "description": "A minimal example plugin",
  "version": "1.0.0",
  "author": "Your name",
  "icon": "👋",
  "entry": "main.js",
  "tools": [
    {
      "name": "say_hello",
      "description": "Greet the user",
      "parameters": [
        {
          "name": "name",
          "type": "string",
          "description": "The user's name",
          "required": true
        }
      ]
    }
  ]
}
```

**main.js**

```javascript
function say_hello(params) {
  var name = params.name || "stranger";
  return {
    success: true,
    greeting: "Hello, " + name + "!"
  };
}

exports.say_hello = say_hello;
```

Once these two files are packed into a ZIP and imported, the AI can call the `say_hello` tool.

---

## 2. manifest.json Field Reference

| Field | Type | Required | Description |
|------|------|------|------|
| `id` | string | ✅ | Unique plugin identifier; reverse-domain format recommended, e.g. `com.example.plugin.name` |
| `name` | string | ✅ | Display name |
| `description` | string | ✅ | One-line description |
| `version` | string | ✅ | Version string, e.g. `1.0.0` |
| `author` | string | ✅ | Author name |
| `icon` | string | ✅ | Icon; an Emoji (e.g. `🌤️`) or a URL |
| `entry` | string | ✅ | Entry file path, relative to the plugin root, e.g. `main.js` |
| `detailCard` | string | ❌ | Name of the exported function for the detail-page data card, see 4.10 |
| `tools` | array | ❌ | Tools registered with the AI |
| `config` | array | ❌ | User configuration items; after installation a settings form appears on the plugin detail page |
| `permissions` | array | ❌ | Plugin permission declarations; currently `ai_chat` and `disable_native_selection` are supported |
| `allowedHosts` | array | ❌ | Network domain allowlist. Empty array = all network requests denied. `*` = allow all (not recommended) |
| `hooks` | array | ❌ | Event hooks, e.g. listening to message send/receive, daily scheduled runs |
| `promptTemplate` | string | ❌ | Template injected into the AI system prompt, teaching the AI how to use the plugin |
| `customPage` | string | ❌ | Built-in page identifier, e.g. `"memory_bank"` |
| `customPageWebView` | object | ❌ | WebView custom page configuration, `{ "entry": "ui/index.html" }` |
| `ui` | object | ❌ | Declarative UI definition, rendered as a native Compose interface |

### 2.1 tools Tool Definition

```json
{
  "name": "tool function name (used when the AI calls it)",
  "description": "functionality description the AI sees",
  "parameters": [
    {
      "name": "parameter name",
      "type": "string | number | integer | boolean | object | array",
      "description": "parameter description",
      "required": false
    }
  ]
}
```

The tool function name must exactly match the function name exported as `exports.xxx` in `main.js`.

### 2.2 config Configuration Items

```json
{
  "config": [
    {
      "name": "api_key",
      "type": "string",
      "label": "API Key",
      "description": "Your API key",
      "required": true,
      "placeholder": "sk-..."
    },
    {
      "name": "enabled",
      "type": "boolean",
      "label": "Enable feature",
      "default": true
    },
    {
      "name": "model",
      "type": "model",
      "label": "Choose a model",
      "description": "Model used for AI calls"
    }
  ]
}
```

Supported `type` values: `string`, `number`, `boolean`, `select`, `password`, `model`.

Config values are injected at runtime as the global variable `config`, e.g. `config.api_key`.

### 2.3 hooks Event Hooks

```json
{
  "hooks": [
    { "event": "message_sent", "handler": "onMessageSent" },
    { "event": "message_received", "handler": "onMessageReceived" },
    { "event": "daily_cron", "handler": "onDailyCron", "schedule": "0 3 * * *" }
  ]
}
```

| Event | Trigger timing | event payload |
|------|---------|-----------|
| `message_sent` | User sent a message, it is persisted, before the AI replies | `{ assistant_id, conversation_id, message, role: "user", timestamp }` |
| `message_received` | AI reply generated and persisted | `{ assistant_id, conversation_id, message, role: "assistant", timestamp }` |
| `daily_cron` | Fired on a daily schedule (default 03:00) | `{ timestamp, date, hour, minute }` |

> ⚠️ All hooks run serially on a single thread; a hook that exceeds 16.5 seconds is skipped.

---

## 3. Writing main.js

### 3.1 Runtime Environment

- **Engine**: QuickJS (ES5 subset)
- **Forbidden**: `let`, `const`, arrow functions `=>`, template literals `` ` ``, `async/await`, `Promise`
- **Required**: declare variables with `var`, define functions with classic `function`, concatenate strings with `+`

### 3.2 Module Exports

Export tool functions through the `exports` object:

```javascript
function my_tool(params) {
  // ...
}
exports.my_tool = my_tool;
```

Only functions on `exports` are recognized by the AI as available tools.

### 3.3 Return Value Format

A tool function returns a **plain object**; the app serializes it to JSON and shows it to the AI:

```javascript
return {
  success: true,
  data: "...",
  error: null   // fill in the error message on failure
};
```

---

## 4. Sandbox Built-in APIs

### 4.1 Network Requests — fetch (synchronous)

```javascript
var response = fetch(url, options);
```

| Parameter | Description |
|------|------|
| `url` | Request URL |
| `options.method` | `GET` / `POST` / `PUT` / `DELETE`, default `GET` |
| `options.headers` | Request header object |
| `options.body` | Request body string |

**Return value**:

```javascript
{
  ok: true,           // true for HTTP 2xx
  status: 200,
  headers: { "content-type": "application/json" },
  body: "...",        // raw response text
  text: function() { return this.body; },
  json: function() { return JSON.parse(this.body); }
}
```

**Example**:

```javascript
function get_weather(params) {
  var city = params.city || "Beijing";
  var url = "https://wttr.in/" + encodeURIComponent(city) + "?format=j1";
  var response = fetch(url);

  if (!response.ok) {
    return { success: false, error: "request failed" };
  }

  var data = response.json();
  return {
    success: true,
    temperature: data.current_condition[0].temp_C + "°C"
  };
}
exports.get_weather = get_weather;
```

> ⚠️ `fetch` is a **synchronous blocking** call with a 15-second timeout. The target domain must be declared in `manifest.allowedHosts`, otherwise the request is rejected.

### 4.2 Configuration — config

```javascript
var apiKey = config.api_key;        // read the value the user entered on the settings page
var enabled = config.enabled;
```

A `type: "model"` config is automatically resolved into an object:

```javascript
// manifest: { "name": "chat_model", "type": "model" }
var modelId = config.chat_model.modelId;
var baseUrl = config.chat_model.baseUrl;
var apiKey  = config.chat_model.apiKey;
```

### 4.3 Memory Bank — memoryBank

```javascript
memoryBank.save("The user likes lattes");                 // save a memory
var results = memoryBank.recall("What coffee does the user like", 3); // semantic search, up to 3 results
var list = memoryBank.search("coffee", "text", 10);       // keyword search
memoryBank.delete("memory-id");                           // delete a memory (reserved)
```

### 4.4 Data Storage — dataStore

Each plugin has its own key-value storage space:

```javascript
dataStore.set("counter", 42);
var count = dataStore.get("counter");   // returns null if absent
dataStore.del("counter");
var keys = dataStore.list("prefix");    // list all keys by prefix
```

### 4.5 Music Player — musicPlayer (requires permission)

```javascript
musicPlayer.play("/storage/.../music.mp3", "Song title", "Artist");
musicPlayer.pause();
musicPlayer.resume();
musicPlayer.stop();
var status = musicPlayer.getStatus();   // { state, title, artist }
```

### 4.6 Console

```javascript
console.log("normal log");
console.info("info");
console.warn("warning");
console.error("error");
```

Output goes to Android Logcat under the tag `PluginSandbox`.

### 4.7 Encoding Utilities

```javascript
var encoded = btoa("hello");           // Base64 encode
var decoded = atob(encoded);            // Base64 decode

var encoder = new TextEncoder();
var bytes = encoder.encode("hello");    // Uint8Array

var decoder = new TextDecoder();
var text = decoder.decode(bytes);       // "hello"
```

### 4.8 Session-aware HTTP — `http.*`

`fetch` is a one-shot bare request: no cookies, no binary handling.
When you need flows like "GET the login page to obtain a session, then POST with it, and the server may rotate a new session after login" (academic systems, forums, any old website with a login state), use `http.*` —
it carries its own cookie jar; the host automatically saves the `Set-Cookie` from every hop's response and sends it along with subsequent requests.

```javascript
var r = http.get("https://example.com/login");
r.status;      // 200
r.ok;          // true / false
r.url;         // final URL (after following redirects)
r.redirected;  // whether any redirect happened
r.text();      // body (string)
r.json();      // body parsed as JSON
r.bytes();     // Uint8Array — use this for binary content like images
r.base64();    // body as base64 text

http.postForm("https://example.com/login", { user: "a", pass: "b" });  // form-urlencoded
http.postJson("https://example.com/api", { a: 1 });
http.post("https://example.com/raw", "raw body as-is");
// also available: http.put / http.delete / http.head / http.request(method, url, options)

http.cookies("https://example.com");   // inspect the cookies currently stored (for debugging)
http.clearCookies();                   // clear (log out)
```

`options` supports `{ headers: {...}, contentType: "...", timeoutMs: 20000 }`.
Domains are restricted by `manifest.allowedHosts` as well; undeclared domains are rejected.

### 4.9 Image Decoding — `image.decode`

There is no `canvas` in the sandbox; use this when you need per-pixel image processing (captcha recognition, color picking, thumbnail analysis):

```javascript
var img = image.decode(http.get(url).bytes());  // a base64 string works too
img.width;    // width in pixels
img.height;   // height in pixels
img.pixels;   // Uint8ClampedArray, RGBA order, 4 bytes per pixel
```

The `pixels` layout is identical to the browser's `canvas.getImageData().data`,
so existing canvas image algorithms can be moved over as-is:

```javascript
for (var y = 0; y < img.height; y++) {
  for (var x = 0; x < img.width; x++) {
    var i = (y * img.width + x) * 4;
    var gray = 0.299 * img.pixels[i] + 0.587 * img.pixels[i + 1] + 0.114 * img.pixels[i + 2];
  }
}
```

### 4.10 Detail-Page Data Card — `detailCard`

To show a live status block on the plugin detail page (today's intake, current logged-in account, number of to-dos),
declare `detailCard` in `manifest.json` as the **name** of an exported function:

```json
{
  "entry": "main.js",
  "detailCard": "detail_card"
}
```

```javascript
function detail_card(params) {
  return {
    title: "Today's intake",                            // required; if empty the whole card is hidden
    items: [                                           // optional
      { label: "Calories", value: "1200 kcal" },
      { label: "Protein", value: "65 g" }
    ],
    note: "Most recent: lunch · beef noodles"            // optional
  };
}
exports.detail_card = detail_card;
```

The host calls it once when the detail page opens, with an 8-second timeout. If the function throws or times out, the card is simply not shown; other plugin features are unaffected. So this is only suitable for local computation or reading a cache —
**do not issue network requests inside `detailCard`** — 8 seconds is easily not enough on campus Wi-Fi or a weak connection.


---

## 5. promptTemplate (Teaching the AI About Your Plugin)

Provide `promptTemplate` in `manifest.json`, and after installation the app automatically injects it into the AI system prompt:

```json
{
  "promptTemplate": "## Weather plugin\nYou can use the `get_weather` tool to look up the weather in a given city. Parameter: city (city name, required)."
}
```

This lets the AI proactively call your plugin tools in conversation, without the user having to describe them manually in the system prompt.

---

## 6. Packaging and Installing

### 6.1 Packaging

Compress `manifest.json` and `main.js` (plus resources used by the WebView, such as HTML/CSS/JS) into a ZIP:

```bash
zip -r my-plugin.zip manifest.json main.js
```

### 6.2 Installation

1. Open the app → Settings → Plugins
2. Tap "Import plugin"
3. Pick the ZIP file
4. Confirm the manifest details → tap "Install"

After installation the app:
- validates the manifest.json format
- verifies the entry file exists
- computes and stores a SHA-256 integrity checksum
- loads the tools into the AI tool list

### 6.3 Network Allowlist

`manifest.allowedHosts` controls which domains a plugin can access:

```json
{
  "allowedHosts": ["api.openweathermap.org", "wttr.in"]
}
```

- Empty array `[]` = **all network requests denied**
- `["*"]` = allow all domains (not recommended; for development and debugging only)
- The list of declared domains is shown to the user at install time

---

## 7. WebView Plugins

If your plugin needs a complex custom interface (charts, rich-text editors, games, etc.), you can use the **WebView** approach.

### 7.1 Declaration

Declare `customPageWebView` in `manifest.json`:

```json
{
  "customPageWebView": {
    "entry": "ui/index.html"
  }
}
```

Plugin directory layout:

```
my-plugin/
├── manifest.json
├── main.js
└── ui/
    ├── index.html
    ├── style.css
    └── app.js
```

### 7.2 WebView ↔ Native Bridge

JavaScript inside the WebView can interact with the app's native side through the `Bridge` object:

```javascript
// call a function exported from the plugin's main.js
Bridge.callJSFunction("myTool", JSON.stringify({ key: "value" }));

// call the AI to generate text (requires the ai_chat permission)
Bridge.callAI("Hello, please introduce yourself");

// get the plugin configuration
var config = JSON.parse(Bridge.getConfig());

// read the PluginDataStore
var value = Bridge.dataStoreGet("myKey");
Bridge.dataStoreSet("myKey", "hello");

// invoke biometric verification
Bridge.verifyFingerprint();

// control music playback
Bridge.musicPlayerPlay("/path/to/music.mp3", "Song title", "Artist");

// haptic feedback
Bridge.vibrate(100);

// get device info
var info = JSON.parse(Bridge.getDeviceInfo());
```

### 7.3 Events in the WebView

A WebView page can declare event bindings via `hookConfigs`:

```json
{
  "hookConfigs": {
    "onPageTurn": {
      "action": "call_js_function",
      "function": "onPageChanged",
      "autoTrigger": true
    },
    "onAnnotationAdded": {
      "action": "call_ai",
      "promptTemplate": "The user wrote an annotation in \"{book}\", chapter {chapter}:\nQuote: \"{quote}\"\nThoughts: {note}",
      "autoTrigger": true
    }
  }
}
```

### 7.4 Built-in Page Identifiers

If you don't want a WebView, you can also use the app's built-in pages:

```json
{
  "customPage": "memory_bank"
}
```

Currently supported built-in pages: `memory_bank` (memory bank management).

---

## 8. Declarative UI (Advanced)

Besides WebView, a plugin can declare a native Compose interface through the `"ui"` field in `manifest.json`, with no HTML required.

### 7.1 Basic Structure

```json
{
  "ui": {
    "title": "Plugin management page",
    "components": [
      { "type": "stats", "items": [...] },
      { "type": "button_row", "buttons": [...] },
      { "type": "card_grid", "queryName": "items", ... }
    ],
    "queries": {
      "items": { "type": "dataStore_list", "prefix": "item:" }
    },
    "actions": {
      "create": { "type": "dataStore_set", "keyField": "id" },
      "delete": { "type": "dataStore_delete" }
    }
  }
}
```

### 7.2 Supported Components

| Component | Description |
|------|------|
| `stats` | Statistic number cards |
| `search_bar` | Search input box |
| `filter_bar` | Filter chips |
| `card_grid` | Card grid (supports images, title, subtitle, tags, delete) |
| `card_list` | Card list |
| `button_row` | Row of buttons |
| `dialog_form` | Dialog form (create/edit data) |
| `section` | Grouped section |
| `text` | Text paragraph |
| `empty_state` | Empty-state hint |

### 7.3 Form Field Types

`string`, `integer`, `boolean`, `select`, `image`, `file`, `multiline`

---

## 8. FAQ

**Q1: The AI called my tool but got undefined?**

Check that `exports.xxx` is exported correctly and that the function name exactly matches `tools[].name` in `manifest.json`.

**Q2: fetch returns "Host not allowed"?**

Add the target domain to `manifest.allowedHosts`, then re-import the plugin.

**Q3: After installing, the plugin reports "integrity check failed"?**

Do not manually modify plugin files under the `Orangechat/plugins/` directory. If you need to change something, repack the ZIP and import it again.

**Q4: How do I debug a plugin?**

Log with `console.log()` and filter the Logcat in Android Studio by the `PluginSandbox` tag.

**Q5: Can a plugin call the AI?**

Declare `"permissions": ["ai_chat"]`, then call through the Bridge (currently only supported via the declarative UI and the `call_ai` action in the WebView).

---

## 9. Sample Plugins

The project ships with official sample plugins you can reference directly:

| Plugin | Path | Highlights |
|------|------|------|
| Calories & Protein tracker | `docs/plugins/calorie/` | Minimal working tool set: dataStore for persistence, detailCard for the detail-page card, daily aggregation |
| YNUFE academic system | `docs/plugins/ynufe/` | Full-fledged case: `http.*` to keep a login session, `image.decode` for captcha OCR, multiple source files stitched into a single entry |
