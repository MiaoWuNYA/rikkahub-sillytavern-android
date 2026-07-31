"""Route: stellium — component-based deep traditional/digital astrology.
┌──────────────────────────────────────────────────────────────────────────┐
│ 维护指南 / Maintenance Guide — 2025-07                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. 架构总览 (Architecture)                                              │
│     ┌──────────┐   ┌──────────────────┐   ┌──────────────────┐          │
│     │ Kotlin   │ → │ mingli_router.py │ → │ _stellium_route  │          │
│     │Tool desc │   │ 别名→_stellium   │   │ (本文件,纯Python)│          │
│     └──────────┘   └──────────────────┘   └────────┬─────────┘          │
│                                                     ↓                   │
│                                            stellium 引擎(pip whl)       │
│                                                                          │
│  2. 触发方式 / 参数映射                                                  │
│     Kotlin MingliTool.kt 的 params → _stellium() 的形参 (同名直通)      │
│     所以：在 Kotlin 侧加了一个 params 字段，就必须在此函数形参同步添加  │
│     反之亦然。两者必须保持同步，否则传参出错。                          │
│                                                                          │
│  3. 模块执行策略 (Execution Strategy)                                    │
│     ┌─────────────────────────────────────────────────────────────┐     │
│     │ 始终执行(always): chart_shape, ruler, dispositors, VOC,     │     │
│     │ moon_phase, draconic, profections, firdaria, ZR, hyleg/     │     │
│     │ LoL, planetary_hour, almuten                                │     │
│     ├─────────────────────────────────────────────────────────────┤     │
│     │ 条件执行(conditional):                                       │     │
│     │   partner_year     → synastry + composite + davison          │     │
│     │   transit_date     → transit chart + cross-aspects          │     │
│     │   transit_forecast → ingresses + stations                   │     │
│     │   return_year      → solar/lunar/saturn/jupiter return      │     │
│     │   progression_*    → progression + arc_direction + primary  │     │
│     │   crossings_*      → planetary crossings                    │     │
│     └─────────────────────────────────────────────────────────────┘     │
│                                                                          │
│  4. 编码规则 (Coding Rules)                                              │
│     每个模块必须包在 try/except 中，错误写入 result["xxx_error"]        │
│     绝不允许让一个模块的异常级联到整个函数崩溃。                        │
│     本路由 100% Python，不依赖任何 JS bridge。不存在 double-encoding。 │
│     所有 stellium 的 import 都用本地导入（在函数内部 import），         │
│     因为 Android Chaquopy 环境中 stellium whl 的导入路径可能延迟加载。  │
│                                                                          │
│  5. 如何添加新模块 (Adding a New Module)                                │
│     ① 在 _stellium() 函数体末尾添加新 block                             │
│     ② 如果是条件触发，用 if xxx is not None: 包裹                      │
│     ③ 用 try/except 包裹内部逻辑                                       │
│     ④ result["your_key"] = ...                                         │
│     ⑤ 如果新增了参数:                                                   │
│        a) 在 _stellium() 形参列表中添加                                  │
│        b) 在 Kotlin MingliTool.kt 的 MingliParameters 中添加同名参数    │
│        c) 在 深度古典占星.txt guide 中添加说明                           │
│                                                                          │
│  6. 关键文件依赖 (File Dependencies)                                     │
│     Kotlin 工具定义: MingliTool.kt (params 必须与路由形参同步)          │
│     Kotlin 路由解析: MingliRouter.kt (不需改动,已泛化)                 │
│     Python 路由分发: mingli_router.py (添加别名映射)                    │
│     Guide模板: assets/mingli/深度古典占星.txt (描述给AI的调用方式)      │
│     stellium whl: offline_pkgs/stellium-0.22.0-py3-none-any.whl         │
│                                                                          │
│  7. TZ 说明                                                              │
│     必须使用 IANA 时区字符串(如"Asia/Shanghai","America/New_York")       │
│     不接收 offset 小时(如 8.0) — 与 stellium 的 pytz 依赖一致           │
│     fallback: UTC                                                        │
│                                                                          │
│  8. stellium API 参考                                                    │
│     ChartBuilder:   stellium/core/builder.py                             │
│     CalculatedChart: stellium/core/models.py                             │
│     MultiChartBuilder: stellium/core/multichart.py                       │
│     ReturnBuilder:  stellium/returns/builder.py                          │
│     SynthesisBuilder: stellium/core/synthesis.py                         │
│     DirectionsEngine: stellium/engines/directions.py                     │
│     Components:     stellium/components/__init__.py                      │
│     Utils:          stellium/utils/                                      │
│                                                                          │
│  9. 日志 / 调试 (Debugging)                                              │
│     正常: _ensure_ephe() 自动寻找 ephe 路径                              │
│     若缺少星历表: 确认 app/offline_pkgs/ 下有 ephe/ 目录                │
│     或者在 Chaquopy pip block 中安装 swisseph 时包含 ephe 文件          │
│     测试: 用 router_test.py 中 mock 数据调用 _stellium() 看返回         │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘

TZ: IANA strings (e.g. "Asia/Shanghai"), NOT offset hours. Falls back to UTC.
"""

