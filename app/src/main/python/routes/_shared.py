"""Shared utilities for mingli routes."""
import json
from datetime import datetime

_bridge = None

def _js(lib, code):
    if _bridge:
        try:
            raw = _bridge.evalJavascript(lib, code)
            # Handle Kotlin bridge errors (catch in PythonBridge.kt returns "Error: message")
            if isinstance(raw, str) and raw.startswith("Error:"):
                return json.dumps({"error": f"bridge:{raw}"}, ensure_ascii=False)
            # Kotlin bridge wraps eval results as {"result":"...","logs":"..."}
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


def jd_to_str(jd):
    """Julian Day (UT) → 人类可读 UTC 时间字符串，供 AI 直接解读。"""
    import datetime as _dt
    try:
        jd = float(jd)
        unix = (jd - 2440587.5) * 86400.0
        # JD 是 float，整分边界上可能有毫秒级浮点误差；
        # 先四舍五入到整秒再格式化，避免 03:59:59.999 被截断显示成 03:59。
        return _dt.datetime.fromtimestamp(round(unix), _dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    except Exception:
        return jd


def convert_caelus_dates(c):
    """原地把 Caelus 结果中的原始 JD 时间戳转换为 UTC 日期字符串。
    Caelus 时序技法返回 Julian Day 大数（如 2448026.6），AI 直接读会误读；
    此处按已知字段路径显式转换（不做全局数字扫描，避免误转经度/度数）。"""
    if not isinstance(c, dict):
        return c

    def _seg_list(segs):
        for seg in segs or []:
            if not isinstance(seg, dict):
                continue
            for f in ("start", "end"):
                if f in seg:
                    seg[f] = jd_to_str(seg[f])
            for s in seg.get("sub", []) or []:
                if isinstance(s, dict):
                    for f in ("start", "end"):
                        if f in s:
                            s[f] = jd_to_str(s[f])

    if "crossings" in c and isinstance(c["crossings"], dict):
        c["crossings"] = {k: [jd_to_str(j) for j in (v or [])] for k, v in c["crossings"].items()}
    if "stations" in c and isinstance(c["stations"], dict):
        c["stations"] = {k: [[jd_to_str(x[0]), x[1]] for x in (v or [])
                             if isinstance(x, (list, tuple)) and len(x) >= 1]
                         for k, v in c["stations"].items()}
    if "lunarPhases" in c and isinstance(c["lunarPhases"], list):
        c["lunarPhases"] = [[jd_to_str(x[0]), x[1]] for x in (c["lunarPhases"] or [])
                            if isinstance(x, (list, tuple)) and len(x) >= 1]
    if "riseSet" in c and isinstance(c["riseSet"], dict):
        for k in ("sun", "moon"):
            o = c["riseSet"].get(k) or {}
            for f in ("rise", "set"):
                if f in o:
                    o[f] = jd_to_str(o[f])
    if "eclipses" in c and isinstance(c["eclipses"], dict):
        for k in ("solar", "lunar"):
            for ev in c["eclipses"].get(k, []) or []:
                for f in ("tMax", "begin", "end", "penumbralBegin", "penumbralEnd",
                          "partialBegin", "partialEnd", "totalBegin", "totalEnd"):
                    if f in ev:
                        ev[f] = jd_to_str(ev[f])
    if "solarReturn" in c and isinstance(c["solarReturn"], list):
        c["solarReturn"] = [jd_to_str(j) for j in (c["solarReturn"] or [])]
    if "lunarReturn" in c and isinstance(c["lunarReturn"], list):
        c["lunarReturn"] = [jd_to_str(j) for j in (c["lunarReturn"] or [])]
    if "chartBrief" in c and isinstance(c["chartBrief"], dict) and "jdUt" in c["chartBrief"]:
        c["chartBrief"]["jdUt"] = jd_to_str(c["chartBrief"]["jdUt"])
    if "planetaryHour" in c and isinstance(c["planetaryHour"], dict):
        for f in ("start", "end"):
            if f in c["planetaryHour"]:
                c["planetaryHour"][f] = jd_to_str(c["planetaryHour"][f])
    if "voidOfCourse" in c and isinstance(c["voidOfCourse"], dict):
        for f in ("signExit", "nextAspect"):
            if f in c["voidOfCourse"]:
                c["voidOfCourse"][f] = jd_to_str(c["voidOfCourse"][f])
    if "firdaria" in c:
        _seg_list(c["firdaria"])
    if "zodiacalReleasing" in c and isinstance(c["zodiacalReleasing"], dict):
        for k in ("spirit", "fortune"):
            z = c["zodiacalReleasing"].get(k) or {}
            zr = z.get("zrRelease") or []
            _seg_list(z.get("zrRelease"))
    if "primaryDirections" in c and isinstance(c["primaryDirections"], list):
        for d in c["primaryDirections"]:
            if isinstance(d, dict) and "jd" in d:
                d["jd"] = jd_to_str(d["jd"])
    if "electional" in c and isinstance(c.get("electional"), dict):
        for hit in c["electional"].get("search", []) or []:
            if isinstance(hit, dict) and "jd" in hit:
                hit["jd"] = jd_to_str(hit["jd"])
    return c
