from pathlib import Path
import re

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')

def replace_once(path, old, new):
    s = read(path)
    if old not in s:
        raise SystemExit(f'missing expected text in {path}: {old[:100]!r}')
    s = s.replace(old, new, 1)
    write(path, s)

def regex_once(path, pattern, repl, flags=0):
    s = read(path)
    out, n = re.subn(pattern, repl, s, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f'expected one regex match in {path}, got {n}: {pattern}')
    write(path, out)

# 1) Completed records are valid solely from completion evidence; DNF remains explicit.
p='app/src/main/java/com/seungjae/jangsu280battery/RaceProtocol.kt'
s=read(p)
s=s.replace('o.optLong("elapsed_ms"), o.optString("status", "INVALID"),','o.optLong("elapsed_ms"), o.optString("status", ""),')
marker='''/**\n * Canonical TimeGate visible-record precision.'''
helper='''/** Completion validity is intentionally independent of CP/quality diagnostics. */\nfun normalizeRaceCompletionStatus(status: String, startedAtMs: Long, finishedAtMs: Long, elapsedMs: Long): String {\n    val current = status.trim().uppercase()\n    if (current == "DNF") return "DNF"\n    if (startedAtMs > 0L && finishedAtMs > startedAtMs) return "VALID"\n    if (finishedAtMs > 0L && elapsedMs > 0L) return "VALID"\n    if (startedAtMs > 0L && finishedAtMs <= 0L) return "DNF"\n    return current\n}\n\n'''
if helper.strip() not in s:
    if marker not in s: raise SystemExit('RaceProtocol insertion marker missing')
    s=s.replace(marker, helper+marker, 1)
write(p,s)

p='app/src/main/java/com/seungjae/jangsu280battery/RaceDataStore.kt'
replace_once(p, 'val jumpCount: Int = 0, val validation: String = "INVALID",', 'val jumpCount: Int = 0, val validation: String = "",')
old='''    fun completed(): List<RaceRunSummary> { val a = runCatching { JSONArray(completedFile.readText(Charsets.UTF_8)) }.getOrDefault(JSONArray()); return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> runCatching { RaceRunSummary.fromJson(o) }.getOrNull() } } }'''
new='''    @Synchronized\n    fun completed(): List<RaceRunSummary> {\n        val a = runCatching { JSONArray(completedFile.readText(Charsets.UTF_8)) }.getOrDefault(JSONArray())\n        val parsed = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> runCatching { RaceRunSummary.fromJson(o) }.getOrNull() } }\n        val normalized = parsed.map { run ->\n            val normalizedStatus = normalizeRaceCompletionStatus(run.status, run.startedAtMs, run.finishedAtMs, run.elapsedMs)\n            if (normalizedStatus == run.status) run else run.copy(status = normalizedStatus)\n        }\n        if (normalized != parsed) {\n            val out = JSONArray().apply { normalized.forEach { put(it.toJson()) } }\n            val tmp = File(dir, "completed_runs.tmp")\n            tmp.writeText(out.toString(), Charsets.UTF_8)\n            tmp.copyTo(completedFile, overwrite = true)\n            tmp.delete()\n        }\n        return normalized\n    }'''
replace_once(p,old,new)

# 2) Keep measurement diagnostics, but they never decide validity.
p='app/src/main/java/com/seungjae/jangsu280battery/RaceTimingService.kt'
replace_once(p,
'''        val validation = validationStatus()\n        val reasons = validationReasons()\n        val timingAudit = RaceTimingEvidence.audit(startAudit, refined?.audit, preliminaryStartAt, preliminaryFinishAt, reasons)''',
'''        val validation = "VALID"\n        val measurementNotes = measurementNotes()\n        val timingAudit = RaceTimingEvidence.audit(startAudit, refined?.audit, preliminaryStartAt, preliminaryFinishAt, measurementNotes)''')
replace_once(p,
'''                put("validation_reason", reasons.joinToString(","))\n                put("validation_reasons", JSONArray().apply { reasons.forEach { put(it) } })''',
'''                put("measurement_notes", JSONArray().apply { measurementNotes.forEach { put(it) } })''')
replace_once(p,
'''            append(if (validation == "VALID") " · 계측 기준 충족" else " · 계측 기준 미달")''',
'''            append(" · 계측 완료")''')
replace_once(p, '    private fun validationReasons(): List<String> = buildList {', '    private fun measurementNotes(): List<String> = buildList {')
regex_once(p, r'''\n    private fun validationStatus\(\): String = when \{.*?\n    \}\n''', '\n', flags=re.S)
replace_once(p, '                validation = validationStatus(),', '                validation = if (state == "FINISHED") "VALID" else "",')
if 'validationStatus()' in read(p): raise SystemExit('validationStatus call remains')

p='app/src/main/java/com/seungjae/jangsu280battery/RaceTimingEvidence.kt'
s=read(p)
s=s.replace('runReasons: List<String>', 'measurementNotes: List<String>')
s=s.replace('put("validation_reasons", JSONArray(runReasons.distinct()))', 'put("measurement_notes", JSONArray(measurementNotes.distinct()))')
write(p,s)

