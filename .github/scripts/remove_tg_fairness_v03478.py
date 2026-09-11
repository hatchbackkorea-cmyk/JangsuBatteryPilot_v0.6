from pathlib import Path
import re

ROOT = Path('.')
PKG = ROOT / 'app/src/main/java/com/seungjae/jangsu280battery'


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, text):
    Path(path).write_text(text, encoding='utf-8')


def replace_required(path, old, new):
    p = Path(path)
    text = read(p)
    if old not in text:
        if new in text:
            return
        raise SystemExit(f'missing expected pattern in {path}: {old[:120]!r}')
    write(p, text.replace(old, new))


write(PKG / 'RaceTimingPendingStore.kt', '''package com.seungjae.jangsu280battery

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

object RaceTimingPendingStore {
    private fun pending(context: Context) = AtomicFile(
        File(context.filesDir, "race/pending_finish.json").also { it.parentFile?.mkdirs() }
    )

    fun save(context: Context, data: JSONObject) {
        val file = pending(context)
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun read(context: Context): JSONObject? = runCatching {
        JSONObject(String(pending(context).readFully(), Charsets.UTF_8))
    }.getOrNull()

    fun clear(context: Context) = pending(context).delete()

    fun markFinalized(context: Context, runId: String) {
        context.getSharedPreferences("race_lap_display", Context.MODE_PRIVATE).edit()
            .putString("run", runId)
            .putLong("at", android.os.SystemClock.elapsedRealtime())
            .apply()
    }

    fun justFinalized(context: Context): String? {
        val prefs = context.getSharedPreferences("race_lap_display", Context.MODE_PRIVATE)
        val age = android.os.SystemClock.elapsedRealtime() - prefs.getLong("at", 0L)
        return if (age in 0L..1500L) prefs.getString("run", null) else null
    }
}
''')

write(PKG / 'RaceTimingEvidence.kt', '''package com.seungjae.jangsu280battery

import org.json.JSONArray
import org.json.JSONObject

object RaceTimingEvidence {
    fun audit(
        start: JSONObject?,
        finish: JSONObject?,
        originalStart: Long,
        originalFinish: Long,
        runReasons: List<String>
    ): JSONObject = JSONObject().apply {
        put("algorithm", GateTimingMath.ALGORITHM)
        put("evidence_version", "TIMING-EVIDENCE-1")
        put("start", start ?: JSONObject.NULL)
        put("finish", finish ?: JSONObject.NULL)
        put("validation_reasons", JSONArray(runReasons.distinct()))
        put("original_started_at_ms", originalStart)
        put("original_finished_at_ms", originalFinish)
        put("original_elapsed_ms", (originalFinish - originalStart).coerceAtLeast(0L))
        put("app_version", BuildConfig.VERSION_NAME)
    }
}
''')

timing = PKG / 'RaceTimingService.kt'
s = read(timing)
s = s.replace('RaceFairTiming.readPending(this)', 'RaceTimingPendingStore.read(this)')
s = s.replace('RaceFairTiming.savePending(this,', 'RaceTimingPendingStore.save(this,')
s = s.replace('RaceFairTiming.clearPending(this)', 'RaceTimingPendingStore.clear(this)')
s = s.replace('RaceFairTiming.markFinalized(this, runId)', 'RaceTimingPendingStore.markFinalized(this, runId)')
s = s.replace(
    'val timingAudit = RaceFairTiming.audit(cfg, startAudit, refined?.audit, preliminaryStartAt, preliminaryFinishAt, reasons)',
    'val timingAudit = RaceTimingEvidence.audit(startAudit, refined?.audit, preliminaryStartAt, preliminaryFinishAt, reasons)'
)
s = s.replace(
    '                put("state","RUNNING");put("route_m",startGate.routeM);put("fair_policy",runCatching{JSONObject(cfg.fairPolicyJson)}.getOrDefault(JSONObject()))',
    '                put("state","RUNNING");put("route_m",startGate.routeM)'
)
old_status = '''            append(if (timingAudit.optString("quality") == "ACCEPTED") " · 계측기준 충족(시범)" else " · 계측 기준 미달")
            if (timingUncertaintyMs == null) append(" · 여유폭 판단 불가")
            else append(" · 판정여유폭 ±").append(timingUncertaintyMs).append("ms(잠정)")'''
new_status = '''            append(if (validation == "VALID") " · 계측 기준 충족" else " · 계측 기준 미달")
            if (timingUncertaintyMs != null) append(" · 추정 불확실성 ±").append(timingUncertaintyMs).append("ms")'''
