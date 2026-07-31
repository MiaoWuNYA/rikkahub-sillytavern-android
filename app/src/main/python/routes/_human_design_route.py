"""Route:  human design"""
import json, sys, os
from ._shared import _js, _js_load, resolve_tz_checked

# ===== 人类图 =====
def _human_design(year,month,day,hour,tz,minute=0,gene_keys=False,transits=False):
    try:
        tz_num, tz_warn=resolve_tz_checked(tz)
        date_str=f"{year}-{month:02d}-{day}"
        _js_load("natalengine-engine")
        hd_raw=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateHumanDesign('{date_str}',{hour+minute/60},{tz_num}))")
        hd=json.loads(hd_raw)
        result={"system":"human_design","engine":"natalengine-js","human_design":hd,"_hint":"已返回类型+权威+中心+通道+闸门+轮回交叉+Profile。基因钥匙:calculateGeneKeys(hdResult)。行运:calculateHDTransits(natalChart,transitDate,timezone)"}
        if tz_warn:
            result["tz_warning"]=tz_warn
        if gene_keys:
            try:
                result["gene_keys"]=json.loads(_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateGeneKeys({hd_raw}))"))
            except Exception:
                result["gene_keys_error"]="gene keys calculation failed"
        if transits:
            try:
                import datetime
                now=datetime.datetime.now()
                result["hd_transits"]=json.loads(_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateHDTransits({hd_raw},'{now.year}-{now.month:02d}-{now.day}',{tz_num}))"))
            except Exception:
                result["hd_transits_error"]="transits calculation failed"
        return result
    except Exception as e:
        return {"system":"human_design","engine":"natalengine-js","error":str(e)}
