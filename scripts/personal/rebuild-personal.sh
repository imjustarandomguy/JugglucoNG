#!/usr/bin/env bash
# Rebuild personal from main: merge branches in order; resolve a conflicted file with the
# old personal's version only when every merged branch touching it still has the same
# version of that file as its backup tag and main has not changed it since $BACKUP/main
# (so the old resolution is still the right one).
# Usage (from BUILD.md "Updating", step 2), in a worktree on a fresh branch from main:
#   BACKUP=backup/<date> scripts/personal/rebuild-personal.sh <worktree> <old-personal-ref> \
#       <branch>[=<its old ref>] ...
# A branch's old ref defaults to $BACKUP/<branch> (the tags step 0 makes). Stops at the
# first conflict it cannot resolve this way, leaving the merge in progress: resolve it by
# hand, commit, and run the same command again (branches already merged are skipped).
set -u
BACKUP=${BACKUP:-backup/$(date +%F)}
wt=$1; old=$2; shift 2
cd "$wt" || exit 2
merged=()   # entries "branch=oldref"
for spec in "$@"; do
  b=${spec%%=*}; o=${spec#*=}; [ "$o" = "$spec" ] && o="$BACKUP/$b"
  # resumable: a branch already in HEAD only joins the list
  if git merge-base --is-ancestor "$b" HEAD; then echo "have  $b"; merged+=("$b=$o"); continue; fi
  if git merge --no-ff -q -m "Merge branch '$b' into personal" "$b" >/dev/null 2>&1; then
    echo "OK    $b"; merged+=("$b=$o"); continue
  fi
  files=$(git diff --name-only --diff-filter=U)
  manual=""
  for f in $files; do
    ok=1
    # upstream changed f since the old base: the old resolution would revert that
    [ "$(git rev-parse -q --verify "main:$f" 2>/dev/null)" = "$(git rev-parse -q --verify "$BACKUP/main:$f" 2>/dev/null)" ] || ok=0
    for m in "${merged[@]}" "$b=$o"; do
      [ $ok = 1 ] || break
      mb=${m%%=*}; mo=${m#*=}
      # does this branch touch f at all?
      git diff --quiet "main...$mb" -- "$f" 2>/dev/null && continue
      if ! git rev-parse -q --verify "$mo^{commit}" >/dev/null; then ok=0; break; fi
      nb=$(git rev-parse -q --verify "$mb:$f" 2>/dev/null); ob=$(git rev-parse -q --verify "$mo:$f" 2>/dev/null)
      [ "$nb" = "$ob" ] || { ok=0; break; }
    done
    if [ $ok = 1 ] && git cat-file -e "$old:$f" 2>/dev/null; then
      git checkout "$old" -- "$f" && git add -- "$f"
    else
      manual="$manual $f"
    fi
  done
  if [ -n "$manual" ]; then
    echo "STOP  $b: resolve by hand:$manual"
    echo "      (auto-resolved: $(echo $files | wc -w) conflicted in total)"
    exit 1
  fi
  git commit -q --no-edit && echo "RES   $b: $(echo $files | tr '\n' ' ')"
  merged+=("$b=$o")
done
echo "done: $(git log --oneline -1)"
