#!/usr/bin/env python3
"""Builds three TunerStudio dashboards (.dash) for the Stealth PCM / MS3 (DM00.2xf).

The component XML mirrors what TunerStudio 3.3 writes, and the bezel / needle / font images are
taken from an existing dashboard so the look matches. Run:

    python3 dash/generate_dashboards.py path/to/existing.dash

and the three files land next to this script:
    track_boost.dash, idle_injectors_throttle.dash, drift_antilag.dash
"""
import re
import sys
import os
import datetime

SIGNATURE = "MS3 Format DM00.23f"
BEZEL = "IMG_ID_BezelgreyNarrow.png_0"
NEEDLE = "IMG_ID_fatNeedleRedShadowed2.png_1"
FONT = "Franklin Gothic Demi"

# colours as TunerStudio stores them (ARGB int) with the components spelled out
def color(r, g, b, a=255):
    v = (a << 24) | (r << 16) | (g << 8) | b
    if v >= 1 << 31:
        v -= 1 << 32
    return 'alpha="%d" blue="%d" green="%d" red="%d" type="Color">%d' % (a, b, g, r, v)

WHITE = color(255, 255, 255)
BLACK = color(0, 0, 0)
RED = color(255, 0, 0)
YELLOW = color(255, 255, 0)
GREEN = color(0, 200, 0)
ORANGE = color(255, 150, 0)
GREY = color(192, 192, 192)
DARKBACK = color(51, 51, 51, 204)
TRIM = color(153, 153, 153)
LINE_FG = color(102, 221, 62)
LINE_BG = color(8, 8, 8)
TRANSPARENT = 'alpha="0" blue="0" green="0" red="0" type="Color">0'


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def common_tail(channel, x, y, w, h, ecu_config="", italic=False, font=""):
    return (
        '<OutputChannel type="String">%s</OutputChannel>\n'
        '<ShortClickAction type="String"></ShortClickAction>\n'
        '<LongClickAction type="String"></LongClickAction>\n'
        '<Id type="String"></Id>\n'
        '<Dirty type="boolean">true</Dirty>\n'
        '<FontFamily type="String">%s</FontFamily>\n'
        '<EcuConfigurationName type="String">%s</EcuConfigurationName>\n'
        '<RelativeWidth type="double">%.6f</RelativeWidth>\n'
        '<RelativeX type="double">%.6f</RelativeX>\n'
        '<RunDemo type="boolean">false</RunDemo>\n'
        '<ItalicFont type="boolean">%s</ItalicFont>\n'
        '<AntialiasingOn type="boolean">true</AntialiasingOn>\n'
        '<InvalidState type="boolean">false</InvalidState>\n'
        '<RelativeY type="double">%.6f</RelativeY>\n'
        '<MustPaint type="boolean">true</MustPaint>\n'
        '<RelativeHeight type="double">%.6f</RelativeHeight>\n'
    ) % (esc(channel), font, ecu_config, w, x, "true" if italic else "false", y, h)


