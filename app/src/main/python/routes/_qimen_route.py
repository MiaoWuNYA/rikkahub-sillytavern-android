"""Route:  qimen"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,minute=0,feature="all"):
    result={"system":"qimen","engine":""}
    _engs=[]
    # Qimen Dunjia (年家+月家+日家+时家+时家流派)
    if feature in ("qimen","all"):
        try:
            _js_load("qimen-engine")
            # 年家
            ny=json.loads(_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'nianjia',year:{year}}}))"))
            if isinstance(ny, dict) and 'error' not in ny:
                ny["_chartType"]="年家奇门"
                ny["_description"]="以年干支定阴阳遁局数，管一年之运。仅作年度宏观参考，断具体事不用此盘。"
                result["qimen_nianjia"]=ny
                _engs.append("QimenEngine(年家)")
            # 月家
            ym=json.loads(_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'yuejia',year:{year},month:{month}}}))"))
            if isinstance(ym, dict) and 'error' not in ym:
                ym["_chartType"]="月家奇门"
                ym["_description"]="以月干支定局，管一月之运。用于月内趋势参考，断具体事不用此盘。"
                result["qimen_yuejia"]=ym
                _engs.append("QimenEngine(月家)")
            # 日家
            q=json.loads(_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'rijia',year:{year},month:{month},day:{day}}}))"))
            if isinstance(q, dict) and 'error' not in q:
                q["_chartType"]="日家奇门"
                q["_description"]="以日干支定局（拆补法），管一日之运。问一日吉凶可参考此盘。"
                result["qimen"]=q
                _engs.append("QimenEngine(日家)")
                if hour is not None:
                    # 时家 — 自研时家引擎 shiJiaGenerate (拆补法, 复用日家局数)
                    # 五鼠遁: 日上起时, branch=floor((hour+1)/2)%12
                    try:
                        qh=json.loads(_js("qimen-engine",
                            "var b=QimenEngine.generate({type:'rijia',year:%d,month:%d,day:%d});"
                            "var stems=['甲','乙','丙','丁','戊','己','庚','辛','壬','癸'];"
                            "var branches=['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥'];"
                            "var fp=JSON.parse(JSON.stringify(b.fourPillars));"
                            "var dg=fp.day.gan;"
                            "var bi=Math.floor((%d+1)/2)%%12;"
                            "var hi=(stems.indexOf(dg)%%5*2+bi)%%10;"
                            "var hg=stems[hi],hb=branches[bi];"
                            "fp.hour={gan:hg,zhi:hb};"
                            "var result=QimenEngine.shiJiaGenerate(hg,hb,b.juNumber,b.dun,fp,b.solarTerm);"
                            "result.juMethod='chaibu';"
                            "JSON.stringify(result)" % (year, month, day, hour)))
                        if isinstance(qh, dict) and 'error' not in qh:
                            qh["_chartType"]="时家奇门"
                            qh["_description"]="以时辰干支排九星八门八神（拆补法），奇门遁甲断事正用。问具体事情吉凶成败，用时家盘。此盘为四盘中唯一正式用于预测的盘。"
                            result["qimen_hourly"]=qh
                            if qh.get("fourPillars") and q.get("fourPillars"):
                                q["fourPillars"]["hour"]=qh["fourPillars"].get("hour")
                    except: pass
                    _engs[-1]="QimenEngine(日家+时家)"
                # 解读层: 格局+星门神详解+十干克应+运筹+长生+用神+值符克应+神将
                qa=json.loads(_js("qimen-engine",
                    "var b=QimenEngine.generate({type:'rijia',year:%d,month:%d,day:%d});"
                    "var fp=b.fourPillars||{};var yg=fp.year?fp.year.gan:null;var mg=fp.month?fp.month.gan:null;"
                    "var dg=fp.day?fp.day.gan:null;var dz=fp.day?fp.day.zhi:null;"
                    "var zsd=b.zhiShiDoor;var zfs=b.zhiFuStar;"
                    "JSON.stringify({"
                    "starDetail:['天蓬','天芮','天冲','天辅','天禽','天心','天柱','天任','天英'].map(function(s){return QimenEngine.getStarDetail(s)}),"
                    "doorDetail:['休门','生门','伤门','杜门','景门','死门','惊门','开门'].map(function(d){return QimenEngine.getDoorDetail(d)}),"
                    "godDetail:['值符','腾蛇','太阴','六合','白虎','玄武','九地','九天'].map(function(g){return QimenEngine.getGodDetail(g)}),"
                    "palaceDetail:[1,2,3,4,5,6,7,8,9].map(function(p){return QimenEngine.getPalaceDetail(p)}),"
                    "shenJiangDetail:['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥']"
                    "  .map(function(b){return QimenEngine.getShenJiangDetail(b)}),"
                    "allJiGe:typeof QimenEngine.getAllJiGe==='function'?QimenEngine.getAllJiGe():null,"
                    "allXiongGe:typeof QimenEngine.getAllXiongGe==='function'?QimenEngine.getAllXiongGe():null,"
                    "yongShen:{"
                    "  day:typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(dg):null,"
                    "  year:yg?typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(yg):null:null,"
                    "  month:mg?typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(mg):null:null"
                    "},"
                    "zhiFuKeYing:dz?typeof QimenEngine.getZhiFuKeYing==='function'?QimenEngine.getZhiFuKeYing(zfs,dz):null:null,"
                    "palaceAnalysis:b.palaces.map(function(p){"
                    "  return{"
                    "    palaceNumber:p.palaceNumber,"
                    "    specialPatterns:typeof QimenEngine.detectSpecialPatterns==='function'?"
                    "      QimenEngine.detectSpecialPatterns(p.palaceNumber,p.skyStem,p.earthStem,p.door,p.star,p.god,zsd,dg,yg,mg):null,"
                    "    palaceKeYing:typeof QimenEngine.getPalaceKeYing==='function'?"
                    "      QimenEngine.getPalaceKeYing(p.skyStem,p.earthStem,p.hiddenStems||[],p.jiGanStem):null,"
                    "    yunChouPatterns:typeof QimenEngine.getYunChouPatterns==='function'?"
                    "      QimenEngine.getYunChouPatterns(p.door,p.skyStem,p.earthStem,p.god,p.palaceNumber):null,"
                    "    palaceChangSheng:typeof QimenEngine.getPalaceChangSheng==='function'?"
                    "      QimenEngine.getPalaceChangSheng(p.palaceNumber,p.skyStem,p.earthStem,p.hiddenStems||[],p.jiGanStem):null,"
                    "    marks:p.marks,"
                    "    jiGanStem:p.jiGanStem,"
                    "    diGod:p.diGod,"
                    "    highlightStem:p.highlightStem"
                    "  }"
                    "})"
                    "})" % (year, month, day)))
                if isinstance(qa, dict) and 'error' not in qa:
                    result["qimen_analysis"]=qa
        except: pass
    # 大六壬
    if feature in ("liuren","all"):
        try:
            _js_load("liuren-engine")
            lr=json.loads(_js("liuren-engine",f"JSON.stringify(LiuRen.getLiuRenByDate(new Date({year},{month-1},{day},{hour or 12},{minute})))"))
            if isinstance(lr, dict) and 'error' not in lr:
                result["liuren"]=lr
                _engs.append("LiuRen")
        except: pass
    result["engine"]="+".join(_engs) if _engs else "none"
    result["_hint"]=("QimenEngine 四柱全: 年家(qimen_nianjia)+月家(qimen_yuejia)+日家(qimen)+时家(qimen_hourly,拆补法)。\n"
        "注意: 此入口一次返回4张盘，各盘用途不同，切勿混淆——\n"
        "  - qimen_nianjia (年家):  以年干支定局，管一年之运。仅作年度背景参考。\n"
        "  - qimen_yuejia (月家):   以月干支定局，管一月之运。月内趋势参考。\n"
        "  - qimen (日家):          以日干支定局，管一日之运。\n"
        "  - qimen_hourly (时家):   ✅ 以时辰干支排九星八门八神，奇门遁甲断事正用。问具体事情吉凶成败，只看此盘。\n"
        "四盘定局方式不同，值符值使八门九星八神均可能不一致，不可混用。\n"
        "大六壬与奇门遁甲共用一个数据入口(system='奇门')，通过feature='liuren'读取大六壬排盘。"
        "system='大六壬'或'六壬'等同 system='奇门'，返回数据含qimen+liuren双份。"
        "Qimen分析层(qimen_analysis):"
        "  格局全列表(allJiGe+allXiongGe), 用神万物类象(yongShen),"
        "  值符克应(zhiFuKeYing), 十二神将详解(shenJiangDetail),"
        "  宫位十干克应含暗干/寄宫(palaceKeYing),"
        "  十二长生含各天干在宫位所有地支(palaceChangSheng),"
        "  宫位标记/寄干/地八神/用神天干(per-palace)."
        "自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)")
    return result
