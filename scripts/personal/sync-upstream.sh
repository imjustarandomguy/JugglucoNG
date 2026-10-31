#!/usr/bin/env bash
# Sync the fork on upstream (BUILD.md "Updating from upstream"), one resumable step at a time.
# Run it from the main checkout, never from $WT: the work happens in the worktree $WT (default
# C:/source/ng-wt-sync), whose files it switches, and bash reads a script while it runs.
# From a copy elsewhere, REPO= names the main checkout.
#
#   forecast       fetch upstream; per branch, what upstream changed in the files it touches
#                  and the files a merge with upstream/main would conflict in
#   start          tag backup/$DATE/* (personal, main, every branch); main = upstream/main
#   rebase         rebase every branch of branches.txt onto main as sync/<branch>, in order;
#                  stops at a conflict: resolve it in $WT (`keepboth` helps), then `continue`
#   continue       go on after resolving (also skips a commit the resolution left empty)
#   keepboth F...  resolve every conflict in F by keeping both sides, ours first
#   adopt          move each branch to its sync/ ref, then delete the sync/ refs
#   rebuild        rebuild/$DATE from main in $WT: merge the branches in order
#                  (rebuild-personal.sh), replay $OLD's personal code commits, carry its docs;
#                  stops where it needs a hand: fix, commit, run `rebuild` again
#   verify         diff against a plain merge of main into $OLD; duplicated strings
#   check          test.sh, both release builds, the APK signer
#
# $OLD is the personal being replaced (default: personal), $DATE the backup tag date
# (default: today). Nothing is pushed.
set -u
here=$(cd "$(dirname "$0")" && pwd)
repo=${REPO:-$(git -C "$here" rev-parse --show-toplevel)}
WT=${WT:-/c/source/ng-wt-sync}
[ "$(cd "$repo" && pwd -P)" = "$(cd "$WT" 2>/dev/null && pwd -P)" ] && { echo "run it from the main checkout, not from $WT"; exit 2; }
DATE=${DATE:-$(date +%F)}
OLD=${OLD:-personal}
B=backup/$DATE
list="$here/branches.txt"

entries() { tr -d '\r' < "$list" | grep -v -E '^\s*(#|$)'; }   # CRLF on a Windows checkout
branches() { entries | awk '{print $1}'; }
spec() { entries | grep -E "^$1( |$)"; }
parent_of() { spec "$1" | grep -o 'parent=[^ ]*' | cut -d= -f2; }
is_hub() { spec "$1" | grep -qw hub; }
state() { git -C "$WT" rev-parse --path-format=absolute --git-path sync-current; }
in_progress() {
  local g; g=$(git -C "$WT" rev-parse --git-dir)
  [ -d "$g/rebase-merge" ] || [ -d "$g/rebase-apply" ] || [ -f "$g/CHERRY_PICK_HEAD" ] ||
    [ -f "$g/MERGE_HEAD" ] || [ -d "$g/sequencer" ]
}
unmerged() { git -C "$WT" diff --name-only --diff-filter=U; }

forecast() {
  git -C "$repo" fetch -q upstream || exit 2
  local up=upstream/main
  echo "upstream: $(git -C "$repo" rev-list --count main..$up) new commits, $(git -C "$repo" diff --shortstat main $up)"
  for b in $(branches); do
    local base files over conf
    base=$(git -C "$repo" merge-base main "$b")
    files=$(git -C "$repo" diff --name-only "$base" "$b")
    over=$(cd "$repo" && [ -n "$files" ] && git log --format=%h main..$up -- $files | wc -l)
    conf=$(git -C "$repo" merge-tree --write-tree --name-only --no-messages $up "$b" | sed 1d | grep -v '^$' | sed 's#.*/##' | tr '\n' ' ')
    printf "%-34s %3s upstream commits touch its files; conflicts: %s\n" "$b" "${over:-0}" "${conf:-none}"
  done
}

start() {
  for b in personal main $(branches); do git -C "$repo" tag -f "$B/$b" "$b" >/dev/null; done
  git -C "$repo" fetch -q upstream && git -C "$repo" branch -f main upstream/main
  echo "tagged $B/*; main = $(git -C "$repo" rev-parse --short main)"
}

# Records sync/<b> once its rebase or recreation has finished.
done_branch() { git -C "$WT" branch -f "sync/$1" HEAD; rm -f "$(state)"; echo "OK    $1 ($(git -C "$WT" rev-list --count --no-merges main..HEAD) commits on main)"; }

old_hub_merge() { git -C "$WT" log --merges --first-parent --format=%H -1 "$B/main..$B/$1"; }
# A hub branch's own commits, picked on top of its new hub merge.
pick_own() {
  git -C "$WT" cherry-pick "$(old_hub_merge "$1")..$B/$1" >/dev/null 2>&1 ||
    { echo "STOP  $1: $(git -C "$WT" log -1 --format='%h %s' CHERRY_PICK_HEAD 2>/dev/null): $(unmerged | tr '\n' ' ')"; return 1; }
}