def gauge(style, channel, title, units, lo, hi, lw, hw, lc, hc, digits, x, y, w, h, **kw):
    """One gauge component. style: 'Circle Analog Gauge' | 'Basic Readout' | 'Horizontal Bar Gauge' | 'Horizontal Line Gauge'."""
    circle = style == "Circle Analog Gauge"
    bar = style == "Horizontal Bar Gauge"
    line = style == "Horizontal Line Gauge"
    if circle:
        font_color, back, needle, warn, crit, trim = BLACK, WHITE, RED, YELLOW, RED, TRANSPARENT
        bg_img, needle_img, border, rel_border, locked, sweep, sweep_begin, face = BEZEL, NEEDLE, 48, 0.0695364238410596, "true", 300, 300, 360
        show_hist, hist_delay, size_adj, at180 = "true", 15000, 1, "true"
        font = FONT
    elif bar:
        font_color, back, needle, warn, crit, trim = color(51, 255, 0), TRANSPARENT, kw.get("needle", GREEN), YELLOW, RED, color(105, 105, 122)
        bg_img, needle_img, border, rel_border, locked, sweep, sweep_begin, face = "null", "null", 1, "NaN", "false", 160, 10, 180
        show_hist, hist_delay, size_adj, at180 = "false", 15000, 0, "false"
        font = ""
    elif line:
        font_color, back, needle, warn, crit, trim = LINE_FG, LINE_BG, LINE_FG, YELLOW, RED, color(204, 204, 204)
        bg_img, needle_img, border, rel_border, locked, sweep, sweep_begin, face = "null", "null", 3, "NaN", "false", 300, 300, 360
        show_hist, hist_delay, size_adj, at180 = "false", 20000, 0, "false"
        font = ""
    else:  # Basic Readout
        font_color, back, needle, warn, crit, trim = WHITE, DARKBACK, RED, color(249, 249, 0, 107), RED, TRIM
        bg_img, needle_img, border, rel_border, locked, sweep, sweep_begin, face = "null", "null", 0, 0.0, "false", 300, 300, 360
        show_hist, hist_delay, size_adj, at180 = "true", 15000, kw.get("size_adj", 1), "true"
        font = ""
    fmt = lambda v: repr(float(v))
    body = (
        '<dashComp type="Gauge">\n'
        '<Value type="double">0.0</Value>\n'
        '<Dirty type="boolean">true</Dirty>\n'
        '<Title type="String">%s</Title>\n'
        '<Units type="String">%s</Units>\n'
        '<MinVP type="ValueProvider">%s</MinVP>\n'
        '<Min type="double">%s</Min>\n'
        '<MaxVP type="ValueProvider">%s</MaxVP>\n'
        '<Max type="double">%s</Max>\n'
        '<LabelDigits type="int">0</LabelDigits>\n'
        '<SweepAngle type="int">%d</SweepAngle>\n'
        '<LowWarning type="double">%s</LowWarning>\n'
        '<HighCritical type="double">%s</HighCritical>\n'
        '<MinorTicks type="double">-1.0</MinorTicks>\n'
        '<NeedleColor %s</NeedleColor>\n'
        '<GaugePainter type="GaugePainter">%s</GaugePainter>\n'
        '<HighWarningVP type="ValueProvider">%s</HighWarningVP>\n'
        '<LowCritical type="double">%s</LowCritical>\n'
        '<ValueDigitsVP type="ValueProvider">%s</ValueDigitsVP>\n'
        '<WarnColor %s</WarnColor>\n'
        '<ShowHistory type="boolean">%s</ShowHistory>\n'
        '<PegLimits type="boolean">true</PegLimits>\n'
        '<TrimColor %s</TrimColor>\n'
        '<LowCriticalVP type="ValueProvider">%s</LowCriticalVP>\n'
        '<HighWarning type="double">%s</HighWarning>\n'
        '<StartAngle type="int">0</StartAngle>\n'
        '<MajorTicks type="double">-1.0</MajorTicks>\n'
        '<GaugeStyle type="String">%s</GaugeStyle>\n'
        '<ValueDigits type="int">%d</ValueDigits>\n'
        '<ShortestSize type="int">100</ShortestSize>\n'
        '<HistoryDelay type="int">%d</HistoryDelay>\n'
        '<LowWarningVP type="ValueProvider">%s</LowWarningVP>\n'
        '<SmoothedValue type="double">0.0</SmoothedValue>\n'
        '<RunDemo type="boolean">false</RunDemo>\n'
        '<MustPaint type="boolean">true</MustPaint>\n'
        '<GoingDead type="boolean">false</GoingDead>\n'
        '<GroupId type="int">0</GroupId>\n'
        '<BorderWidth type="int">%d</BorderWidth>\n'
        '<DefaultMax type="double">%s</DefaultMax>\n'
        '<DisplayValue type="String">0</DisplayValue>\n'
        '<FontColor %s</FontColor>\n'
        '<BackColor %s</BackColor>\n'
        '<DefaultMin type="double">%s</DefaultMin>\n'
        '<CriticalColor %s</CriticalColor>\n'
        '<FaceAngle type="int">%d</FaceAngle>\n'
        '<BackgroundImageFileName type="String">%s</BackgroundImageFileName>\n'
        '<FontSizeAdjustment type="int">%d</FontSizeAdjustment>\n'
        '<HighCriticalVP type="ValueProvider">%s</HighCriticalVP>\n'
        '<HistoricalPeakValue type="double">0.0</HistoricalPeakValue>\n'
        '<NeedleSmoothing type="int">1</NeedleSmoothing>\n'
        '<CounterClockwise type="boolean">false</CounterClockwise>\n'
        '<RelativeBorderWidth2 type="double">%s</RelativeBorderWidth2>\n'
        '<SweepBeginDegree type="int">%d</SweepBeginDegree>\n'
        '<ShapeLockedToAspect type="boolean">%s</ShapeLockedToAspect>\n'
        '<DisplayValueAt180 type="boolean">%s</DisplayValueAt180>\n'
        '<NeedleImageFileName type="String">%s</NeedleImageFileName>\n'
        '<Value type="double">0.0</Value>\n'
        '<Dirty type="boolean">true</Dirty>\n'
    ) % (esc(title), esc(units), fmt(lo), fmt(lo), fmt(hi), fmt(hi), sweep, fmt(lw), fmt(hc), needle, style, fmt(hw), fmt(lc),
         fmt(digits), warn, show_hist, trim, fmt(lc), fmt(hw), style, digits, hist_delay, fmt(lw), border, fmt(hi), font_color, back,
         fmt(lo), crit, face, bg_img, size_adj, fmt(hc), rel_border, sweep_begin, locked, at180, needle_img)
    return body + common_tail(channel, x, y, w, h, italic=not circle and not bar and not line, font=font) + "</dashComp>\n"


