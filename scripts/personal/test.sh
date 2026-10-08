#!/usr/bin/env bash
# Runs the unit tests on this Windows machine and reports only what is new.
#
# About 70 tests always fail here for reasons of the machine, not the code (JDK 25
# vs Robolectric, no symlink privilege, Windows paths and line endings, no
# Python/WSL, file locks). They pass on Linux. This script hides them, so a run
# answers one question: did my change break anything?
#
#   bash /c/source/JugglucoNG/scripts/personal/test.sh   from any worktree: tests that worktree
#   scripts/personal/test.sh                      both variants, every test
#   scripts/personal/test.sh mobile               phone variant only (also: wear)
#   scripts/personal/test.sh --tests '*Journal*'  a filter, passed to Gradle (repeatable)
#   scripts/personal/test.sh --no-run             re-read the last run's results
#   scripts/personal/test.sh --update-baseline    rewrite the baseline from this run
#
# A failure is "known" when it is listed in test-baseline.txt, or when its message
# matches one of the machine signatures below (so a new test in a Robolectric class
# is not reported as broken: it cannot run here at all). Everything else is NEW.
#
# Exit code: 0 no new failures, 1 new failures, 2 the build itself failed
# (compile error: no test ran). The Gradle output goes to build/personal-test.log.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
# The worktree it is run from (topic branches lack this script: run it by its path in
# the main checkout); the baseline always comes from next to the script.
root="$(git rev-parse --show-toplevel 2>/dev/null)"
[ -n "$root" ] && [ -d "$root/Common" ] || root="$(cd "$here/../.." && pwd)"
baseline="$here/test-baseline.txt"
log="$root/build/personal-test.log"
results="$root/Common/build/test-results"

variants=()
filters=()
run=1
update=0
while [ $# -gt 0 ]; do
    case "$1" in
        mobile|wear) variants+=("$1") ;;
        both) variants+=(mobile wear) ;;
        --tests) shift; filters+=(--tests "$1") ;;
        --no-run) run=0 ;;
        --update-baseline) update=1 ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done
