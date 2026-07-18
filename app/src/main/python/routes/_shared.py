"""Shared utilities for mingli routes."""
import json

_bridge = None

def _js(lib, code):
    if _bridge:
        try:
            return _bridge.evalJavascript(lib, code)
        except Exception as e:
            return json.dumps({"error": f"JS: {e}"}, ensure_ascii=False)
    return json.dumps({"error": "bridge not available"})

def _js_load(lib):
    if _bridge:
        try:
            return _bridge.evalJavascript(lib, "")
        except Exception as e:
            return json.dumps({"error": f"load: {e}"}, ensure_ascii=False)
    return json.dumps({"error": "bridge not available"})
