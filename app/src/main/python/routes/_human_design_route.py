"""Route:  human design"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 人类图 =====
def _human_design(year,month,day,hour,tz,gene_keys=False,transits=False):
    tz=float(tz) if tz is not None else 8.0
    date_str=f"{year}-{month:02d}-{day}"
    _js_load("natalengine-engine")
    hd=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateHumanDesign('{date_str}',{hour},{tz}))")
    result={"system":"human_design","engine":"natalengine-js","human_design":hd,"_hint":"已返回类型+权威+中心+通道+闸门+轮回交叉+Profile。基因钥匙:calculateGeneKeys(hdResult)。行运:calculateHDTransits(natalChart,transitDate,timezone)"}
    if gene_keys:
        result["gene_keys"]=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateGeneKeys(JSON.parse({hd})))")
    if transits:
        import datetime
        now=datetime.datetime.now()
        result["hd_transits"]=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateHDTransits(JSON.parse({hd}),'{now.year}-{now.month:02d}-{now.day}',{tz}))")
    return result