def indicator(channel, off_text, on_text, on_bg, x, y, w, h, off_bg=WHITE, on_fg=BLACK, off_fg=GREY, ecu_config=""):
    return (
        '<dashComp type="Indicator">\n'
        '<Value type="double">0.0</Value>\n'
        '<Painter type="IndicatorPainter">Basic Rectangle Indicator</Painter>\n'
        '<OnTextColor %s</OnTextColor>\n'
        '<OffTextColor %s</OffTextColor>\n'
        '<RunDemo type="boolean">false</RunDemo>\n'
        '<MustPaint type="boolean">false</MustPaint>\n'
        '<OffText type="String">%s</OffText>\n'
        '<OnText type="String">%s</OnText>\n'
        '<OnImageFileName type="String">null</OnImageFileName>\n'
        '<OffImageFileName type="String">null</OffImageFileName>\n'
        '<OffBackgroundColor %s</OffBackgroundColor>\n'
        '<OnBackgroundColor %s</OnBackgroundColor>\n'
        '<Value type="double">0.0</Value>\n'
        '<Dirty type="boolean">true</Dirty>\n'
    ) % (on_fg, off_fg, esc(off_text), esc(on_text), off_bg, on_bg) + common_tail(channel, x, y, w, h, ecu_config=ecu_config) + "</dashComp>\n"


def label(text, x, y, w, h, fg=WHITE):
    return (
        '<dashComp type="Label">\n'
        '<BackgroundColor type="Color">Transparent</BackgroundColor>\n'
        '<TextColor %s</TextColor>\n'
        '<Text type="String">%s</Text>\n'
        '<RunDemo type="boolean">false</RunDemo>\n'
        '<MustPaint type="boolean">false</MustPaint>\n'
        '<Id type="String"></Id>\n'
        '<Dirty type="boolean">true</Dirty>\n'
        '<FontFamily type="String"></FontFamily>\n'
        '<EcuConfigurationName type="String"></EcuConfigurationName>\n'
        '<RelativeWidth type="double">%.6f</RelativeWidth>\n'
        '<RelativeX type="double">%.6f</RelativeX>\n'
        '<RunDemo type="boolean">false</RunDemo>\n'
        '<ItalicFont type="boolean">false</ItalicFont>\n'
        '<AntialiasingOn type="boolean">true</AntialiasingOn>\n'
        '<InvalidState type="boolean">false</InvalidState>\n'
        '<RelativeY type="double">%.6f</RelativeY>\n'
        '<MustPaint type="boolean">false</MustPaint>\n'
        '<RelativeHeight type="double">%.6f</RelativeHeight>\n'
        '</dashComp>\n'
    ) % (fg, esc(text), w, x, y, h)


# ---- shorthands ----------------------------------------------------------------------------
def circle(ch, title, units, lo, hi, lw, hw, lc, hc, digits, x, y, w, h):
    return gauge("Circle Analog Gauge", ch, title, units, lo, hi, lw, hw, lc, hc, digits, x, y, w, h)


def readout(ch, title, units, lo, hi, lw, hw, lc, hc, digits, x, y, w, h):
    return gauge("Basic Readout", ch, title, units, lo, hi, lw, hw, lc, hc, digits, x, y, w, h)


