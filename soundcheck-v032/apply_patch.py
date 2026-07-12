#!/usr/bin/env python3
from pathlib import Path
import re, sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "generated/SoundCheckAssistant")
java_path = root / "app/src/main/java/si/ell/soundcheck/MainActivity.java"
gradle_path = root / "app/build.gradle"
text = java_path.read_text(encoding="utf-8")

gradle = gradle_path.read_text(encoding="utf-8")
gradle, n1 = re.subn(r'versionCode\s+4\b', 'versionCode 5', gradle, count=1)
gradle, n2 = re.subn(r'versionName\s+["\']0\.3\.1["\']', 'versionName "0.3.2"', gradle, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit(f"Version replacement failed: {n1}, {n2}")
gradle_path.write_text(gradle, encoding="utf-8")

m = re.search(r'(?m)^(?P<indent>\s*)(?:private|protected|public)?\s*(?:static\s+)?(?:final\s+)?class\s+SpectrumView\s+extends\s+View\s*\{', text)
if not m:
    raise SystemExit("SpectrumView class not found")
brace_start = text.find("{", m.start(), m.end() + 2)
depth = 0
class_end = None
for i in range(brace_start, len(text)):
    if text[i] == "{":
        depth += 1
    elif text[i] == "}":
        depth -= 1
        if depth == 0:
            class_end = i
            break
if class_end is None:
    raise SystemExit("SpectrumView closing brace not found")
class_text = text[m.start():class_end + 1]

calls = list(re.finditer(r'\bspectrumView\.(?P<name>[A-Za-z_]\w*)\s*\((?P<args>.*?)\)\s*;', text, re.S))
candidate_calls = [c for c in calls if "spectrum" in c.group("name").lower()]
if not candidate_calls:
    candidate_calls = [c for c in calls if c.group("name").lower().startswith(("set", "update"))]
if not candidate_calls:
    raise SystemExit("No spectrumView update call found")
call = candidate_calls[0]
setter_name = call.group("name")

sig_re = re.compile(
    r'(?P<indent>^[ \t]*)(?P<mods>(?:public|protected|private|static|final|synchronized|\s)+)'
    + re.escape(setter_name)
    + r'\s*\((?P<params>[^)]*)\)\s*\{',
    re.M
)
sm = sig_re.search(class_text)
if not sm:
    sig_re = re.compile(r'(?P<indent>^[ \t]*)' + re.escape(setter_name) + r'\s*\((?P<params>[^)]*)\)\s*\{', re.M)
    sm = sig_re.search(class_text)
if not sm:
    raise SystemExit(f"Setter declaration not found: {setter_name}")

params = sm.group("params").strip()
param_parts = [p.strip() for p in params.split(",") if p.strip()]
parsed = []
for p in param_parts:
    p_clean = re.sub(r'@\w+(?:\([^)]*\))?\s*', '', p).strip()
    mm = re.match(r'(?P<type>[\w.<>,?\[\]]+(?:\s*\[\])?)\s+(?P<name>\w+)$', p_clean)
    if not mm:
        raise SystemExit(f"Cannot parse parameter: {p}")
    parsed.append((mm.group("type").replace(" ", ""), mm.group("name")))

array_params = [(typ, name) for typ, name in parsed if "[]" in typ]
if not array_params:
    raise SystemExit(f"Spectrum setter has no array parameter: {params}")
value_type, value_name = array_params[0]
for typ, name in array_params:
    if not any(k in name.lower() for k in ("freq", "hz", "axis", "x")):
        value_type, value_name = typ, name
        break
freq_array = None
for typ, name in array_params:
    if any(k in name.lower() for k in ("freq", "hz", "axis")):
        freq_array = (typ, name)
        break
sample_rate_name = None
for typ, name in parsed:
    if typ in ("int", "long", "float", "double") and any(k in name.lower() for k in ("samplerate", "sample_rate", "rate", "sr")):
        sample_rate_name = name
        break

base_type = value_type.replace("[]", "")
if base_type == "float":
    convert_code = f"this.liveSpectrum = java.util.Arrays.copyOf({value_name}, {value_name}.length);"
elif base_type == "double":
    convert_code = f"this.liveSpectrum = new float[{value_name}.length];\n            for (int i = 0; i < {value_name}.length; i++) this.liveSpectrum[i] = (float) {value_name}[i];"
elif base_type in ("int", "short", "long"):
    convert_code = f"this.liveSpectrum = new float[{value_name}.length];\n            for (int i = 0; i < {value_name}.length; i++) this.liveSpectrum[i] = {value_name}[i];"
else:
    raise SystemExit(f"Unsupported spectrum array type: {value_type}")

freq_copy_code = ""
if freq_array:
    ftyp, fname = freq_array
    if ftyp.replace("[]", "") == "float":
        freq_copy_code = f"this.liveFrequencies = java.util.Arrays.copyOf({fname}, {fname}.length);"
    elif ftyp.replace("[]", "") == "double":
        freq_copy_code = f"this.liveFrequencies = new float[{fname}.length];\n            for (int i = 0; i < {fname}.length; i++) this.liveFrequencies[i] = (float) {fname}[i];"
rate_code = f"this.liveSampleRate = Math.max(8000, (int) {sample_rate_name});" if sample_rate_name else ""

indent = sm.group("indent")
body_indent = indent + "    "
new_members = f'''
{indent}// v0.3.2: live graph bypasses the smoothed text/warning refresh path.
{indent}private volatile float[] liveSpectrum = new float[0];
{indent}private volatile float[] liveFrequencies = null;
{indent}private volatile int liveSampleRate = 44100;
{indent}private volatile float liveTouchX = -1f;

{indent}public void setLiveSpectrum({params}) {{
{body_indent}if ({value_name} == null || {value_name}.length < 2) return;
{body_indent}synchronized (this) {{
{body_indent}    {convert_code}
'''
if freq_copy_code:
    new_members += f"{body_indent}    {freq_copy_code}\n"
if rate_code:
    new_members += f"{body_indent}    {rate_code}\n"
new_members += f'''{body_indent}}}
{body_indent}postInvalidateOnAnimation();
{indent}}}

{indent}@Override
{indent}public boolean onTouchEvent(android.view.MotionEvent event) {{
{body_indent}switch (event.getActionMasked()) {{
{body_indent}    case android.view.MotionEvent.ACTION_DOWN:
{body_indent}    case android.view.MotionEvent.ACTION_MOVE:
{body_indent}        liveTouchX = event.getX();
{body_indent}        getParent().requestDisallowInterceptTouchEvent(true);
{body_indent}        postInvalidateOnAnimation();
{body_indent}        return true;
{body_indent}    case android.view.MotionEvent.ACTION_UP:
{body_indent}    case android.view.MotionEvent.ACTION_CANCEL:
{body_indent}        liveTouchX = -1f;
{body_indent}        getParent().requestDisallowInterceptTouchEvent(false);
{body_indent}        postInvalidateOnAnimation();
{body_indent}        return true;
{body_indent}    default:
{body_indent}        return true;
{body_indent}}}
{indent}}}

{indent}private boolean drawLiveGraph(Canvas canvas) {{
{body_indent}float[] values;
{body_indent}float[] frequencies;
{body_indent}synchronized (this) {{
{body_indent}    if (liveSpectrum == null || liveSpectrum.length < 2) return false;
{body_indent}    values = java.util.Arrays.copyOf(liveSpectrum, liveSpectrum.length);
{body_indent}    frequencies = liveFrequencies == null ? null : java.util.Arrays.copyOf(liveFrequencies, liveFrequencies.length);
{body_indent}}}
{body_indent}final float density = getResources().getDisplayMetrics().density;
{body_indent}final float left = 46f * density;
{body_indent}final float top = 28f * density;
{body_indent}final float right = getWidth() - 12f * density;
{body_indent}final float bottom = getHeight() - 30f * density;
{body_indent}if (right <= left || bottom <= top) return true;
{body_indent}Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
{body_indent}paint.setStyle(Paint.Style.FILL);
{body_indent}paint.setColor(Color.rgb(14, 19, 27));
{body_indent}canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
{body_indent}final float minDb = -90f, maxDb = 0f;
{body_indent}final float nyquist = Math.max(4000f, liveSampleRate * 0.5f);
{body_indent}final float minHz = 20f, maxHz = Math.min(20000f, nyquist);
{body_indent}final double logMin = Math.log10(minHz);
{body_indent}final double logRange = Math.max(0.1, Math.log10(maxHz) - logMin);
{body_indent}float rawMin = Float.POSITIVE_INFINITY, rawMax = Float.NEGATIVE_INFINITY;
{body_indent}for (float v : values) if (Float.isFinite(v)) {{ rawMin = Math.min(rawMin, v); rawMax = Math.max(rawMax, v); }}
{body_indent}final boolean linearInput = rawMin >= 0f && rawMax <= 10f;
{body_indent}paint.setStrokeWidth(1f * density);
{body_indent}paint.setTextSize(10f * density);
{body_indent}paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
{body_indent}int[] dbTicks = new int[]{{0, -20, -40, -60, -80}};
{body_indent}for (int db : dbTicks) {{
{body_indent}    float y = top + (maxDb - db) / (maxDb - minDb) * (bottom - top);
{body_indent}    paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.rgb(74, 87, 104)); canvas.drawLine(left, y, right, y, paint);
{body_indent}    paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(165, 176, 191)); canvas.drawText(String.valueOf(db), 5f * density, y + 3f * density, paint);
{body_indent}}}
{body_indent}canvas.drawText("dBFS", 5f * density, 12f * density, paint);
{body_indent}int[] hzTicks = new int[]{{20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000}};
{body_indent}for (int hz : hzTicks) {{
{body_indent}    if (hz > maxHz) continue;
{body_indent}    float x = left + (float) ((Math.log10(hz) - logMin) / logRange) * (right - left);
{body_indent}    paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.rgb(55, 67, 83)); canvas.drawLine(x, top, x, bottom, paint);
{body_indent}    paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(165, 176, 191));
{body_indent}    String label = hz >= 1000 ? ((hz / 1000) + "k") : String.valueOf(hz);
{body_indent}    canvas.drawText(label, x - 7f * density, bottom + 16f * density, paint);
{body_indent}}}
{body_indent}canvas.drawText("Hz", right - 12f * density, bottom + 16f * density, paint);
{body_indent}Path path = new Path();
{body_indent}boolean started = false;
{body_indent}for (int i = 0; i < values.length; i++) {{
{body_indent}    float hz = frequencies != null && frequencies.length == values.length ? frequencies[i] : (i * nyquist) / Math.max(1, values.length - 1);
{body_indent}    if (hz < minHz || hz > maxHz) continue;
{body_indent}    float db = linearInput ? (float) (20.0 * Math.log10(Math.max(1.0e-7, values[i]))) : values[i];
{body_indent}    db = Math.max(minDb, Math.min(maxDb, db));
{body_indent}    float x = left + (float) ((Math.log10(hz) - logMin) / logRange) * (right - left);
{body_indent}    float y = top + (maxDb - db) / (maxDb - minDb) * (bottom - top);
{body_indent}    if (!started) {{ path.moveTo(x, y); started = true; }} else path.lineTo(x, y);
{body_indent}}}
{body_indent}paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * density); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(Color.rgb(62, 207, 188));
{body_indent}canvas.drawPath(path, paint);
{body_indent}paint.setStyle(Paint.Style.FILL); paint.setTextSize(11f * density); paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setColor(Color.rgb(62, 207, 188));
{body_indent}canvas.drawText("LIVE", left, 16f * density, paint);
{body_indent}paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)); paint.setColor(Color.rgb(165, 176, 191));
{body_indent}canvas.drawText("Dotakni se grafa za Hz in dBFS", left + 44f * density, 16f * density, paint);
{body_indent}float tx = liveTouchX;
{body_indent}if (tx >= left && tx <= right) {{
{body_indent}    float hz = (float) Math.pow(10.0, logMin + ((tx - left) / (right - left)) * logRange);
{body_indent}    int index;
{body_indent}    if (frequencies != null && frequencies.length == values.length) {{
{body_indent}        index = 0; float best = Float.MAX_VALUE;
{body_indent}        for (int i = 0; i < frequencies.length; i++) {{ float delta = Math.abs(frequencies[i] - hz); if (delta < best) {{ best = delta; index = i; }} }}
{body_indent}        hz = frequencies[index];
{body_indent}    }} else {{
{body_indent}        index = Math.max(0, Math.min(values.length - 1, Math.round(hz / nyquist * (values.length - 1))));
{body_indent}        hz = index * nyquist / Math.max(1, values.length - 1);
{body_indent}    }}
{body_indent}    float db = linearInput ? (float) (20.0 * Math.log10(Math.max(1.0e-7, values[index]))) : values[index];
{body_indent}    db = Math.max(minDb, Math.min(maxDb, db));
{body_indent}    float x = left + (float) ((Math.log10(Math.max(minHz, hz)) - logMin) / logRange) * (right - left);
{body_indent}    float y = top + (maxDb - db) / (maxDb - minDb) * (bottom - top);
{body_indent}    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1f * density); paint.setColor(Color.argb(210, 255, 208, 90));
{body_indent}    canvas.drawLine(x, top, x, bottom, paint); canvas.drawCircle(x, y, 5f * density, paint);
{body_indent}    String hzText = hz >= 1000f ? String.format(java.util.Locale.US, "%.2f kHz", hz / 1000f) : String.format(java.util.Locale.US, "%.0f Hz", hz);
{body_indent}    String tip = hzText + "   " + String.format(java.util.Locale.US, "%.1f dBFS", db);
{body_indent}    paint.setTextSize(12f * density); paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
{body_indent}    float tw = paint.measureText(tip) + 18f * density;
{body_indent}    float boxX = Math.min(Math.max(left, x - tw / 2f), right - tw);
{body_indent}    float boxY = Math.max(top + 25f * density, y - 24f * density);
{body_indent}    paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(235, 28, 35, 46));
{body_indent}    canvas.drawRoundRect(boxX, boxY - 18f * density, boxX + tw, boxY + 6f * density, 7f * density, 7f * density, paint);
{body_indent}    paint.setColor(Color.rgb(255, 221, 120)); canvas.drawText(tip, boxX + 9f * density, boxY, paint);
{body_indent}}}
{body_indent}return true;
{indent}}}
'''

text = text[:brace_start + 1] + "\n" + new_members + text[brace_start + 1:]

m2 = re.search(r'(?m)^(?P<indent>\s*)(?:private|protected|public)?\s*(?:static\s+)?(?:final\s+)?class\s+SpectrumView\s+extends\s+View\s*\{', text)
brace2 = text.find("{", m2.start(), m2.end() + 2)
depth = 0
end2 = None
for i in range(brace2, len(text)):
    if text[i] == "{": depth += 1
    elif text[i] == "}":
        depth -= 1
        if depth == 0:
            end2 = i
            break
segment = text[m2.start():end2 + 1]
om = re.search(r'(?m)^(?P<indent>\s*)(?:@Override\s*\n\s*)?protected\s+void\s+onDraw\s*\(\s*Canvas\s+(?P<canvas>\w+)\s*\)\s*\{', segment)
if not om:
    raise SystemExit("SpectrumView onDraw not found")
method_abs = m2.start() + om.start()
open_abs = text.find("{", method_abs, m2.start() + om.end() + 2)
canvas_name = om.group("canvas")
text = text[:open_abs + 1] + f"\n{om.group('indent')}    if (drawLiveGraph({canvas_name})) return;" + text[open_abs + 1:]

calls2 = list(re.finditer(r'\bspectrumView\.' + re.escape(setter_name) + r'\s*\((?P<args>.*?)\)\s*;', text, re.S))
if not calls2:
    raise SystemExit("Original spectrum setter call disappeared")
orig = calls2[0]
live_stmt = re.sub(r'\.' + re.escape(setter_name) + r'\s*\(', '.setLiveSpectrum(', orig.group(0), count=1)
prefix = text[:orig.start()]
if_matches = list(re.finditer(r'if\s*\((?P<cond>[^)]{0,300})\)\s*\{', prefix, re.S))
throttle = None
for im in reversed(if_matches):
    cond = im.group("cond").lower()
    if any(k in cond for k in ("lastui", "ui_update", "uiupdate", "update_interval", "systemclock", "elapsedrealtime")):
        throttle = im
        break
if throttle is None:
    for im in reversed(if_matches):
        if orig.start() - im.start() < 2500:
            throttle = im
            break
if throttle is None:
    raise SystemExit("UI throttle before spectrum setter not found")
line_start = text.rfind("\n", 0, throttle.start()) + 1
leading = re.match(r'[ \t]*', text[line_start:]).group(0)
insertion = leading + "// v0.3.2: raw FFT goes directly to the graph; text and warnings stay smoothed.\n" + leading + live_stmt.strip() + "\n"
text = text[:line_start] + insertion + text[line_start:]

java_path.write_text(text, encoding="utf-8")
print(f"Patched live graph using {setter_name}({params})")
