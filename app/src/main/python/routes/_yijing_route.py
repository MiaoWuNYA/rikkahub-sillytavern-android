"""Route:  yijing"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 六爻梅花 =====
def _yijing(method="time",seed=None,year=None,month=None,day=None,feature="all"):
    result={"system":"yijing","engine":"","_hint":
        "ichingshifa(Iching类)已返回起卦+解卦。meihua_yi梅花全API+taixuanshifa太玄+jingjue荆诀。JS引擎对照(IchingShifa)。自探索:dir(ichingshifa)/dir(meihua_yi)/dir(Taixuan)/dir(jingjue)"}
    hex_values=None
    # 主力: ichingshifa (APK环境)
    try:
        sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
        from ichingshifa import Iching
        i=Iching()
        if method=="dayan" or method=="manual":
            _js_load("iching-shifa-engine")
            js_yao=_js("iching-shifa-engine","JSON.stringify(IchingShifa.dayan())")
            result["iching_shifa_js_yao"]=js_yao
            gua_str=json.loads(js_yao).get("yao","697887") if isinstance(js_yao,str) else "697887"
            result["ichingshifa"]=str(i.qigua_manual(year or 2026,month or 1,day or 1,12,0,gua_str))
        elif method=="time" and all([year,month,day]):
            hex_data=i.qigua_time(year,month,day,12,0)
            result["ichingshifa"]=str(hex_data)
            try: result["daykong"]=str(hex_data.daykong_shikong())
            except: pass
            try: result["innate_cegui"]=str(hex_data.innate_cegui())
            except: pass
            try: result["acquired_cegui"]=str(hex_data.acquired_cegui())
            except: pass
            try:
                gz=getattr(hex_data,"time_dizhi",None) or getattr(hex_data,"ri_gan",None) or "癸"
                result["six_months_stars"]=str(hex_data.find_six_mons(gz))
            except: pass
            try:
                rg=getattr(hex_data,"ri_gan",None) or "癸"
                result["shier_luck"]=str(hex_data.find_shier_luck(rg))
            except: pass
            try: result["hutiangua"]=str(hex_data.hutiangua())
            except: pass
        elif method=="number":
            n=seed if seed is not None else 42
            hex_data=i.qigua_manual(2026,1,1,12,0,f"{n}")
            result["ichingshifa"]=str(hex_data)
        else:
            hex_data=i.qigua_now()
            result["ichingshifa"]=str(hex_data)
        result["engine"]+="ichingshifa"
        if hasattr(hex_data,'lines') or hasattr(hex_data,'values'):
            hex_values=getattr(hex_data,'lines',None) or getattr(hex_data,'values',None)
    except Exception as e: result["py_error"]=str(e)
    # 梅花易数 (全API)
    try:
        import meihua_yi
        result["engine"]+="+meihua_yi"
        if method=="coin":
            coin_result=meihua_yi.qigua_coin()
            result["meihua_coin"]={
                "lines":[y for y in coin_result[0]],
                "moving":[y for y in coin_result[1]],
                "details":coin_result[2],
            }
        else:
            time_result=meihua_yi.qigua_time()
            result["meihua_time"]={
                "lines":[y for y in time_result[0]],
                "moving":[y for y in time_result[1]],
            }
        # 通用梅花数据
        gua=result.get("meihua_coin") or result.get("meihua_time") or {}
        if gua.get("lines"):
            result["meihua_formatted"]=meihua_yi.format_hexagram_text(gua["lines"],gua.get("moving",[]))
            result["meihua_gua_name"]=meihua_yi.get_gua_name(meihua_yi.XIAN_TIAN.get(str(gua["lines"]),""))
            try:
                lines_list=gua["lines"]
                moving_positions=gua.get("moving",[])
                hg=meihua_yi.compute_hexagrams(lines_list,moving_positions)
                result["meihua_tiyong"]={"original":hg[0],"mutual":hg[1],"changed":hg[2],"ti_yong":hg[3]} if len(hg)>=4 else str(hg)
            except: pass
    except: pass
    # taixuanshifa / jingjue (如有)
    try:
        import taixuanshifa
        result["engine"]+="+taixuanshifa"
        if hasattr(taixuanshifa,'pan_from_code'):
            result["taixuan"]=str(taixuanshifa.pan_from_code(seed or "777777"))
    except: pass
    try:
        import jingjue
        result["engine"]+="+jingjue"
        if hasattr(jingjue,'jie'):
            result["jingjue"]=str(jingjue.jie(seed or "777777"))
    except: pass
    # JS双引擎 (feature="all"时补充)
    if feature=="all":
        _js_load("iching-shifa-engine")
        if hex_values and isinstance(hex_values, (list, tuple)):
            js_vals = json.dumps(list(hex_values))
            result["iching_shifa_js"] = _js("iching-shifa-engine",
                f"JSON.stringify(IchingShifa.interpret({js_vals}))")
        else:
            result["iching_shifa_js"] = _js("iching-shifa-engine",
                "JSON.stringify(IchingShifa.dayan())")
        result["engine"] += "+iching-shifa-engine"
    return result