def line(ch, title, units, lo, hi, x, y, w, h, lw=None, hw=None, lc=None, hc=None, digits=1):
    return gauge("Horizontal Line Gauge", ch, title, units, lo, hi, lo if lw is None else lw, hi if hw is None else hw,
                 lo if lc is None else lc, hi if hc is None else hc, digits, x, y, w, h)


def bar(ch, title, units, lo, hi, x, y, w, h, hw=None, hc=None, digits=1):
    return gauge("Horizontal Bar Gauge", ch, title, units, lo, hi, lo, hi if hw is None else hw, lo, hi if hc is None else hc, digits, x, y, w, h)


def app_indicators(y):
    """Connection / logging state along the bottom edge, like the stock dashboards."""
    return (indicator("controllerConnected", "Not connected", "Connected", GREEN, 0.005, y, 0.09, 0.028, ecu_config="Application Events")
            + indicator("dataLoggingActive", "Logging off", "Logging", GREEN, 0.10, y, 0.09, 0.028, ecu_config="Application Events")
            + indicator("protocolError", "Protocol ok", "Protocol Error", YELLOW, 0.195, y, 0.09, 0.028, ecu_config="Application Events"))


# ---- status bits: TunerStudio exposes the INI's { statusN & bit } indicators as statusNANDbit_OC channels --
def ind_row(items, x0, y, w, h, gap=0.006):
    out = ""
    x = x0
    for ch, off, on, bg in items:
        out += indicator(ch, off, on, bg, x, y, w, h)
        x += w + gap
    return out