if old_status in s:
    s = s.replace(old_status, new_status)
elif new_status not in s:
    raise SystemExit('finish status block not found')
for forbidden in ('RaceFairTiming', 'fair_policy', 'fairPolicyJson'):
    if forbidden in s:
        raise SystemExit(f'TG fairness reference remains in RaceTimingService.kt: {forbidden}')
write(timing, s)

for name in ('RaceAutoLapDiscoveryService.kt', 'RaceLiveLapDisplayInstaller.kt'):
    p = PKG / name
    x = read(p)
    x = x.replace('RaceFairTiming.readPending(this)', 'RaceTimingPendingStore.read(this)')
    x = x.replace('RaceFairTiming.justFinalized(activity)', 'RaceTimingPendingStore.justFinalized(activity)')
    if 'RaceFairTiming' in x:
        raise SystemExit(f'TG fairness reference remains in {name}')
    write(p, x)

protocol = PKG / 'RaceProtocol.kt'
s = read(protocol)
s = s.replace(',\n    val leaderElapsedMs: Long? = null,\n    val fairPolicyJson: String = ""\n)', ',\n    val leaderElapsedMs: Long? = null\n)')
s = s.replace('        if (fairPolicyJson.isNotBlank()) put("fair_policy", JSONObject(fairPolicyJson))\n', '')
s = s.replace(',\n                fairPolicyJson = o.optJSONObject("fair_policy")?.toString().orEmpty()\n            )', '\n            )')
s = s.replace(
    'Raw milliseconds are preserved for timing evidence and TG공정성 verification; every rider/admin/monitor',
    'Raw milliseconds are preserved for timing evidence; every rider/admin/monitor'
)
for forbidden in ('fairPolicyJson', 'fair_policy', 'TG공정성'):
    if forbidden in s:
        raise SystemExit(f'TG fairness protocol field remains: {forbidden}')
write(protocol, s)

for rel in (
    'app/src/main/java/com/seungjae/jangsu280battery/RaceFairTiming.kt',
    'docs/TG-FAIR-1.md',
):
    Path(rel).unlink(missing_ok=True)

old_wf = Path('.github/workflows/fair-timing-tests.yml')
new_wf = Path('.github/workflows/timing-accuracy-tests.yml')
if old_wf.exists():
    old_wf.rename(new_wf)
if new_wf.exists():
    t = read(new_wf)
    t = t.replace('Fair timing regression tests', 'Timing accuracy regression tests')
    t = t.replace("'.github/workflows/fair-timing-tests.yml'", "'.github/workflows/timing-accuracy-tests.yml'")
    t = t.replace('fair-timing-unit-tests', 'timing-accuracy-unit-tests')
    write(new_wf, t)

home = PKG / 'TimeGateHomeView.kt'
h = read(home)
h = h.replace('"기록측정"', '"LAP TIMER"')
h = h.replace('"정확한 기록을 측정하세요"', '"어제보다 오늘이 더 빠르게!"')
if '"LAP TIMER", "어제보다 오늘이 더 빠르게!"' not in h:
    raise SystemExit('home LAP TIMER copy not present')
write(home, h)

vp = Path('VERSION.txt')
cur = vp.read_text(encoding='utf-8').strip()
m = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', cur)
if not m:
    raise SystemExit(f'bad VERSION: {cur}')
a, b, c = map(int, m.groups())
if (a, b, c) < (0, 34, 77):
    raise SystemExit(f'refuse downgrade/old base: {cur}')
target = f'{a}.{b}.{c + 1}'
vp.write_text(target + '\n', encoding='utf-8')
print('ANDROID_TARGET_VERSION=' + target)

for base in (PKG, Path('docs')):
    if not base.exists():
        continue
    for p in base.rglob('*'):
        if not p.is_file() or p.suffix not in {'.kt', '.md'}:
            continue
        text = read(p)
        for token in ('RaceFairTiming', 'fairPolicyJson', 'fair_policy', 'TG-FAIR', 'TG공정성'):
            if token in text:
                raise SystemExit(f'active TG fairness leftover: {p}: {token}')

for required in (
    PKG / 'GateTimingMath.kt',
    PKG / 'RaceTimingRefiner.kt',
    PKG / 'RaceSensorFusion.kt',
):
    if not required.exists():
        raise SystemExit(f'missing timing accuracy module: {required}')
