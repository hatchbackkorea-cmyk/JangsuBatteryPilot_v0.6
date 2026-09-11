from pathlib import Path

src = Path('.github/scripts/apply_simple_validity_v03479.py')
s = src.read_text(encoding='utf-8')
s = s.replace(
    "'                \"기존 REVIEW/DNF/INVALID는 기록표에는 남지만 BEST/OPTIMAL 계산에서는 제외합니다.\"'",
    "'            text = \"기존 REVIEW/DNF/INVALID는 기록표에는 남지만 BEST/OPTIMAL 계산에서는 제외합니다.\"'",
)
s = s.replace(
    "'                \"완주 기록은 BEST/OPTIMAL 계산에 포함하고 DNF/미완주만 제외합니다.\"'",
    "'            text = \"완주 기록은 BEST/OPTIMAL 계산에 포함하고 DNF/미완주만 제외합니다.\"'",
)
if 'text = \"기존 REVIEW/DNF/INVALID' not in s:
    raise SystemExit('failed to patch lap-history source matcher')
code = compile(s, str(src), 'exec')
exec(code, {'__name__': '__main__'})
Path(__file__).unlink(missing_ok=True)