def track_boost():
    c = ""
    # top row: the three big ones
    c += circle("rpm", "Engine Speed", "RPM", 0, 9000, 600, 7000, 300, 8000, 0, 0.00, 0.01, 0.235, 0.42)
    c += circle("map", "Manifold Pressure", "kPa", 0, 300, 20, 200, 10, 220, 0, 0.235, 0.01, 0.235, 0.42)
    c += circle("afr1", "Air:Fuel Ratio", "AFR", 10, 19.4, 10.5, 13.0, 10.0, 14.0, 2, 0.47, 0.01, 0.235, 0.42)
    # right column: numbers that matter while tuning boost on the road
    rx, rw, rh = 0.715, 0.138, 0.078
    col2 = rx + rw + 0.006
    rows = [
        ("boost_targ_1", "Boost target", "kPa", 0, 300, 0, 300, 0, 300, 0),
        ("boostduty", "Boost duty", "%", 0, 100, -1, 100, -1, 100, 0),
        ("advance", "Ignition advance", "deg", -20, 50, -1, 999, -1, 999, 1),
        ("knockRetard", "Knock retard", "deg", 0, 25, -1, 2, -1, 5, 1),
        ("coolant_C", "Coolant", "°C", -40, 130, 60, 100, 40, 108, 0),
        ("mat_C", "Intake air", "°C", -40, 110, 0, 60, -15, 75, 0),
        ("egt1", "EGT 1", "°C", 0, 1250, 0, 900, 0, 980, 0),
        ("oil_pressure", "Oil pressure", "kPa", 0, 800, 150, 700, 100, 750, 0),
        ("fuel_press1", "Fuel pressure", "kPa", 0, 600, 250, 500, 200, 550, 0),
        ("batteryVoltage", "Battery", "V", 7, 21, 12.5, 15, 11.5, 16, 2),
        ("vvt_ang1", "VVT angle", "deg", 0, 60, -1, 999, -1, 999, 1),
        ("vvt_target1", "VVT target", "deg", 0, 60, -1, 999, -1, 999, 1),
    ]
    for i, r in enumerate(rows):
        x = rx if i % 2 == 0 else col2
        y = 0.01 + (i // 2) * (rh + 0.006)
        c += readout(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], x, y, rw, rh)
    # second row: smaller dials for the things that bite
    c += circle("knockRetard", "Knock Retard", "deg", 0, 25, -1, 2, -1, 5, 1, 0.00, 0.44, 0.175, 0.31)
    c += circle("egt1", "EGT 1", "°C", 0, 1250, 0, 900, 0, 980, 0, 0.175, 0.44, 0.175, 0.31)
    c += circle("oil_pressure", "Oil Pressure", "kPa", 0, 800, 150, 700, 100, 750, 0, 0.35, 0.44, 0.175, 0.31)
    c += circle("boostduty", "Boost Duty", "%", 0, 100, -1, 100, -1, 100, 0, 0.525, 0.44, 0.175, 0.31)
    # throttle and boost target vs map as lines
    c += line("throttle", "Throttle", "%", 0, 100, 0.715, 0.53, 0.24, 0.045, lw=1, hw=90, lc=-1, hc=100)
    c += readout("throttle", "", "%", 0, 100, 1, 90, -1, 100, 1, 0.955, 0.53, 0.045, 0.05)
    c += line("map", "MAP", "kPa", 0, 300, 0.715, 0.585, 0.24, 0.045, hw=200, hc=220)
    c += readout("map", "", "kPa", 0, 300, 0, 200, 0, 220, 0, 0.955, 0.585, 0.045, 0.05)
    c += line("boost_targ_1", "Boost target", "kPa", 0, 300, 0.715, 0.64, 0.24, 0.045)
    c += readout("boost_targ_1", "", "kPa", 0, 300, 0, 300, 0, 300, 0, 0.955, 0.64, 0.045, 0.05)
    c += line("boostduty", "Boost duty", "%", 0, 100, 0.715, 0.695, 0.24, 0.045)
    c += readout("boostduty", "", "%", 0, 100, -1, 100, -1, 100, 0, 0.955, 0.695, 0.045, 0.05)
    # indicators: limiters and safety first
    c += label("Boost / limiters", 0.005, 0.765, 0.2, 0.03)
    c += ind_row([
        ("status2AND64_OC", "Over boost", "OVER BOOST", RED),
        ("status2AND32_OC", "Spark cut", "SPARK CUT", RED),
        ("status3AND1_OC", "No fuel cut", "FUEL CUT", RED),
        ("status3AND32_OC", "No soft limit", "SOFT LIMITER", RED),
        ("status7AND16_OC", "No knock", "KNOCK", RED),
        ("status10AND1_OC", "Boost open-loop", "Boost closed-loop", GREEN),
        ("status2AND4_OC", "No launch", "LAUNCH", GREEN),
        ("status2AND16_OC", "No flat shift", "FLAT SHIFT", GREEN),
        ("status10AND128_OC", "Antilag off", "ALS ACTIVE", ORANGE),
    ], 0.005, 0.80, 0.104, 0.05)
    c += label("Faults", 0.005, 0.86, 0.2, 0.03)
    c += ind_row([
        ("cel_status_afrshut", "AFR ok", "AFR SHUTDOWN", RED),
        ("cel_status_egtshut", "EGT ok", "EGT SHUTDOWN", RED),
        ("cel_status_oil", "Oil ok", "OIL FAULT", RED),
        ("cel_status_fp", "Fuel press ok", "FUEL PRESS FAULT", RED),
        ("cel_status_knock", "Knock sensor ok", "KNOCK SENSOR", RED),
        ("cel_status_map", "MAP ok", "MAP FAULT", RED),
        ("cel_status_sync", "Sync ok", "SYNC FAULT", RED),
        ("status1AND1_OC", "Burned", "NEED BURN", YELLOW),
        ("status1AND4_OC", "Config ok", "CONFIG ERROR", RED),
    ], 0.005, 0.895, 0.104, 0.05)
    c += app_indicators(0.962)
    return c