import os
from datetime import datetime, timedelta
from dataclasses import asdict

import pytz

# ── Mock svgwrite — stellium's visualization modules import it at module level,
#     but we never call any chart drawing functions. An empty mock avoids the
#     "No module named 'svgwrite'" crash without installing the actual package. ──
import sys, types
_svgwrite = types.ModuleType('svgwrite')
class _Mock:
    def __call__(self, *a, **kw): return _Mock()
    def __getattr__(self, n): return _Mock()
    def __enter__(self): return self
    def __exit__(self, *a): pass
_svgwrite.Drawing = _Mock()
_svgwrite.container = types.ModuleType('svgwrite.container')
_svgwrite.container.Group = _Mock()
sys.modules['svgwrite'] = _svgwrite
sys.modules['svgwrite.container'] = _svgwrite.container
del sys, types

_EPHE_SET = False


def _ensure_ephe():
    global _EPHE_SET
    if _EPHE_SET:
        return
    import swisseph as swe

    found = []
    for p in [os.path.join(os.path.dirname(swe.__file__), "ephe")]:
        if os.path.isdir(p) and any(f.endswith(".se1") for f in os.listdir(p)):
            found.append(p)
    if not found:
        import site
        for sp in site.getsitepackages():
            p = os.path.join(sp, "swisseph/ephe")
            if os.path.isdir(p) and any(f.endswith(".se1") for f in os.listdir(p)):
                found.append(p)
                break
    if found:
        swe.set_ephe_path(found[0])
        os.environ.setdefault("SE_EPHE_PATH", found[0])
        os.environ.setdefault("STELLIUM_EPHE_PATH", found[0])
    _EPHE_SET = True


def _build_dt(year, month, day, hour, minute, tz_name):
    """Build ChartDateTime from raw inputs, bypassing geopy/timezonefinder."""
    from stellium.core.models import ChartDateTime
    from stellium.utils.time import datetime_to_julian_day

    local = datetime(year, month, day, hour, minute)
    if tz_name:
        tz = pytz.timezone(tz_name)
        aware = tz.localize(local)
        utc = aware.astimezone(pytz.UTC)
    else:
        utc = local.replace(tzinfo=pytz.UTC)
        aware = utc
    jd = datetime_to_julian_day(utc)
    return ChartDateTime(utc, jd, aware)


