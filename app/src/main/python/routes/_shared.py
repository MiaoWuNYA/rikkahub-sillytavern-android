"""Shared utilities for mingli routes."""
import json

_bridge = None

def _js(lib, code):
    if _bridge:
        try:
            raw = _bridge.evalJavascript(lib, code)
            # Kotlin bridge wraps eval results as {"result":"...","logs":"..."}
            # Extract the actual JS result value
            try:
                obj = json.loads(raw)
                if isinstance(obj, dict) and "result" in obj:
                    return obj["result"]
            except (json.JSONDecodeError, ValueError, TypeError):
                pass
            return raw
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

def compute_jd(year, month, day, hour, minute, tz_offset):
    """Compute Julian Day matching Caelus.isoToJd() result exactly.
    JD = 2440587.5 + (Unix timestamp ms) / 86400000"""
    import datetime
    dt_local = datetime.datetime(year, month, day, hour, minute, 0)
    dt_utc = dt_local - datetime.timedelta(hours=tz_offset)
    epoch = datetime.datetime(1970, 1, 1)
    delta = dt_utc - epoch
    return 2440587.5 + delta.total_seconds() / 86400.0