def idle_injectors_throttle():
    c = ""
    # idle: a fine RPM scale and the idle valve numbers
    c += circle("rpm", "Engine Speed", "RPM", 0, 4000, 500, 3500, 300, 3800, 0, 0.00, 0.01, 0.235, 0.42)
    c += circle("dcseq1", "Injector Duty 1", "%", 0, 100, -1, 85, -1, 90, 1, 0.235, 0.01, 0.235, 0.42)
    c += circle("afr1", "Air:Fuel Ratio", "AFR", 10, 19.4, 13.0, 15.5, 12.0, 16.5, 2, 0.47, 0.01, 0.235, 0.42)
    rx, rw, rh = 0.715, 0.138, 0.078
    col2 = rx + rw + 0.006
    rows = [
        ("cl_idle_targ_rpm", "Idle target", "RPM", 0, 3000, 0, 3000, 0, 3000, 0),
        ("iacstep", "Idle valve steps", "", 0, 255, -1, 256, -1, 256, 0),
        ("idleDC", "Idle valve duty", "%", 0, 100, -1, 101, -1, 101, 1),
        ("cl_idle_corr_iac", "CL idle correction", "steps", -100, 100, -101, 101, -101, 101, 0),
        ("pulseWidth1", "Pulse width 1", "ms", 0, 25.5, 1.2, 20, 1.0, 25, 3),
        ("veCurr1", "VE current", "%", 0, 200, -1, 999, -1, 999, 1),
        ("egocor1", "EGO correction", "%", 50, 150, 95, 105, 85, 115, 1),
        ("afrtgt1", "AFR target", "AFR", 10, 19.4, -1, 99, -1, 99, 1),
        ("warmupEnrich", "Warmup enrich", "%", 50, 200, -1, 201, -1, 201, 0),
        ("accelEnrich", "Accel enrich", "ms", 0, 25, -1, 99, -1, 99, 1),
        ("dwell", "Dwell", "ms", 0, 10, 1.5, 5, 1.0, 6, 2),
        ("batteryVoltage", "Battery", "V", 7, 21, 12.5, 15, 11.5, 16, 2),
    ]
    for i, r in enumerate(rows):
        x = rx if i % 2 == 0 else col2
        y = 0.01 + (i // 2) * (rh + 0.006)
        c += readout(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], x, y, rw, rh)
    # second row: EGO correction dial, MAP, temperatures
    c += circle("egocor1", "EGO Correction", "%", 50, 150, 95, 105, 85, 115, 1, 0.00, 0.44, 0.175, 0.31)
    c += circle("map", "Manifold Pressure", "kPa", 0, 120, 20, 105, 10, 110, 0, 0.175, 0.44, 0.175, 0.31)
    c += circle("coolant_C", "Coolant", "°C", -40, 130, 60, 100, 40, 108, 0, 0.35, 0.44, 0.175, 0.31)
    c += circle("mat_C", "Intake Air", "°C", -40, 110, 0, 60, -15, 75, 0, 0.525, 0.44, 0.175, 0.31)
    # throttle block
    c += line("throttle", "Throttle", "%", 0, 100, 0.715, 0.53, 0.24, 0.045, lw=1, hw=90, lc=-1, hc=100)
    c += readout("throttle", "", "%", 0, 100, 1, 90, -1, 100, 1, 0.955, 0.53, 0.045, 0.05)
    c += line("pps", "Pedal", "%", 0, 100, 0.715, 0.585, 0.24, 0.045)
    c += readout("pps", "", "%", 0, 100, 0, 100, 0, 100, 1, 0.955, 0.585, 0.045, 0.05)
    c += line("tps_target", "TPS target (DBW)", "%", 0, 100, 0.715, 0.64, 0.24, 0.045)
    c += readout("tps_target", "", "%", 0, 100, 0, 100, 0, 100, 1, 0.955, 0.64, 0.045, 0.05)
    c += readout("TPSdot", "TPS rate", "%/s", -1000, 1000, -1001, 1001, -1001, 1001, 0, 0.715, 0.695, 0.117, 0.06)
    c += readout("MAPdot", "MAP rate", "kPa/s", -1000, 1000, -1001, 1001, -1001, 1001, 0, 0.838, 0.695, 0.117, 0.06)
    c += readout("barometer", "Barometer", "kPa", 60, 110, 0, 200, 0, 200, 1, 0.961, 0.695, 0.039, 0.06)
    # indicators
    c += label("Idle / enrichment", 0.005, 0.765, 0.25, 0.03)
    c += ind_row([
        ("status2AND128_OC", "CL idle off", "CL IDLE ON", GREEN),
        ("status6AND16_OC", "Idle VE off", "IDLE VE", GREEN),
        ("status6AND32_OC", "Idle adv off", "IDLE ADV", GREEN),
        ("status1AND16_OC", "Idle-up off", "IDLE-UP", GREEN),
        ("status7AND32_OC", "AC off", "AC ON", GREEN),
        ("status6AND64_OC", "Fan off", "FAN ON", GREEN),
        ("tpsaccaen", "TPS accel off", "TPS ACCEL", YELLOW),
        ("tpsaccden", "TPS decel off", "TPS DECEL", YELLOW),
        ("mapaccaen", "MAP accel off", "MAP ACCEL", YELLOW),
    ], 0.005, 0.80, 0.104, 0.05)
    c += label("Engine state", 0.005, 0.86, 0.2, 0.03)
    c += ind_row([
        ("ready", "Not ready", "READY", GREEN),
        ("crank", "Not cranking", "CRANKING", YELLOW),
        ("warmup", "Warm", "WARMUP", YELLOW),
        ("status3AND1_OC", "No fuel cut", "OVER-RUN CUT", RED),
        ("dbw_status_en", "DBW off", "DBW ON", GREEN),
        ("dbw_status_pid", "DBW PID off", "DBW PID", GREEN),
        ("cel_status_tps", "TPS ok", "TPS FAULT", RED),
        ("cel_status_clt", "CLT ok", "CLT FAULT", RED),
        ("status1AND1_OC", "Burned", "NEED BURN", YELLOW),
    ], 0.005, 0.895, 0.104, 0.05)
    c += app_indicators(0.962)
    return c