def _chart(chart_dt, lat, lon, tz_name, house_system):
    """Build a CalculatedChart via the component-based builder pattern."""
    from stellium.core.builder import ChartBuilder
    from stellium.core.models import ChartLocation
    from stellium.engines.houses import (
        PlacidusHouses, WholeSignHouses, EqualHouses,
        RegiomontanusHouses, KochHouses, PorphyryHouses, CampanusHouses,
    )

    HS = {
        "placidus": [PlacidusHouses()], "whole_sign": [WholeSignHouses()],
        "whole": [WholeSignHouses()], "equal": [EqualHouses()],
        "regiomontanus": [RegiomontanusHouses()], "koch": [KochHouses()],
        "porphyry": [PorphyryHouses()], "campanus": [CampanusHouses()],
    }
    loc = ChartLocation(lat, lon, name="", timezone=tz_name or "UTC")
    builder = (
        ChartBuilder(chart_dt, loc)
        .with_aspects()
        .with_declination_aspects(orb=1.0)
        .with_house_systems(HS.get(house_system, [PlacidusHouses()]))
    )
    from stellium.components import (
        AntisciaCalculator, ArabicPartsCalculator,
        AccidentalDignityComponent, DignityComponent,
        FixedStarsComponent, MidpointCalculator,
    )
    from stellium.engines.patterns import AspectPatternAnalyzer
    builder = (
        builder.add_component(DignityComponent())
        .add_component(ArabicPartsCalculator())
        .add_component(FixedStarsComponent())
        .add_component(MidpointCalculator())
        .add_component(AccidentalDignityComponent())
        .add_component(AntisciaCalculator())
        .add_analyzer(AspectPatternAnalyzer())
    )
    return builder.calculate()


