"""Route:  yijing"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 六爻梅花 =====
def _yijing(method="time",seed=None,year=None,month=None,day=None,feature="all"):
    result={"system":"yijing","engine":"","_hint":
        "ichingshifa(Iching类)已返回起卦+解卦。meihua_yi梅花全API+taixuanshifa太玄+jingjue荆诀。JS引擎对照(IchingShifa)。自探索:dir(ichingshifa)/dir(meihua_yi)/dir(Taixuan)/dir(jingjue)"}
    hex_values=None
    # 当method="now"且传了seed(毫秒时间戳)时, 用seed反算固定时间, 确保相同seed得到相同卦
    _seed_dt=None
    if seed is not None and not (year and month and day):
        import datetime as _dt
        try:
            _s=seed/1000 if seed>1e12 else seed
            _seed_dt=_dt.datetime.fromtimestamp(_s,_dt.timezone.utc).astimezone(_dt.timezone(_dt.timedelta(hours=8)))
        except: pass
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
            hex_data=i.qigua_manual(year or 2026,month or 1,day or 1,12,0,gua_str)
            result["ichingshifa"]=hex_data
            hex_values=gua_str
        elif method=="time" and all([year,month,day]):
            hex_data=i.qigua_time(year,month,day,12,0)
            result["ichingshifa"]=hex_data
            # Extract yao string from 大衍筮法 dict key
            try:
                _da=hex_data.get("大衍筮法")
                if isinstance(_da,(list,tuple)) and len(_da)>0:
                    hex_values=str(_da[0])
            except: pass
            # 日空時空 (called on i, not hex_data)
            try: result["daykong"]=i.daykong_shikong(year,month,day,12,0)
            except: pass
            # 先天策軌數
            try: result["innate_cegui"]=i.innate_cegui(year,month,day,12,0)
            except: pass
            # 後天策軌數
            try: result["acquired_cegui"]=i.acquired_cegui(year,month,day,12,0)
            except: pass
            # 六獸 + 十二運
            try:
                gz=i.gangzhi(year,month,day,12,0)
                if gz and len(gz)>2:
                    rg=gz[2][0] if gz[2] else "癸"
                    result["six_months_stars"]=i.find_six_mons(rg)
                    result["shier_luck"]=i.find_shier_luck(rg)
            except: pass
            # 本卦/之卦 from dict directly
            try:
                ben=hex_data.get("本卦",{})
                zhi=hex_data.get("之卦",{})
                result["decode_gua"]=ben
                if zhi:
                    result["decode_two_gua"]={"本卦":ben,"之卦":zhi}
            except: pass
            # 卦辞
            if hex_values:
                try: result["gua_description"]=i.show_sixtyfourguadescription(str(hex_values))
                except: pass
                try:
                    if len(str(hex_values))>=6:
                        hv=str(hex_values)
                        result["guaike"]=i.guaike(year,month,day,12,0,int(hv[:3]),int(hv[3:6]))
                except: pass
        elif method=="number":
            n=seed if seed is not None else 42
            import random as _rnd
            _rnd.seed(n)
            yao_str=i.bookgua()
            hex_data=i.qigua_manual(year or 2026,month or 1,day or 1,12,0,yao_str)
            result["ichingshifa"]=hex_data
            hex_values=yao_str
        else:
            if _seed_dt is not None:
                hex_data=i.qigua_time(_seed_dt.year,_seed_dt.month,_seed_dt.day,_seed_dt.hour,_seed_dt.minute)
            else:
                hex_data=i.qigua_now()
            result["ichingshifa"]=hex_data
        result["engine"]+="ichingshifa"
        if hex_values is None and isinstance(hex_data,dict):
            try:
                _da=hex_data.get("大衍筮法")
                if isinstance(_da,(list,tuple)) and len(_da)>0:
                    hex_values=str(_da[0])
            except: pass
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
        else:
            if _seed_dt is not None:
                tx = taixuanshifa.Taixuan(_seed_dt.year, _seed_dt.month, _seed_dt.day, _seed_dt.hour)
            else:
                import datetime as _dt
                _now = _dt.datetime.now()
                tx = taixuanshifa.Taixuan(_now.year, _now.month, _now.day, _now.hour)
            try: result["taixuan"] = tx.pan()
            except: pass
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
            _yr=_seed_dt.year if _seed_dt is not None else (year or 2026)
            _mo=_seed_dt.month if _seed_dt is not None else (month or 1)
            _dy=_seed_dt.day if _seed_dt is not None else (day or 1)
            result["iching_shifa_pan"]=json.loads(_js("iching-shifa-engine",
                "var r="+yao_js+";"
                "var pan=IchingShifa.decodePan(r,{year:"+str(_yr)+",month:"+str(_mo)+",day:"+str(_dy)+",hour:12});"
                "var gdyd=null;try{gdyd=IchingShifa.getGaoDaoYiDuan(r);}catch(e){}"
                "var qyxx=null;try{qyxx=IchingShifa.calculateQingyiXingXiu(r,"+str(_yr)+","+str(_mo)+","+str(_dy)+");}catch(e){}"
                "JSON.stringify({pan:pan,gaoDaoYiDuan:gdyd,qingyiXingXiu:qyxx})"))
        except: pass
        result["engine"]+="+iching-shifa-engine"
    return result
