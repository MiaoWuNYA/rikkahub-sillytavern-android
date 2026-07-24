"""Route:  qimen"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,minute=0,feature="all"):
    result={"system":"qimen","engine":""}
    _engs=[]
    # Qimen Dunjia (日家+时家)
    if feature in ("qimen","all"):
        try:
            _js_load("qimen-engine")
            q=json.loads(_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'rijia',year:{year},month:{month},day:{day}}}))"))
            if isinstance(q, dict) and 'error' not in q:
                result["qimen"]=q
                _engs.append("QimenEngine(日家)")
                if hour is not None:
                    qh=json.loads(_js("qimen-engine",f"var b=QimenEngine.generate({{type:'rijia',year:{year},month:{month},day:{day}}});JSON.stringify(QimenEngine.generate({{type:'shijia',year:{year},month:{month},day:{day},hour:{hour},minute:{minute},baseChart:b}}))"))
                    if isinstance(qh, dict) and 'error' not in qh:
                        result["qimen_hourly"]=qh
                        # 合并时柱到日家fourPillars
                        if qh.get("fourPillars") and q.get("fourPillars"):
                            q["fourPillars"]["hour"]=qh["fourPillars"].get("hour")
                        _engs[-1]="QimenEngine(日家+时家)"
                else:
                    _engs[-1]="QimenEngine(日家)"
                # 解读层: 格局+星门神详解+十干克应+运筹+长生
                qa=json.loads(_js("qimen-engine",
                    "var b=QimenEngine.generate({type:'rijia',year:%d,month:%d,day:%d});"
                    "var fp=b.fourPillars||{};var yg=fp.year?fp.year.gan:null;var mg=fp.month?fp.month.gan:null;var dg=fp.day?fp.day.gan:null;"
                    "var zsd=b.zhiShiDoor;"
                    "JSON.stringify({"
                    "starDetail:['天蓬','天芮','天冲','天辅','天禽','天心','天柱','天任','天英'].map(function(s){return QimenEngine.getStarDetail(s)}),"
                    "doorDetail:['休','生','伤','杜','景','死','惊','开'].map(function(d){return QimenEngine.getDoorDetail(d)}),"
                    "godDetail:['值符','螣蛇','太阴','六合','白虎','玄武','九地','九天'].map(function(g){return QimenEngine.getGodDetail(g)}),"
                    "palaceDetail:[1,2,3,4,5,6,7,8,9].map(function(p){return QimenEngine.getPalaceDetail(p)}),"
                    "palaceAnalysis:b.palaces.map(function(p){return{"
                    "  palaceNumber:p.palaceNumber,"
                    "  specialPatterns:typeof QimenEngine.detectSpecialPatterns==='function'?"
                    "    QimenEngine.detectSpecialPatterns(p.palaceNumber,p.skyStem,p.earthStem,p.door,p.star,p.god,zsd,dg,yg,mg):null,"
                    "  shiGanKeYing:typeof QimenEngine.getShiGanKeYing==='function'?"
                    "    QimenEngine.getShiGanKeYing(p.skyStem,p.earthStem):null,"
                    "  yunChouPatterns:typeof QimenEngine.getYunChouPatterns==='function'?"
                    "    QimenEngine.getYunChouPatterns(p.door,p.skyStem,p.earthStem,p.god,p.palaceNumber):null,"
                    "  changSheng:typeof QimenEngine.getChangSheng==='function'?"
                    "    QimenEngine.getChangSheng(p.skyStem,p.earthStem):null"
                    "}})"
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
    result["_hint"]=("QimenEngine日家/时家已返回。LiuRen一键排盘含课体+三传+神将+22原子函数。"
        "QimenEngine另有阴遁/阳遁/置闰/拆补4流派。自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)")
    return result