rebase_one() {
  local b=$1 p n op
  p=$(parent_of "$b"); n=main; op=$B/main
  if [ -n "$p" ]; then n=sync/$p; op=$B/$p; fi
  echo "$b" > "$(state)"
  if is_hub "$b"; then
    git -C "$WT" switch -q --detach "$n" || return 2
    git -C "$WT" merge --no-ff -q -m "Merge branch 'feat/current-reading-hub' into $b" sync/feat/current-reading-hub >/dev/null 2>&1 ||
      { echo "STOP  $b: the hub merge: $(unmerged | tr '\n' ' ')"; return 1; }
    pick_own "$b" || return 1
  else
    git -C "$WT" switch -q --detach "$B/$b" || return 2
    git -C "$WT" rebase -q --onto "$n" "$op" >/dev/null 2>&1 ||
      { echo "STOP  $b: $(git -C "$WT" log -1 --format='%h %s' REBASE_HEAD 2>/dev/null): $(unmerged | tr '\n' ' ')"; return 1; }
  fi
  done_branch "$b"
}

rebase_all() {
  if in_progress; then echo "a rebase/cherry-pick/merge is in progress in $WT: resolve it, then run continue"; exit 1; fi
  local skipped=""
  for b in $(branches); do
    git -C "$WT" rev-parse -q --verify "refs/heads/sync/$b" >/dev/null && continue
    # SKIP="<branch> ...": left to be rebased elsewhere (it, and what is stacked on it)
    local p; p=$(parent_of "$b")
    if [[ " ${SKIP:-} $skipped " == *" $b "* || ( -n "$p" && " ${SKIP:-} $skipped " == *" $p "* ) ]]; then
      skipped="$skipped $b"; echo "SKIP  $b"; continue
    fi
    rebase_one "$b" || exit 1
  done
  [ -n "$skipped" ] && { echo "rebased all but:$skipped (make their sync/ refs, then run rebase again)"; exit 0; }
  echo "all branches rebased; review, then run adopt"
}

continue_() {
  local b g; b=$(cat "$(state)" 2>/dev/null) || { echo "nothing in progress"; exit 1; }
  g=$(git -C "$WT" rev-parse --git-dir)
  [ -n "$(unmerged)" ] && { echo "still unmerged: $(unmerged | tr '\n' ' ')"; exit 1; }
  while in_progress; do
    if git -C "$WT" diff --cached --quiet && git -C "$WT" diff --quiet; then
      # the resolution left the commit empty (upstream already has it): drop it
      if [ -d "$g/rebase-merge" ]; then git -C "$WT" rebase --skip >/dev/null 2>&1
      elif [ -f "$g/CHERRY_PICK_HEAD" ]; then git -C "$WT" cherry-pick --skip >/dev/null 2>&1
      else git -C "$WT" commit -q --no-edit; fi
    elif [ -d "$g/rebase-merge" ]; then GIT_EDITOR=true git -C "$WT" rebase --continue >/dev/null 2>&1
    elif [ -f "$g/CHERRY_PICK_HEAD" ] || [ -d "$g/sequencer" ]; then GIT_EDITOR=true git -C "$WT" cherry-pick --continue >/dev/null 2>&1
    else git -C "$WT" commit -q --no-edit; fi
    if [ -n "$(unmerged)" ]; then echo "STOP  $b again: $(unmerged | tr '\n' ' ')"; exit 1; fi
  done
  if [ "$b" != rebuild ]; then
    # a hub merge that needed a hand: its own commits are still to come
    if is_hub "$b" && [ "$(git -C "$WT" rev-list --parents -n1 HEAD | wc -w)" -eq 3 ]; then pick_own "$b" || exit 1; fi
    done_branch "$b"
  fi
  case "$b" in rebuild) rebuild ;; *) rebase_all ;; esac
}

keepboth() {
  for f in "$@"; do
    sed -i -e '/^<<<<<<< /d' -e '/^=======$/d' -e '/^>>>>>>> /d' "$WT/$f"
    git -C "$WT" add -- "$f"
  done
}

adopt() {
  for b in $(branches); do
    git -C "$repo" rev-parse -q --verify "refs/heads/sync/$b" >/dev/null || { echo "missing sync/$b"; exit 1; }
  done
  for b in $(branches); do git -C "$repo" branch -f "$b" "sync/$b" && git -C "$repo" branch -D -q "sync/$b"; done
  echo "branches moved; old tips in $B/*"
}

