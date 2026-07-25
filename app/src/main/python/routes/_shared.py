"""Shared utilities for mingli routes."""
import json
from datetime import datetime

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


def resolve_tz(tz_val, default=8.0):
    """Normalize tz input (IANA string or number) to float UTC offset.

    Accepts:
        - float/int: returned as-is (e.g. 8.0, 5.5)
        - string number: parsed to float (e.g. "8", "5.5")
        - IANA timezone: converted via pytz (e.g. "Asia/Shanghai" → 8.0)
        - None: returns default
    """
    if tz_val is None:
        return default
    if isinstance(tz_val, (int, float)):
        return float(tz_val)
    # Try IANA string
    try:
        import pytz
        tz = pytz.timezone(str(tz_val))
        offset = tz.utcoffset(datetime.now()).total_seconds() / 3600
        return offset
    except Exception:
        pass
    # Try float parse as last resort
    try:
        return float(tz_val)
    except (ValueError, TypeError):
        return default
