"""Route:  bazi"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 八字 =====
def _bazi(year, month, day, hour, gender=1):
    from lunar_python import Solar
    s = Solar.fromYmdHms(year, month, day, hour, 0, 0)
    l = s.getLunar()
    ec = l.getEightChar()
    yun = ec.getYun(gender)
    dayun_list = []
    for dy in yun.getDaYun():
        gz = dy.getGanZhi()
        if gz: dayun_list.append({"ganzhi":gz, "start_age":dy.getStartAge(), "end_age":dy.getEndAge(),
                                   "liunian":[ln.getGanZhi() for ln in dy.getLiuNian()]})
    result = {
        "system":"bazi","engine":"lunar_python",
        "four_pillars":{
            "year":{"gan":ec.getYearGan(),"zhi":ec.getYearZhi(),"ganzhi":ec.getYear(),"wuxing":ec.getYearWuXing(),"nayin":ec.getYearNaYin(),"xunkong":ec.getYearXunKong(),"hide_gan":ec.getYearHideGan(),"shishen":ec.getYearShiShenGan(),"dishi":ec.getYearDiShi()},
            "month":{"gan":ec.getMonthGan(),"zhi":ec.getMonthZhi(),"ganzhi":ec.getMonth(),"wuxing":ec.getMonthWuXing(),"nayin":ec.getMonthNaYin(),"xunkong":ec.getMonthXunKong(),"hide_gan":ec.getMonthHideGan(),"shishen":ec.getMonthShiShenGan()},
            "day":{"gan":ec.getDayGan(),"zhi":ec.getDayZhi(),"ganzhi":ec.getDay(),"wuxing":ec.getDayWuXing(),"nayin":ec.getDayNaYin(),"xunkong":ec.getDayXunKong(),"hide_gan":ec.getDayHideGan(),"shishen":ec.getDayShiShenGan(),"dishi":ec.getDayDiShi()},
            "time":{"gan":ec.getTimeGan(),"zhi":ec.getTimeZhi(),"ganzhi":ec.getTime(),"wuxing":ec.getTimeWuXing(),"nayin":ec.getTimeNaYin(),"xunkong":ec.getTimeXunKong(),"hide_gan":ec.getTimeHideGan(),"shishen":ec.getTimeShiShenGan(),"dishi":ec.getTimeDiShi()},
        },
        "dayun":dayun_list,"start_age":yun.getStartYear(),"gender":gender,
        "solar":s.toFullString(),"lunar":l.toFullString(),
        "jieqi":{k:str(v) for k,v in (l.getJieQiTable() or {}).items()},
    }
    try:
        sys.path.insert(0, os.path.dirname(__file__))
        from bazi_china import datas, shengxiao, sizi, luohou, ganzhi
        yg,yz=ec.getYearGan(),ec.getYearZhi(); dg,dz=ec.getDayGan(),ec.getDayZhi()
        tg,tz=ec.getTimeGan(),ec.getTimeZhi(); mg=ec.getMonthGan(); mz=ec.getMonthZhi()
        result["extra"] = {
            "nayin":{"year":datas.nayins.get((yg,yz),""),"day":datas.nayins.get((dg,dz),""),"time":datas.nayins.get((tg,tz),"")},
            "rizhu":datas.rizhus.get(dg+dz,""),
            "minggong":datas.minggongs.get(ec.getMingGong()[-1:],""),
            "shengong":datas.minggongs.get(ec.getShenGong()[-1:],""),
            "day_shen":{k:v.get(dz,"") for k,v in datas.day_shens.items()},
            "g_shen":{k:v.get(dg,"") for k,v in datas.g_shens.items()},
            "sizi":{k: v for k,v in list(sizi.summarys.items())[:5]},
            "ganzhi_gan":ganzhi.Gan[:10], "ganzhi_zhi":ganzhi.Zhi[:12],
        }
        result["engine"] += " + bazi_china"
        result["_hint"] = "lunar_python全字段已返回。bazi_china另有: ganzi干支/luohou飞星/shengxiao生肖/sizi古诀/yue月令/cnlunar黄历。自探索: dir(datas)"
    except Exception:
        result["_hint"] = "lunar_python已返回排盘+大运。bazi_china不可用(仅APK内)"
    return result