rebuild() {
  local r=rebuild/$DATE
  if ! git -C "$WT" rev-parse -q --verify "refs/heads/$r" >/dev/null; then
    in_progress && { echo "resolve what is in progress in $WT first"; exit 1; }
    git -C "$WT" switch -q -c "$r" main || exit 2
  elif [ "$(git -C "$WT" rev-parse --abbrev-ref HEAD)" != "$r" ]; then
    git -C "$WT" switch -q "$r" || exit 2
  fi
  echo rebuild > "$(state)"
  local out rc
  out=$(BACKUP=$B bash "$here/rebuild-personal.sh" "$WT" "$OLD" $(branches)); rc=$?
  echo "$out" | grep -v '^have'
  [ $rc = 0 ] || { echo "      then run: $0 continue (it commits the merge and goes on)"; exit 1; }
  # $OLD's own commits on top of its merges, except the docs-only ones (carried below)
  local c done_list; done_list=$(git -C "$WT" rev-parse --path-format=absolute --git-path sync-replayed)
  touch "$done_list"
  for c in $(git -C "$repo" log --reverse --no-merges --first-parent --format=%H "$B/main..$OLD"); do
    grep -qx "$c" "$done_list" && continue
    if [ -z "$(git -C "$repo" diff-tree --no-commit-id --name-only -r "$c" -- . ':!docs/personal' ':!scripts/personal' ':!CLAUDE.md')" ]; then
      echo "$c" >> "$done_list"; continue
    fi
    if ! git -C "$WT" cherry-pick "$c" >/dev/null 2>&1; then
      if [ -n "$(unmerged)" ]; then echo "STOP  replay $(git -C "$repo" log -1 --format='%h %s' "$c"): $(unmerged | tr '\n' ' ')"; echo "$c" >> "$done_list"; exit 1; fi
      git -C "$WT" cherry-pick --skip >/dev/null 2>&1; echo "EMPTY $(git -C "$repo" log -1 --format='%h %s' "$c")"
    else
      echo "REPLAY $(git -C "$WT" log -1 --format='%h %s')"
    fi
    echo "$c" >> "$done_list"
  done
  if ! git -C "$WT" diff --quiet "$OLD" HEAD -- docs/personal scripts/personal CLAUDE.md; then
    git -C "$WT" checkout "$OLD" -- docs/personal scripts/personal CLAUDE.md &&
      git -C "$WT" commit -q -m "Personal: guide, notes and helper scripts" -m "Carried over from $(git -C "$repo" rev-parse --short "$OLD")." &&
      echo "DOCS  carried over from $OLD"
  fi
  rm -f "$(state)" "$done_list"
  echo "rebuilt: $(git -C "$WT" log --oneline -1)"
}

verify() {
  local r=rebuild/$DATE t f
  t=$(git -C "$repo" merge-tree --write-tree --no-messages "$OLD" main | head -1)
  echo "differences from a plain merge of main into $OLD (docs aside):"
  git -C "$repo" diff --name-only "$t" "$r" -- . ':!docs/personal' ':!scripts/personal' ':!CLAUDE.md' |
  while read -r f; do
    local mk; mk=$(git -C "$repo" show "$t:$f" 2>/dev/null | grep -c '^<<<<<<<')
    printf "  %-9s %s %s\n" "$([ "$mk" -gt 0 ] && echo conflict || echo CHECK)" "$f" "$(git -C "$repo" diff --shortstat "$t" "$r" -- "$f" | sed 's/ 1 file changed,//')"
  done
  echo "  (conflict = the plain merge conflicts there; CHECK = explain it: a dropped branch, an adaptation)"
  for f in $(git -C "$repo" ls-tree -r --name-only "$r" Common/src/main/res | grep 'values[^/]*/strings.xml$'); do
    git -C "$repo" show "$r:$f" | grep -v '<!--' | grep -o 'name="[^"]*"' | sort | uniq -d | sed "s#^#  duplicated in $f: #"
  done
}

check() {
  local log; log=$(git -C "$WT" rev-parse --path-format=absolute --git-path sync-check); mkdir -p "$log"
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  (cd "$WT" && bash "$repo/scripts/personal/test.sh" > "$log/test.log" 2>&1); echo "test.sh exit $?: $(grep -E '^tests:' "$log/test.log")"
  (cd "$WT" && ./gradlew :Common:assembleMobileRelease -PjugglucoAbi=arm64-v8a > "$log/phone.log" 2>&1); echo "phone release exit $?"
  (cd "$WT" && ./gradlew :Common:assembleWearRelease -PjugglucoAbi=armeabi-v7a > "$log/wear.log" 2>&1); echo "wear release exit $?"
  local as; as=$(ls -d "$LOCALAPPDATA"/Android/Sdk/build-tools/*/ | sort -V | tail -1)
  for a in "$WT"/Common/build/outputs/apk/{mobile,wear}/release/*.apk; do
    echo "$(basename "$a"): $(PATH="$JAVA_HOME/bin:$PATH" "$as/apksigner.bat" verify --print-certs "$a" 2>/dev/null | grep -m1 'SHA-256' | sed 's/.*: //')"
  done
  echo "logs: $log"
}

cmd=${1:-}; shift || true
case "$cmd" in
  forecast) forecast ;;
  start) start ;;
  rebase) rebase_all ;;
  continue) continue_ ;;
  keepboth) keepboth "$@" ;;
  adopt) adopt ;;
  rebuild) rebuild ;;
  verify) verify ;;
  check) check ;;
  *) sed -n '2,24p' "$0"; exit 2 ;;
esac
