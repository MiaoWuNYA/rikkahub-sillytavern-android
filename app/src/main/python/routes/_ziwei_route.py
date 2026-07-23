"""Route:  ziwei"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 紫微 =====
def _ziwei(year,month,day,hour,minute=0,gender="male",engine="iztro"):
    date_str=f"{year}-{month:02d}-{day}"
    hour_dec = hour + minute/60
    if isinstance(gender, int):
        gender = "male" if gender == 1 else "female"
    result={"system":"ziwei","engine":""}
    _engs=[]
    if engine in ("iztro","all"):
        _js_load("iztro-engine")
        # 手动提取字段，避免 JSON.stringify 遍历全对象触发深层 getter → fixIndex 爆栈
        try:
            result["iztro"]=json.loads(_js("iztro-engine",
                "var a=Iztro.astro.bySolar('%s',%d,'%s');"
                "JSON.stringify({"
                "solarDate:a.solarDate,lunarDate:a.lunarDate,"
                "chineseDate:a.chineseDate,rawDates:a.rawDates,"
                "sign:a.sign,zodiac:a.zodiac,"
                "fiveElementsClass:a.fiveElementsClass,soul:a.soul,body:a.body,"
                "gender:a.gender,time:a.time,timeRange:a.timeRange,"
                "earthlyBranchOfSoulPalace:a.earthlyBranchOfSoulPalace,"
                "earthlyBranchOfBodyPalace:a.earthlyBranchOfBodyPalace,"
                "palaces:a.palaces.map(function(p){return{"
                "index:p.index,name:p.name,"
                "isBodyPalace:p.isBodyPalace,isOriginalPalace:p.isOriginalPalace,"
                "heavenlyStem:p.heavenlyStem,earthlyBranch:p.earthlyBranch,"
                "majorStars:p.majorStars,minorStars:p.minorStars,adjectiveStars:p.adjectiveStars,"
                "changsheng12:p.changsheng12,boshi12:p.boshi12,"
                "jiangqian12:p.jiangqian12,suiqian12:p.suiqian12,"
                "decadal:p.decadal,ages:p.ages"
                "}})"
                "})" % (date_str, int(hour_dec), gender)))
            if isinstance(result.get("iztro"), dict) and 'error' not in result["iztro"]:
                _engs.append("iztro")
        except: pass
    if engine in ("nihai","all"):
        try:
            _js_load("ziwei-nihai")
            result["nihai"]=json.loads(_js("ziwei-nihai",f"JSON.stringify(ZiweiNihai.generateChart({{year:{year},month:{month},day:{day},hour:{hour_dec},gender:'{gender}'}}))"))
            if isinstance(result.get("nihai"), dict) and 'error' not in result["nihai"]:
                _engs.append("nihai")
        except: pass
    if engine in ("python","all"):
        try:
            sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            from ziwei_paipan import by_solar
            result["ziwei_paipan"]=by_solar(date_str,int(hour_dec),gender)
            _engs.append("ziwei_paipan")
        except Exception as e: result["ziwei_paipan_error"]=str(e)
    result["engine"]="+".join(_engs) if _engs else "none"
    result["_hint"]="Iztro全量已返回(手动提取避免爆栈)。ZiweiNihai含倪海夏天纪+古籍。自探索:Object.keys(Iztro.astro)/dir(ziwei_paipan)"
    return result