# 3) CP detail is analysis only: no record-status column or VALID/INVALID/REVIEW presentation.
p='app/src/main/java/com/seungjae/jangsu280battery/RaceLapHistoryActivity.kt'
replace_once(p,
'                "기존 REVIEW/DNF/INVALID는 기록표에는 남지만 BEST/OPTIMAL 계산에서는 제외합니다."',
'                "완주 기록은 BEST/OPTIMAL 계산에 포함하고 DNF/미완주만 제외합니다."')
replace_once(p,
'            text = "각 CP 구간별 최속은 파랑, 최저속은 빨강으로 표시합니다. 기존 REVIEW/DNF/INVALID 구간은 비교에서 제외합니다."',
'            text = "CP는 구간 분석용입니다. 각 구간별 최속은 파랑, 최저속은 빨강으로 표시합니다."')
replace_once(p, '        header.addView(cell("상태", true, TEXT, dp(84)))\n', '')
replace_once(p, '            row.addView(cell(run.status, false, if (excluded) RED else SECONDARY, dp(84)))\n', '')
replace_once(p, '            row.addView(cell("이론상", true, BLUE, dp(84)))\n', '')

# 4) Unit contract for old REVIEW/INVALID normalization and explicit DNF preservation.
test='''package com.seungjae.jangsu280battery\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass RaceCompletionPolicyTest {\n    @Test fun startFinishAlwaysValidRegardlessOfOldStatus() {\n        assertEquals("VALID", normalizeRaceCompletionStatus("REVIEW", 1000, 5000, 4000))\n        assertEquals("VALID", normalizeRaceCompletionStatus("INVALID", 1000, 5000, 4000))\n    }\n\n    @Test fun explicitDnfAlwaysStaysDnf() {\n        assertEquals("DNF", normalizeRaceCompletionStatus("DNF", 1000, 5000, 4000))\n    }\n\n    @Test fun legacyCompletedElapsedWithoutRawStartCanBeValid() {\n        assertEquals("VALID", normalizeRaceCompletionStatus("INVALID", 0, 5000, 4000))\n    }\n\n    @Test fun startedWithoutFinishIsDnf() {\n        assertEquals("DNF", normalizeRaceCompletionStatus("INVALID", 1000, 0, 2000))\n    }\n}\n'''
write('app/src/test/java/com/seungjae/jangsu280battery/RaceCompletionPolicyTest.kt', test)

# 5) Keep accuracy regression tests, remove fairness naming/hooks.
oldwf=ROOT/'.github/workflows/fair-timing-tests.yml'
if oldwf.exists(): oldwf.unlink()
write('.github/workflows/timing-accuracy-tests.yml', '''name: Timing accuracy regression tests\non:\n  push:\n    branches: [main]\n    paths: ['app/src/main/java/com/seungjae/jangsu280battery/GateTimingMath.kt', 'app/src/main/java/com/seungjae/jangsu280battery/RaceTiming*.kt', 'app/src/test/**', '.github/workflows/timing-accuracy-tests.yml']\n  workflow_dispatch:\npermissions:\n  contents: read\njobs:\n  test:\n    runs-on: ubuntu-latest\n    timeout-minutes: 15\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '17'\n      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: '8.9'\n      - run: gradle --no-daemon :app:testDebugUnitTest\n      - uses: actions/upload-artifact@v4\n        if: always()\n        with:\n          name: timing-accuracy-unit-tests\n          path: app/build/reports/tests/\n''')
for dead in ['.github/workflows/timegate-remove-tg-fairness-v03478.yml', '.timegate-simple-validity-v03479', '.github/workflows/timegate-simple-validity-v03479.yml', '.github/scripts/apply_simple_validity_v03479.py']:
    q=ROOT/dead
    if q.exists(): q.unlink()

# 6) One patch version up; never downgrade.
vpath=ROOT/'VERSION.txt'
version=vpath.read_text(encoding='utf-8').strip()
parts=version.split('.')
if len(parts)!=3 or not all(x.isdigit() for x in parts): raise SystemExit(f'unexpected version: {version}')
major,minor,patch=map(int,parts)
if (major,minor,patch) < (0,34,78): raise SystemExit(f'refusing downgrade baseline: {version}')
target=f'{major}.{minor}.{patch+1}'
if target != '0.34.79': raise SystemExit(f'expected 0.34.79 from current repository, got {target}')
vpath.write_text(target+'\n',encoding='utf-8')

# Final policy guard: no active TG fairness policy tokens; REVIEW may exist nowhere in active main source.
active='\n'.join(p.read_text(encoding='utf-8',errors='ignore') for p in (ROOT/'app/src/main').rglob('*') if p.is_file())
for token in ('race_tg_fairness','TG-FAIR','tg_fair','TG공정성'):
    if token in active: raise SystemExit(f'active TG fairness token remains: {token}')
cp=read('app/src/main/java/com/seungjae/jangsu280battery/RaceLapHistoryActivity.kt')
for token in ('cell("상태"','cell(run.status','REVIEW/','/INVALID'):
    if token in cp: raise SystemExit(f'CP/detail status presentation remains: {token}')
print('Android simple validity patch prepared:', target)