def drift_antilag():
    c = ""
    # what you glance at mid-drift: revs, boost, intake heat, exhaust heat
    c += circle("rpm", "Engine Speed", "RPM", 0, 9000, 600, 7000, 300, 8000, 0, 0.00, 0.01, 0.235, 0.42)
    c += circle("map", "Manifold Pressure", "kPa", 0, 300, 20, 200, 10, 220, 0, 0.235, 0.01, 0.235, 0.42)
    c += circle("mat_C", "Intake Air", "°C", -40, 110, 0, 65, -15, 80, 0, 0.47, 0.01, 0.235, 0.42)
    rx, rw, rh = 0.715, 0.138, 0.078
    col2 = rx + rw + 0.006
    rows = [
        ("egt1", "EGT 1", "°C", 0, 1250, 0, 900, 0, 980, 0),
        ("advance", "Ignition timing", "deg", -30, 50, -1, 999, -1, 999, 1),
        ("boost_targ_1", "Boost target", "kPa", 0, 300, 0, 300, 0, 300, 0),
        ("boostduty", "Boost duty", "%", 0, 100, -1, 100, -1, 100, 0),
        ("afr1", "AFR", "AFR", 10, 19.4, 10.5, 13.0, 10.0, 14.0, 2),
        ("knockRetard", "Knock retard", "deg", 0, 25, -1, 2, -1, 5, 1),
        ("coolant_C", "Coolant", "°C", -40, 130, 60, 100, 40, 108, 0),
        ("oil_pressure", "Oil pressure", "kPa", 0, 800, 150, 700, 100, 750, 0),
        ("fuel_press1", "Fuel pressure", "kPa", 0, 600, 250, 500, 200, 550, 0),
        ("batteryVoltage", "Battery", "V", 7, 21, 12.5, 15, 11.5, 16, 2),
        ("iacstep", "Idle valve steps", "", 0, 255, -1, 256, -1, 256, 0),
        ("gear", "Gear", "", 0, 6, -1, 7, -1, 7, 0),
    ]
    for i, r in enumerate(rows):
        x = rx if i % 2 == 0 else col2
        y = 0.01 + (i // 2) * (rh + 0.006)
        c += readout(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], x, y, rw, rh)
    c += circle("egt1", "EGT 1", "°C", 0, 1250, 0, 900, 0, 980, 0, 0.00, 0.44, 0.175, 0.31)
    c += circle("advance", "Ignition Timing", "deg", -30, 50, -1, 999, -1, 999, 1, 0.175, 0.44, 0.175, 0.31)
    c += circle("afr1", "Air:Fuel Ratio", "AFR", 10, 19.4, 10.5, 13.0, 10.0, 14.0, 2, 0.35, 0.44, 0.175, 0.31)
    c += circle("oil_pressure", "Oil Pressure", "kPa", 0, 800, 150, 700, 100, 750, 0, 0.525, 0.44, 0.175, 0.31)
    c += line("throttle", "Throttle", "%", 0, 100, 0.715, 0.53, 0.24, 0.045, lw=1, hw=90, lc=-1, hc=100)
    c += readout("throttle", "", "%", 0, 100, 1, 90, -1, 100, 1, 0.955, 0.53, 0.045, 0.05)
    c += line("map", "MAP", "kPa", 0, 300, 0.715, 0.585, 0.24, 0.045, hw=200, hc=220)
    c += readout("map", "", "kPa", 0, 300, 0, 200, 0, 220, 0, 0.955, 0.585, 0.045, 0.05)
    c += line("mat_C", "Intake air", "°C", -40, 110, 0.715, 0.64, 0.24, 0.045, hw=65, hc=80)
    c += readout("mat_C", "", "°C", -40, 110, 0, 65, -15, 80, 0, 0.955, 0.64, 0.045, 0.05)
    c += line("egt1", "EGT 1", "°C", 0, 1250, 0.715, 0.695, 0.24, 0.045, hw=900, hc=980)
    c += readout("egt1", "", "°C", 0, 1250, 0, 900, 0, 980, 0, 0.955, 0.695, 0.045, 0.05)
    # the anti-lag, launch and flat shift states large, safety cuts next to them
    c += label("Anti-lag / launch", 0.005, 0.765, 0.25, 0.03)
    c += ind_row([
        ("status10AND128_OC", "Antilag off", "ALS ACTIVE", ORANGE),
        ("status2AND4_OC", "No launch", "LAUNCH", GREEN),
        ("status2AND16_OC", "No flat shift", "FLAT SHIFT", GREEN),
        ("status3AND128_OC", "Launch off", "LAUNCH ON", GREEN),
        ("status2AND32_OC", "Spark cut", "SPARK CUT", RED),
        ("status3AND1_OC", "No fuel cut", "FUEL CUT", RED),
        ("status2AND64_OC", "Over boost", "OVER BOOST", RED),
        ("status3AND32_OC", "No soft limit", "SOFT LIMITER", RED),
        ("status7AND16_OC", "No knock", "KNOCK", RED),
    ], 0.005, 0.80, 0.104, 0.05)
    c += label("Heat / faults", 0.005, 0.86, 0.2, 0.03)
    c += ind_row([
        ("cel_status_egtshut", "EGT ok", "EGT SHUTDOWN", RED),
        ("cel_status_afrshut", "AFR ok", "AFR SHUTDOWN", RED),
        ("cel_status_mat", "MAT ok", "MAT FAULT", RED),
        ("cel_status_oil", "Oil ok", "OIL FAULT", RED),
        ("cel_status_fp", "Fuel press ok", "FUEL PRESS FAULT", RED),
        ("cel_status_egt", "EGT sensor ok", "EGT SENSOR", RED),
        ("status10AND1_OC", "Boost open-loop", "Boost closed-loop", GREEN),
        ("status1AND1_OC", "Burned", "NEED BURN", YELLOW),
        ("status1AND4_OC", "Config ok", "CONFIG ERROR", RED),
    ], 0.005, 0.895, 0.104, 0.05)
    c += app_indicators(0.962)
    return c


