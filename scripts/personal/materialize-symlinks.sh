#!/usr/bin/env bash
# Windows without Developer Mode (or admin rights) cannot create symlinks, so git
# checks each tracked symlink out as a small text file holding the link target.
# The wear build then compiles "../../../../mobileSi/..." as Java and fails, and
# AGP crashes on a jniLibs "folder" that is really a file.
#
# This replaces each placeholder with a copy of its target, or removes it when
# the target does not exist (which is what a dangling symlink amounts to on
# Linux), and marks it skip-worktree so git neither shows nor commits the copy.
#
# Run it after every checkout, reset, pull or rebase that touches these paths:
#   scripts/personal/materialize-symlinks.sh
# Undo (for example before an operation git refuses because of these paths):
#   scripts/personal/materialize-symlinks.sh --restore
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

mapfile -t links < <(git ls-files -s | awk '$1 == "120000" { print $4 }')

if [ "${1:-}" = "--restore" ]; then
    for link in "${links[@]}"; do
        git update-index --no-skip-worktree -- "$link"
        rm -rf -- "$link"
        git checkout -- "$link"
        echo "restored $link"
    done
    exit 0
fi

# File targets first: a link to a directory (smallSi -> wearSi/) must copy the
# directory after the links inside it have been materialized.
materialize() {
    local link=$1 resolved=$2
    git update-index --skip-worktree -- "$link"
    rm -rf -- "$link"
    if [ -e "$resolved" ]; then
        cp -r -- "$resolved" "$link"
        echo "copied   $link <- $resolved"
    else
        echo "removed  $link (target missing, as a dangling link)"
    fi
}

deferred=()
for link in "${links[@]}"; do
    [ -L "$link" ] && continue # real symlink: nothing to do
    target=$(git cat-file -p "HEAD:$link")
    resolved="$(dirname "$link")/${target%/}"
    if [ -d "$resolved" ]; then
        deferred+=("$link" "$resolved")
    else
        materialize "$link" "$resolved"
    fi
done
for ((i = 0; i < ${#deferred[@]}; i += 2)); do
    materialize "${deferred[i]}" "${deferred[i + 1]}"
done
