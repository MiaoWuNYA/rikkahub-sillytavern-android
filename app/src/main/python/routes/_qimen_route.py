"""Route:  qimen"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,feature="qimen"):
    result={"system":"qimen","engine":feature,"_hint":"QimenEngine 7局4流派已返回日家。LiuRen一键排盘含课体+三传+神将+22原子函数。小六壬掌诀推算。自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)"}
    if feature in ("qimen","all"):
        _js_load("qimen-engine")
        result["qimen"]=_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'rijia',year:{year},month:{month},day:{day}}}))")
    if feature in ("liuren","all"):
        _js_load("liuren-engine")
        result["liuren"]=_js("liuren-engine",f"JSON.stringify(LiuRen.getLiuRenByDate(new Date({year},{month-1},{day},12,0)))")
    if feature in ("xiaoliuren","all"):
        from lunar_python import Lunar
        lunar=Lunar.fromYmd(year,month,day)
        result["xiaoliuren"]={"lunar_month":lunar.getMonth(),"lunar_day":lunar.getDay(),"hour":hour or 12}
    return result
