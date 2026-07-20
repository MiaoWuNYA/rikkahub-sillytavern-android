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
            result["ichingshifa"]=i.qigua_manual(year or 2026,month or 1,day or 1,12,0,gua_str)
        elif method=="time" and all([year,month,day]):
            hex_data=i.qigua_time(year,month,day,12,0)
            result["ichingshifa"]=hex_data
            try: result["daykong"]=hex_data.daykong_shikong()
            except: pass
            try: result["innate_cegui"]=hex_data.innate_cegui()
            except: pass
            try: result["acquired_cegui"]=hex_data.acquired_cegui()
            except: pass
            try:
                gz=getattr(hex_data,"time_dizhi",None) or getattr(hex_data,"ri_gan",None) or "癸"
                result["six_months_stars"]=hex_data.find_six_mons(gz)
            except: pass
            try:
                rg=getattr(hex_data,"ri_gan",None) or "癸"
                result["shier_luck"]=hex_data.find_shier_luck(rg)
            except: pass
            try: result["hutiangua"]=hex_data.hutiangua()
            except: pass
            try: result["bookgua_details"]=i.bookgua_details()
            except: pass
            try:
                hlines=getattr(hex_data,"lines",None) or getattr(hex_data,"values",None)
                if hlines:
                    result["decode_gua"]=i.decode_gua(str(hlines))
                    try: result["decode_two_gua"]=i.decode_two_gua(str(hlines),str(getattr(hex_data,"ggua_lines","") or ""))
                    except: pass
                    try:
                        gua_str_full=str(hlines)
                        result["guaike"]=i.guaike(year,month,day,12,0,int(gua_str_full[:3]),int(gua_str_full[3:6]))
                    except: pass
                    try: result["gua_description"]=i.show_sixtyfourguadescription(hlines)
                    except: pass
            except: pass
        elif method=="number":
            n=seed if seed is not None else 42
            hex_data=i.qigua_manual(2026,1,1,12,0,f"{n}")
            result["ichingshifa"]=hex_data
        else:
            hex_data=i.qigua_now()
            result["ichingshifa"]=hex_data
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
                result["meihua_tiyong"]={"original":hg[0],"mutual":hg[1],"changed":hg[2],"ti_yong":hg[3]} if len(hg)>=4 else hg
            except: pass
    except: pass
    # taixuanshifa / jingjue (如有)
    try:
        import taixuanshifa
        result["engine"]+="+taixuanshifa"
        if year and month and day:
            tx = taixuanshifa.Taixuan(year, month, day, 12)
            try: result["taixuan_pan"] = tx.pan()
            except: pass
            try: result["taixuan_qigua"] = tx.qigua_number()
            except: pass
            try: result["taixuan_dz"] = tx.getdz()
            except: pass
            try: result["taixuan_dz_date"] = tx.getdz_date()
            except: pass
        elif hasattr(taixuanshifa,'pan_from_code'):
            result["taixuan"]=taixuanshifa.pan_from_code(seed or "777777")
    except: pass
    try:
        import jingjue
        result["engine"]+="+jingjue"
        if hasattr(jingjue,'jie'):
            result["jingjue"]=jingjue.jie(seed or "777777")
    except: pass
    # JS双引擎 (feature="all"时全量 — 与Python同盘)
    if feature=="all":
        _js_load("iching-shifa-engine")
        if hex_values is not None:
            yao_js = json.dumps(str(hex_values))
        else:
            yao_js = "IchingShifa.dayan()"
        result["iching_shifa_js"]=_js("iching-shifa-engine","JSON.stringify("+yao_js+")")
        try:
            result["iching_shifa_pan"]=_js("iching-shifa-engine",
                "var r="+yao_js+";"
                "var pan=IchingShifa.decodePan(r,{year:"+str(year or 2026)+",month:"+str(month or 1)+",day:"+str(day or 1)+",hour:12});"
                "var gdyd=null;try{gdyd=IchingShifa.getGaoDaoYiDuan(pan.benGua.guaCode);}catch(e){}"
                "var qyxx=null;try{qyxx=IchingShifa.calculateQingyiXingXiu(r,"+str(year or 2026)+","+str(month or 1)+","+str(day or 1)+");}catch(e){}"
                "JSON.stringify({pan:pan,gaoDaoYiDuan:gdyd,qingyiXingXiu:qyxx})")
        except: pass
        result["engine"]+="+iching-shifa-engine"
    return result
