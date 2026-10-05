#!/usr/bin/env bash
# 报告 leanback 与 mobile 两个 flavor 之间「同名 Java 文件」的行数差异。
# 目的：上游同步时，若某文件只在其中一个 flavor 改了，这里能让人一眼看到分叉。
# 非阻塞：只输出报告，始终 exit 0。
set -uo pipefail

ROOT="${1:-app/src}"
LB="$ROOT/leanback/java"
MB="$ROOT/mobile/java"

if [ ! -d "$LB" ] || [ ! -d "$MB" ]; then
  echo "skip: source sets not found ($LB / $MB)"
  exit 0
fi

echo "=== leanback ∩ mobile Java drift report (|line delta| > 0) ==="
count=0
max_delta=0
while IFS= read -r f; do
  rel="${f#"$LB"/}"
  mb="$MB/$rel"
  [ -f "$mb" ] || continue
  la=$(wc -l < "$f")
  ma=$(wc -l < "$mb")
  delta=$((la - ma))
  ad=${delta#-}
  if [ "$ad" -gt 0 ]; then
    printf '%-72s leanback=%5s mobile=%5s delta=%+6s\n' "$rel" "$la" "$ma" "$delta"
    count=$((count + 1))
    [ "$ad" -gt "$max_delta" ] && max_delta=$ad
  fi
done < <(find "$LB" -name '*.java')

echo "=== $count paired files differ; largest line delta = $max_delta ==="
echo "(informational only; this check never fails the workflow)"