[ ${#variants[@]} -eq 0 ] && variants=(mobile wear)

taskdir() { [ "$1" = mobile ] && echo testMobileDebugUnitTest || echo testWearDebugUnitTest; }

status=0
if [ $run -eq 1 ]; then
    : "${JAVA_HOME:=/c/Program Files/Android/Android Studio/jbr}"
    export JAVA_HOME
    # A fresh worktree has no local.properties (SDK path; git-ignored): take the main checkout's.
    main_props="$(cd "$here/../.." && pwd)/local.properties"
    [ -f "$root/local.properties" ] || { [ -f "$main_props" ] && cp "$main_props" "$root/"; }
    # Gradle applies --tests to the task right before it, so each task gets its own copy.
    args=()
    for v in "${variants[@]}"; do
        rm -rf "$results/$(taskdir "$v")"
        args+=(":Common:$(taskdir "$v")" "${filters[@]}")
    done
    mkdir -p "$(dirname "$log")"
    echo "running ${args[*]} (log: build/personal-test.log)"
    (cd "$root" && ./gradlew "${args[@]}" --continue -q) >"$log" 2>&1
    status=$?
fi

# One awk pass over every result file: "variant class#test<TAB>message".
failures="$(mktemp)"; counts="$(mktemp)"; entries="$(mktemp)"
trap 'rm -f "$failures" "$counts" "$entries"' EXIT
files=()
for v in "${variants[@]}"; do
    d="$results/$(taskdir "$v")"
    [ -d "$d" ] && for f in "$d"/TEST-*.xml; do [ -e "$f" ] && files+=("$f"); done
done
if [ ${#files[@]} -eq 0 ]; then
    echo "no test results: the build failed before any test ran. Last lines of the log:"
    tail -n 40 "$log" 2>/dev/null
    exit 2
fi
awk -v counts="$counts" '
    FNR == 1 { v = (FILENAME ~ /testWear/) ? "wear" : "mobile" }
    /<testcase / {
        total++
        match($0, /name="[^"]*"/); n = substr($0, RSTART + 6, RLENGTH - 7)
        match($0, /classname="[^"]*"/); c = substr($0, RSTART + 11, RLENGTH - 12)
    }
    /<(failure|error) / {
        m = ""
        if (match($0, /message="[^"]*/)) m = substr($0, RSTART + 9, RLENGTH - 9)
        gsub(/\t/, " ", m); gsub(/&#10;/, " ", m); gsub(/&quot;/, "\"", m); gsub(/&lt;/, "<", m); gsub(/&gt;/, ">", m); gsub(/&amp;/, "\\&", m)
        print v " " c "#" n "\t" m
    }
    END { print total > counts }
' "${files[@]}" | sort -u >"$failures"
total=$(cat "$counts")

# Messages that only this machine produces. Keep each pattern specific.
signatures='Unsupported class file major version 69|A required privilege is not held by the client|Python was not found|arch-metrics\.sh exited non-zero|This driver is configured to open a database named|: \[src\\(mobile|main|wear)\\|AccessDeniedException: C:\\Users\\[^\\]+\\AppData\\Local\\Temp\\|being used by another process'

# The baseline's entries without their "   # reason" comments.
[ -f "$baseline" ] && grep -v '^#' "$baseline" | sed -E 's/[[:space:]]+#[[:space:]].*$//; s/[[:space:]]+$//' | grep -v '^$' >"$entries"
declare -A listed=()
while IFS= read -r e; do listed["$e"]=1; done <"$entries"

known=0; machine=0; new=0
newlist=""
declare -A failed=()
while IFS=$'\t' read -r id msg; do
    [ -z "$id" ] && continue
    failed["$id"]=1
    if [ -n "${listed[$id]:-}" ] || [ -n "${listed[${id%%#*}#*]:-}" ]; then
        known=$((known + 1))
    elif [[ "$msg" =~ $signatures ]]; then
        machine=$((machine + 1))
    else
        new=$((new + 1))
        newlist+="  $id"$'\n'"      ${msg:0:200}"$'\n'
    fi
done <"$failures"

# Baseline entries whose class ran and that did not fail: candidates to drop.
fixed=""
while IFS= read -r e; do
    v="${e%% *}"; [[ " ${variants[*]} " == *" $v "* ]] || continue
    [[ "$e" == *'#*' ]] && continue
    [ -n "${failed[$e]:-}" ] && continue
    cls="${e#* }"; cls="${cls%%#*}"
    [ -f "$results/$(taskdir "$v")/TEST-$cls.xml" ] && fixed+="  $e"$'\n'
done <"$entries"

if [ $update -eq 1 ]; then
    {
        echo "# Unit tests that fail on this Windows machine only. One per line:"
        echo "#   <mobile|wear> <class>#<test>   # reason"
        echo "# '<class>#*' covers a whole class. Rewritten by: scripts/personal/test.sh --update-baseline"
        while IFS=$'\t' read -r id msg; do
            reason="?"
            case "$msg" in
                *"major version 69"*) reason="JDK 25: Robolectric cannot read the classes" ;;
                *"required privilege"*) reason="no symlink privilege (Developer Mode off)" ;;
                *"Python was not found"*) reason="no Python" ;;
                *"arch-metrics.sh"*) reason="no WSL for arch-metrics.sh" ;;
                *"database named"*) reason="Room migration test: Windows path in the database name" ;;
                *'[src\'*) reason="architecture gate: Windows path separators" ;;
                *"another process"*|*AccessDenied*) reason="Windows file lock on a temp file" ;;
            esac
            old=$(grep -F -- "$id " "$baseline" 2>/dev/null | head -n 1 | sed -nE 's/^.*[[:space:]]+#[[:space:]]+//p')
            [ -n "$old" ] && [ "$old" != "?" ] && reason="$old"
            echo "$id   # $reason"
        done <"$failures"
    } >"$baseline.tmp" && mv "$baseline.tmp" "$baseline"
    echo "baseline rewritten: $(grep -vc '^#' "$baseline") entries"
fi

echo "tests: $total   failed: $((known + machine + new))   known: $known   machine-only: $machine   NEW: $new"
[ -n "$fixed" ] && printf 'baseline entries that passed this run (drop them with --update-baseline):\n%s' "$fixed"
if [ $new -gt 0 ]; then
    printf 'NEW failures:\n%s' "$newlist"
    echo "details: Common/build/reports/tests/<task>/index.html, or the TEST-<class>.xml under Common/build/test-results"
    exit 1
fi
# A filter that matches nothing in one variant fails that task; that alone is fine.
# A task that failed for another reason (compile error, resource merge...) is not.
if [ $status -ne 0 ] && grep -E "Execution failed for task" "$log" | grep -vqE "Test(Mobile|Wear)DebugUnitTest'|test(Mobile|Wear)DebugUnitTest'"; then
    echo "the build failed. Last lines of the log:"
    tail -n 40 "$log"
    exit 2
fi
if [ $status -ne 0 ] && [ $((known + machine)) -eq 0 ] && ! grep -q "No tests found for given includes" "$log"; then
    echo "Gradle failed without a failing test. Last lines of the log:"
    tail -n 40 "$log"
    exit 2
fi
echo "OK: nothing new failed"
exit 0