def build(images_xml, comps):
    now = datetime.datetime.now().strftime("%a %b %d %H:%M:%S %Y")
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n'
        '<dsh xmlns="http://www.EFIAnalytics.com/:dsh">\n'
        '<bibliography author="Boost Autotune dashboards (generated)" company="" writeDate="%s"/>\n'
        '<versionInfo fileFormat="3.0" firmwareSignature="%s"/>\n'
        '<gaugeCluster antiAliasing="true" backgroundDitherColor="-8355712" clusterBackgroundColor="-13421773" '
        'clusterBackgroundImageFileName="" clusterBackgroundImageStyle="Center" forceAspect="false" forceAspectHeight="9.0" forceAspectWidth="16.0">\n'
        '%s%s</gaugeCluster>\n</dsh>\n'
    ) % (now, SIGNATURE, images_xml, comps)


def main():
    if len(sys.argv) < 2:
        print("usage: generate_dashboards.py existing.dash [signature]")
        sys.exit(1)
    src = open(sys.argv[1], encoding="utf-8").read()
    global SIGNATURE
    if len(sys.argv) > 2:
        SIGNATURE = sys.argv[2]
    else:
        m = re.search(r'firmwareSignature="([^"]+)"', src)
        if m:
            SIGNATURE = m.group(1)
    images = re.findall(r"<imageFile [^>]*>.*?</imageFile>", src, re.S)
    if not images:
        raise SystemExit("no <imageFile> elements in " + sys.argv[1])
    images_xml = "".join(i + "\n" for i in images)
    here = os.path.dirname(os.path.abspath(__file__))
    for name, fn in (("track_boost", track_boost), ("idle_injectors_throttle", idle_injectors_throttle), ("drift_antilag", drift_antilag)):
        out = os.path.join(here, name + ".dash")
        with open(out, "w", encoding="utf-8", newline="\r\n") as f:
            f.write(build(images_xml, fn()))
        print("wrote", out)


if __name__ == "__main__":
    main()
