#!/usr/bin/env bash
# Fail when any test was skipped (spec 001, AC-001-14).
# A skipped test must never count as a green build.
# Reads surefire/failsafe XML reports, prints totals, and appends a table to
# $GITHUB_STEP_SUMMARY when that variable is set.
set -u

shopt -s nullglob
files=(target/surefire-reports/TEST-*.xml target/failsafe-reports/TEST-*.xml)

if [ ${#files[@]} -eq 0 ]; then
  echo "ERROR: no test reports found in target/surefire-reports or target/failsafe-reports." >&2
  echo "Run './mvnw verify' first." >&2
  exit 1
fi

total=0
failed=0
skipped=0
skipped_classes=()

for f in "${files[@]}"; do
  # Attributes on the root <testsuite ...> element (may span several lines).
  header=$(tr '\n' ' ' < "$f" | grep -o '<testsuite [^>]*>' | head -n 1)
  attr() { printf '%s' "$header" | grep -o " $1=\"[0-9]*\"" | head -n 1 | grep -o '[0-9]*'; }
  t=$(attr tests);    t=${t:-0}
  fl=$(attr failures); fl=${fl:-0}
  er=$(attr errors);   er=${er:-0}
  sk=$(attr skipped);  sk=${sk:-0}
  total=$((total + t))
  failed=$((failed + fl + er))
  skipped=$((skipped + sk))
  if [ "$sk" -gt 0 ]; then
    name=$(printf '%s' "$header" | grep -o ' name="[^"]*"' | head -n 1 | sed 's/^ name="//; s/"$//')
    skipped_classes+=("${name:-$f} ($sk)")
  fi
done

echo "Tests run: $total, failed: $failed, skipped: $skipped"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Test results"
    echo ""
    echo "| Run | Failed | Skipped |"
    echo "|---|---|---|"
    echo "| $total | $failed | $skipped |"
    if [ "$skipped" -gt 0 ]; then
      echo ""
      echo "Skipped classes:"
      for c in "${skipped_classes[@]}"; do echo "- \`$c\`"; done
    fi
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [ "$skipped" -gt 0 ]; then
  echo "ERROR: $skipped test(s) were skipped. Skipped classes:" >&2
  for c in "${skipped_classes[@]}"; do echo "  - $c" >&2; done
  exit 1
fi

echo "OK: no skipped tests."
