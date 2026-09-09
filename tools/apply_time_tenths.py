from pathlib import Path

ROOT=Path('app/src/main/java/com/seungjae/jangsu280battery')
def rep(path,old,new,count=1):
    p=Path(path);s=p.read_text();actual=s.count(old)
    assert actual==count,(path,actual,count,old[:120])
    p.write_text(s.replace(old,new))

rep(ROOT/'RaceProtocol.kt', '''fun formatRaceTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L); val minutes = safe / 60000; val sec = (safe % 60000) / 1000; val milli = safe % 1000
    return if (minutes > 0) "%d:%02d.%03d".format(minutes, sec, milli) else "%d.%03d".format(sec, milli)
}''', '''fun formatRaceTime(ms: Long): String {
    // TimeGate keeps millisecond precision internally, but every user-visible race time is
    // rounded to the nearest 0.1 second so phone/server/spectator displays use one rule.
    val rounded = ((ms.coerceAtLeast(0L) + 50L) / 100L) * 100L
    val minutes = rounded / 60_000L
    val seconds = (rounded % 60_000L) / 1_000L
    val tenth = (rounded % 1_000L) / 100L
    return if (minutes > 0) "%d:%02d.%d".format(minutes, seconds, tenth) else "%d.%d".format(seconds, tenth)
}''')

rep(ROOT/'RaceLiveLapDisplayInstaller.kt', '''    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minute = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val tenth = (safe % 1000) / 100
        return if (minute > 0) "%d:%02d.%d".format(minute, seconds, tenth) else "%d.%d".format(seconds, tenth)
    }''', '''    private fun formatTime(ms: Long): String = formatRaceTime(ms)''')

rep(ROOT/'RaceLapHistoryActivity.kt', 'opt.addView(cell("0.000", true, BLUE, dp(96)))', 'opt.addView(cell("0.0", true, BLUE, dp(96)))')
rep(ROOT/'RaceLapHistoryActivity.kt', '''    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val milli = safe % 1000
        return if (minutes > 0) "%d:%02d.%03d".format(minutes, seconds, milli) else "%d.%03d".format(seconds, milli)
    }''', '''    private fun formatTime(ms: Long): String = formatRaceTime(ms)''')

Path('VERSION.txt').write_text('0.34.59\n')