def _stellium(
    year, month, day, hour, minute=0, tz=None,
    lat=0.0, lon=0.0, house_system="placidus",
    partner_year=None, partner_month=None, partner_day=None,
    partner_hour=None, partner_minute=None, partner_tz=None,
    partner_lat=None, partner_lon=None,
    transit_date=None, transit_hour=None,
    return_year=None, progression_age=None, progression_date=None,
    transit_forecast_months=None, crossings_start=None, crossings_end=None,
):
    _ensure_ephe()

    # ── 1. Base chart (always) ──
    chart_dt = _build_dt(year, month, day, hour, minute, tz)
    chart = _chart(chart_dt, lat, lon, tz, house_system)
    result = chart.to_dict()
    result["system"] = "stellium"
    result["prompt_text"] = chart.to_prompt_text()
    result["house_system"] = house_system

    # Current age for timing calculations
    try:
        # 用 chart.datetime.utc_datetime（真正的出生 UTC 时刻）算年龄，
        # 避免把本地时间误当 UTC（时区偏差在生日前后会令 profection 岁数差 1）
        current_age = (
            datetime.now(pytz.UTC) - chart.datetime.utc_datetime
        ).total_seconds() / 31557600
    except Exception:
        current_age = 30.0

    # ── 2. Chart shape + ruler (always) ──
    try:
        from stellium.utils.chart_shape import detect_chart_shape, get_chart_shape_description
        shape, meta = detect_chart_shape(chart)
        result["chart_shape"] = {
            "shape": shape,
            "description": get_chart_shape_description(shape, meta),
            "metadata": meta,
        }
    except Exception as e:
        result["chart_shape_error"] = str(e)

    try:
        from stellium.utils.chart_ruler import get_chart_ruler_from_chart
        result["chart_ruler"] = get_chart_ruler_from_chart(chart)
    except Exception as e:
        result["chart_ruler_error"] = str(e)

    # ── 3. Dispositor chains + mutual receptions (always) ──
    try:
        from stellium.engines.dispositors import DispositorEngine
        de = DispositorEngine(chart)
        result["dispositors"] = {
            "planetary": de.planetary().to_dict(),
            "house": de.house_based().to_dict(),
        }
    except Exception as e:
        result["dispositors_error"] = str(e)

    # ── 4. VOC Moon (always) ──
    try:
        voc = chart.voc_moon()
        result["voc_moon"] = {
            "is_void": voc.is_void,
            "moon_sign": voc.moon_sign,
            "void_until": voc.void_until.isoformat(),
            "ends_by": voc.ends_by,
            "next_aspect": voc.next_aspect,
            "next_planet": voc.next_planet,
            "next_sign": voc.next_sign,
            "ingress_time": voc.ingress_time.isoformat(),
        }
    except Exception as e:
        result["voc_moon_error"] = str(e)

    # ── 5. Moon phase (always) ──
    try:
        moon = chart.get_object("Moon")
        if moon and moon.phase:
            result["moon_phase"] = {
                "phase_name": moon.phase.phase_name,
                "illuminated_fraction": moon.phase.illuminated_fraction,
                "phase_angle": moon.phase.phase_angle,
                "is_waxing": moon.phase.is_waxing,
            }
    except Exception as e:
        result["moon_phase_error"] = str(e)

    # ── 5b. Planetary phases (always, classical morning/evening star) ──
    # 只保留古典七星中的水金火木土：太阳相位无信息量，月亮另有 moon_phase；
    # 三王星/凯龙/交点不属于古典晨昏星体系。
    try:
        phases = []
        sun_obj = chart.get_object("Sun")
        sun_lon = sun_obj.longitude if sun_obj else None
        for p in chart.positions:
            if p.phase is not None and p.name in ("Mercury", "Venus", "Mars", "Jupiter", "Saturn"):
                item = {
                    "name": p.name,
                    "phase_angle": p.phase.phase_angle,
                    "illuminated_fraction": p.phase.illuminated_fraction,
                    "elongation": p.phase.elongation,
                    "apparent_magnitude": p.phase.apparent_magnitude,
                }
                # 晨星/昏星由黄经差直接推导（对照 swisseph 黄经，纯几何无假设）：
                # diff<180°=行星在太阳东侧（日落前后可见=昏星）；diff>180°=西侧（日出前可见=晨星）
                if sun_lon is not None:
                    diff = (p.longitude - sun_lon) % 360
                    item["side_of_sun"] = "east" if diff < 180 else "west"
                    item["zodiacal_elongation"] = round(diff, 4)
                phases.append(item)
        if phases:
            result["planetary_phases"] = phases
    except Exception as e:
        result["planetary_phases_error"] = str(e)

    # ── 6. Draconic chart (always) ──
    try:
        result["draconic"] = chart.draconic().to_dict()
    except Exception as e:
        result["draconic_error"] = str(e)

    # ── 7. Timing: profections + firdaria + ZR (always) ──
    # ── 7a. Profections ──
    try:
        from stellium.engines.profections import ProfectionEngine
        pe = ProfectionEngine(chart)
        result["profection"] = {"annual": asdict(pe.annual(int(current_age)))}
        try:
            mp = pe.multi(int(current_age))
            result["profection"]["multi"] = {
                "age": mp.age,
                "lords": mp.lords,
                "profected_signs": {k: v.profected_sign for k, v in mp.results.items()},
            }
        except Exception:
            pass
    except Exception as e:
        result["profection_error"] = str(e)

    # ── 7b. Firdaria ──
    try:
        ft = chart.firdaria()
        # FirdariaTimeline has .at(datetime) not .at_age()
        fp = ft.at(ft.birth + timedelta(days=current_age * 365.25))
        result["firdaria"] = {
            "preset": ft.preset, "sect": ft.sect,
            "current": asdict(fp) if fp else None,
        }
        # 未来若干期（主期+副期按时间混排），供中短期趋势判断
        try:
            now = datetime.now(pytz.UTC)
            upcoming = []
            for p in sorted(ft.periods, key=lambda x: x.start):
                if p.start > now and len(upcoming) < 6:
                    upcoming.append({
                        "level": p.level,
                        "ruler": p.ruler,
                        "sub_ruler": p.sub_ruler,
                        "start_age": round(p.start_age, 2),
                        "end_age": round(p.end_age, 2),
                    })
            result["firdaria"]["upcoming"] = upcoming
        except Exception:
            pass
    except Exception as e:
        result["firdaria_error"] = str(e)

    # ── 7c. Zodiacal Releasing ──
    try:
        from stellium.engines.releasing import ZodiacalReleasingEngine
        zr = ZodiacalReleasingEngine(chart).build_timeline()
        snap = zr.at_age(current_age)
        result["zodiacal_releasing"] = {
            "lot": snap.lot, "lot_sign": snap.lot_sign,
            "age": snap.age, "date": snap.date.isoformat(),
        }
        for lvl in range(1, 5):
            p = getattr(snap, f"l{lvl}")
            if p:
                result["zodiacal_releasing"][f"l{lvl}"] = asdict(p)
        # 未来峰值与脱绑节点（Valens 时间技术的中短期拐点）
        try:
            def _future_periods(pred):
                out = []
                for lvl in range(1, 5):
                    for p in zr.periods.get(lvl, []):
                        if p.end > snap.date and pred(p):
                            out.append({
                                "level": p.level,
                                "sign": p.sign,
                                "ruler": p.ruler,
                                "start": p.start.date().isoformat(),
                                "end": p.end.date().isoformat(),
                                "length_days": p.length_days,
                            })
                return out
            peaks = sorted(
                _future_periods(lambda p: p.is_peak),
                key=lambda x: x["start"],
            )[:3]
            lbs = sorted(
                _future_periods(lambda p: p.is_loosing_bond),
                key=lambda x: x["start"],
            )[:3]
            if peaks:
                result["zodiacal_releasing"]["upcoming_peaks"] = peaks
            if lbs:
                result["zodiacal_releasing"]["upcoming_loosing_bonds"] = lbs
        except Exception:
            pass
    except Exception as e:
        result["zr_error"] = str(e)

    # ── 8. Hyleg / Length of Life (always) ──
    try:
        result["hyleg"] = asdict(chart.hyleg())
    except Exception as e:
        result["hyleg_error"] = str(e)
    try:
        lol = chart.length_of_life()
        result["length_of_life"] = {
            "hyleg": asdict(lol.hyleg),
            "alcocoden": lol.alcocoden,
            "alcocoden_angularity": lol.alcocoden_angularity,
            "base_years": lol.base_years,
            "base_family": lol.base_family,
            "modifiers": [asdict(m) for m in lol.modifiers],
            "total": lol.total,
            "unit": lol.unit,
            "method": lol.method,
        }
    except Exception as e:
        result["lol_error"] = str(e)

    # ── 10. Planetary Hour of Birth (always) ──
    try:
        from stellium.electional.planetary_hours import get_planetary_hour
        ph_dt = chart.datetime.utc_datetime
        ph = get_planetary_hour(ph_dt, chart.location.latitude, chart.location.longitude)
        result["planetary_hour"] = {
            "ruler": ph.ruler,
            "hour_number": ph.hour_number,
            "is_day_hour": ph.is_day_hour,
            "start_utc": ph.start_utc.isoformat(),
            "end_utc": ph.end_utc.isoformat(),
        }
    except Exception as e:
        result["planetary_hour_error"] = str(e)

    # ── 11. Almuten / Strongest Planet (always) ──
    try:
        from stellium.engines.almuten import almuten_of_degree
        sun_obj = chart.get_object("Sun")
        sun_house = chart.get_house("Sun") if sun_obj is not None else None
        sect = "night" if (sun_house is not None and 1 <= sun_house <= 6) else "day"
        almuten_data = {}
        asc = chart.get_object("ASC")
        if asc:
            ar = almuten_of_degree(asc.longitude, sect)
            almuten_data["asc_almuten"] = {
                "winner": ar.winner,
                "scores": ar.scores,
                "tie": list(ar.tie),
            }
        strongest = chart.get_strongest_planet()
        if strongest:
            almuten_data["strongest_planet"] = {
                "planet": strongest[0],
                "score": strongest[1],
            }
        if almuten_data:
            result["almuten"] = almuten_data
    except Exception as e:
        result["almuten_error"] = str(e)

    # ── 12. Synastry + Composite + Davison (partner_year 驱动) ──
    if partner_year is not None:
        try:
            p_dt = _build_dt(
                partner_year, partner_month or 1, partner_day or 1,
                partner_hour or 12, partner_minute or 0, partner_tz or tz,
            )
            partner = _chart(
                p_dt, partner_lat or lat, partner_lon or lon,
                partner_tz or tz, house_system,
            )
            from stellium.core.multichart import MultiChartBuilder
            mc = (
                MultiChartBuilder.synastry(chart, partner)
                .with_cross_aspects().with_house_overlays().calculate()
            )
            result["synastry"] = mc.to_dict()
            result["synastry_text"] = mc.to_prompt_text()

            from stellium.core.synthesis import SynthesisBuilder
            result["composite"] = (
                SynthesisBuilder.composite(chart, partner).calculate().to_dict()
            )
            result["davison"] = (
                SynthesisBuilder.davison(chart, partner).calculate().to_dict()
            )
        except Exception as e:
            result["synastry_error"] = str(e)

    # ── 13. Transit (transit_date 驱动) ──
    if transit_date is not None:
        try:
            td = datetime.strptime(transit_date, "%Y-%m-%d")
            t_dt = _build_dt(td.year, td.month, td.day, transit_hour or 12, 0, "UTC")
            t_chart = _chart(t_dt, lat, lon, "UTC", house_system)
            from stellium.core.multichart import MultiChartBuilder
            mc = MultiChartBuilder.transit(chart, t_chart).with_cross_aspects().calculate()
            result["transit"] = {"chart": t_chart.to_dict(), "cross_aspects": mc.to_dict()}
            result["transit_text"] = mc.to_prompt_text()
        except Exception as e:
            result["transit_error"] = str(e)

    # ── Transit Forecast (transit_forecast_months 驱动) ──
    if transit_forecast_months is not None:
        try:
            from stellium.engines.search import find_all_sign_changes, find_all_stations
            from stellium.utils.time import julian_day_to_datetime
            now = datetime.now(pytz.UTC)
            end = now + timedelta(days=int(transit_forecast_months) * 30)
            forecast = {"outer_planet_ingresses": [], "outer_planet_stations": []}
            outer_planets = ["Jupiter", "Saturn", "Uranus", "Neptune", "Pluto"]
            for p in outer_planets:
                try:
                    ingresses = find_all_sign_changes(p, now, end)
                    for ing in ingresses:
                        forecast["outer_planet_ingresses"].append({
                            "planet": p, "sign": ing.sign,
                            "date": julian_day_to_datetime(ing.julian_day).isoformat(),
                        })
                except Exception:
                    pass
                try:
                    stations = find_all_stations(p, now, end)
                    for st in stations:
                        forecast["outer_planet_stations"].append({
                            "planet": p, "type": st.station_type,
                            "date": julian_day_to_datetime(st.julian_day).isoformat(),
                        })
                except Exception:
                    pass
            result["transit_forecast"] = forecast
        except Exception as e:
            result["transit_forecast_error"] = str(e)

    # ── 14. Returns (return_year 驱动, 算 solar/lunar/planetary) ──
    if return_year is not None:
        from stellium.returns import ReturnBuilder
        for label, cls_name in [
            ("solar", "Sun"), ("lunar", "Moon"),
            ("saturn", "Saturn"), ("jupiter", "Jupiter"),
        ]:
            try:
                if cls_name in ("Sun",):
                    rc = ReturnBuilder.solar(chart, return_year).calculate()
                elif cls_name in ("Moon",):
                    rc = ReturnBuilder.lunar(
                        chart, near_date=f"{return_year}-{month:02d}-{day:02d}"
                    ).calculate()
                else:
                    rc = ReturnBuilder.planetary(
                        chart, cls_name, near_date=f"{return_year}-{month:02d}-{day:02d}"
                    ).calculate()
                result[f"{label}_return"] = rc.to_dict()
                result[f"{label}_return_text"] = rc.to_prompt_text()
            except Exception as e:
                result[f"{label}_return_error"] = str(e)

    # ── 15. Progression (progression_age 或 progression_date 驱动) ──
    if progression_age is not None or progression_date is not None:
        try:
            from stellium.core.multichart import MultiChartBuilder
            kwargs = {}
            if progression_age is not None:
                kwargs["age"] = progression_age
            if progression_date is not None:
                kwargs["target_date"] = progression_date
            mc = MultiChartBuilder.progression(chart, **kwargs).calculate()
            result["progression"] = mc.to_dict()
            result["progression_text"] = mc.to_prompt_text()
        except Exception as e:
            result["progression_error"] = str(e)

    # ── 16. Arc Direction (progression_age 或 progression_date 驱动) ──
    if progression_age is not None or progression_date is not None:
        try:
            from stellium.core.multichart import MultiChartBuilder
            kwargs = {}
            if progression_age is not None:
                kwargs["age"] = progression_age
            if progression_date is not None:
                kwargs["target_date"] = progression_date
            mc = MultiChartBuilder.arc_direction(chart, **kwargs).calculate()
            result["arc_direction"] = mc.to_dict()
        except Exception as e:
            result["arc_direction_error"] = str(e)

    # ── 17. Primary Directions (progression_age 或 progression_date 驱动) ──
    if progression_age is not None or progression_date is not None:
        try:
            from stellium.engines.directions import DirectionsEngine
            de = DirectionsEngine(chart, method="zodiacal", time_key="naibod")
            primary = {"all": {}}
            # Direct each planet to angles
            for planet_name in ["Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn"]:
                try:
                    angles = de.direct_to_angles(planet_name)
                    primary["all"][planet_name] = {
                        angle: {
                            "arc_degrees": r.arc.arc_degrees,
                            "age": r.age, "date": r.date.isoformat(),
                            "method": r.arc.method, "direction": r.arc.direction,
                        }
                        for angle, r in angles.items() if r
                    }
                except Exception:
                    pass
            # Direct traditional planets to ASC + MC
            for sig_name in ["ASC", "MC"]:
                primary[f"to_{sig_name}"] = {}
                for planet_name in ["Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn"]:
                    try:
                        r = de.direct(planet_name, sig_name)
                        primary[f"to_{sig_name}"][planet_name] = {
                            "arc_degrees": r.arc.arc_degrees,
                            "age": r.age, "date": r.date.isoformat(),
                            "method": r.arc.method, "direction": r.arc.direction,
                        }
                    except Exception:
                        pass
            if primary["all"] or primary.get("to_ASC") or primary.get("to_MC"):
                result["primary_directions"] = primary
        except Exception as e:
            result["primary_directions_error"] = str(e)

    # ── 18. Planetary Crossings (crossings_start + crossings_end 驱动) ──
    if crossings_start is not None and crossings_end is not None:
        try:
            from stellium.utils.planetary_crossing import find_planetary_crossing
            from stellium.utils.time import datetime_to_julian_day, julian_day_to_datetime
            cs = datetime.strptime(crossings_start, "%Y-%m-%d")
            ce = datetime.strptime(crossings_end, "%Y-%m-%d")
            start_jd = datetime_to_julian_day(cs)
            end_jd = datetime_to_julian_day(ce)
            crossings = {"planets": []}
            for p_name in ["Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn"]:
                natal_obj = chart.get_object(p_name)
                if natal_obj is None:
                    continue
                try:
                    x_jd = find_planetary_crossing(p_name, natal_obj.longitude, start_jd, direction=1)
                    if x_jd <= end_jd:
                        x_dt = julian_day_to_datetime(x_jd)
                        crossings["planets"].append({
                            "transiting": p_name,
                            "natal_longitude": natal_obj.longitude,
                            "date": x_dt.isoformat(),
                        })
                except Exception:
                    pass
            if crossings["planets"]:
                result["planetary_crossings"] = crossings
        except Exception as e:
            result["planetary_crossings_error"] = str(e)

    return result
