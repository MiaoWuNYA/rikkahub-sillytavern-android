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
                "var h=a.horoscope(new Date());"
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
                "decadal:p.decadal,ages:p.ages,"
                "surrounded:(function(){try{var s=a.surroundedPalaces(p.index);"
                "return{target:s.target.name,opposite:s.opposite.name,"
                "wealth:s.wealth.name,career:s.career.name}"
                "}catch(e){return null}})()"
                "}}),"
                "horoscope:{lunarDate:h.lunarDate,solarDate:h.solarDate,"
                "decadal:{index:h.decadal.index,name:h.decadal.name,"
                "heavenlyStem:h.decadal.heavenlyStem,earthlyBranch:h.decadal.earthlyBranch,"
                "palaceNames:h.decadal.palaceNames,mutagen:h.decadal.mutagen,stars:h.decadal.stars},"
                "age:{index:h.age.index,name:h.age.name,nominalAge:h.age.nominalAge,"
                "heavenlyStem:h.age.heavenlyStem,earthlyBranch:h.age.earthlyBranch,"
                "palaceNames:h.age.palaceNames,mutagen:h.age.mutagen},"
                "yearly:{index:h.yearly.index,name:h.yearly.name,"
                "heavenlyStem:h.yearly.heavenlyStem,earthlyBranch:h.yearly.earthlyBranch,"
                "palaceNames:h.yearly.palaceNames,mutagen:h.yearly.mutagen,"
                "stars:h.yearly.stars,yearlyDecStar:h.yearly.yearlyDecStar},"
                "monthly:{index:h.monthly.index,name:h.monthly.name,"
                "heavenlyStem:h.monthly.heavenlyStem,earthlyBranch:h.monthly.earthlyBranch,"
                "palaceNames:h.monthly.palaceNames,mutagen:h.monthly.mutagen,stars:h.monthly.stars},"
                "daily:{index:h.daily.index,name:h.daily.name,"
                "heavenlyStem:h.daily.heavenlyStem,earthlyBranch:h.daily.earthlyBranch,"
                "palaceNames:h.daily.palaceNames,mutagen:h.daily.mutagen,stars:h.daily.stars},"
                "hourly:{index:h.hourly.index,name:h.hourly.name,"
                "heavenlyStem:h.hourly.heavenlyStem,earthlyBranch:h.hourly.earthlyBranch,"
                "palaceNames:h.hourly.palaceNames,mutagen:h.hourly.mutagen,stars:h.hourly.stars}"
                "}"
                "})" % (date_str, ((hour+1)//2)%12, gender)))
            if isinstance(result.get("iztro"), dict) and 'error' not in result["iztro"]:
                _engs.append("iztro")
        except: pass
    if engine in ("nihai","all"):
        try:
            _js_load("ziwei-nihai")
            result["nihai"]=json.loads(_js("ziwei-nihai",f"JSON.stringify(ZiweiNihai.generateChart({{year:{year},month:{month},day:{day},hour:{((hour+1)//2)%12},gender:'{gender}'}}))"))
            if isinstance(result.get("nihai"), dict) and 'error' not in result["nihai"]:
                _engs.append("nihai")
        except: pass
    if engine in ("python","all"):
        try:
            sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            from ziwei_paipan import by_solar
            result["ziwei_paipan"]=by_solar(date_str,((hour+1)//2)%12,gender)
            _engs.append("ziwei_paipan")
        except Exception as e: result["ziwei_paipan_error"]=str(e)
    result["engine"]="+".join(_engs) if _engs else "none"
    result["_hint"]="Iztro全量已返回(手动提取避免爆栈)+三方四正+当前大限/流年/流月/流日/流时/小限运限。ZiweiNihai含倪海夏天纪+古籍。自探索:Object.keys(Iztro.astro)/dir(ziwei_paipan)"
    return result
