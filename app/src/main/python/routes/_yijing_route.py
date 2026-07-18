"""Route:  yijing"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 六爻梅花 =====
def _yijing(method="time",seed=None,year=None,month=None,day=None,feature="all"):
    result={"system":"yijing","engine":"","_hint":"ichingshifa(Iching类)已返回起卦+解卦。meihua_yi梅花/taixuanshifa太玄/jingjue荆诀可用。JS双引擎对照(IchingShifa)。自探索: dir(ichingshifa)/dir(meihua_yi)/dir(Taixuan)/dir(jingjue)"}
    hex_values=None
    try:
        sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
        from ichingshifa import Iching
        i=Iching()
        if method=="dayan" or method=="manual":
            # 先调JS取爻值（双引擎用同一组）
            _js_load("iching-shifa-engine")
            js_yao=_js("iching-shifa-engine","JSON.stringify(IchingShifa.dayan())")
            result["iching_shifa_js_yao"]=js_yao
            # 传同爻值给Python
            gua_str=json.loads(js_yao).get("yao","697887") if isinstance(js_yao,str) else "697887"
            result["ichingshifa"]=str(i.qigua_manual(year or 2026,month or 1,day or 1,12,0,gua_str))
        elif method=="time" and all([year,month,day]):
            hex_data=i.qigua_time(year,month,day,12,0)
            result["ichingshifa"]=str(hex_data)
        elif method=="number":
            n=seed if seed is not None else 42
            hex_data=i.qigua_manual(2026,1,1,12,0,f"{n}")
            result["ichingshifa"]=str(hex_data)
        else:
            hex_data=i.qigua_now()
            result["ichingshifa"]=str(hex_data)
        result["engine"]+="ichingshifa"
        # 尝试取爻值
        if hasattr(hex_data,'lines') or hasattr(hex_data,'values'):
            hex_values=getattr(hex_data,'lines',None) or getattr(hex_data,'values',None)
    except Exception as e: result["py_error"]=str(e)
    # meihua_yi
    try:
        import meihua_yi
        if method=="time" and all([year,month,day]):
            result["meihua"]=str(meihua_yi.qigua_time())
        result["engine"]+="+meihua_yi"
    except: pass
    if feature=="all":
        if not hex_values:
            _js_load("iching-shifa-engine")
            result["iching_shifa_js"]=_js("iching-shifa-engine","JSON.stringify(IchingShifa.dayan())")
        result["engine"]+="+iching-shifa-engine"
    return result

