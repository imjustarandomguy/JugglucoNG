# Personal fork of JugglucoNG

This checkout is the owner's personal fork (`imjustarandomguy/JugglucoNG`) of
`ctqvva/JugglucoNG`. The `personal` branch is `main` plus a merge of every fix
and feature the owner uses. It exists only for testing on the owner's devices,
and the phone and watch APKs are built from it.

- To set up a machine, build, sign, install, or update from upstream, follow
  [docs/personal/BUILD.md](docs/personal/BUILD.md).
- Open work, verified results and design notes:
  [docs/personal/NEXT.md](docs/personal/NEXT.md).

## How every change is made

Each fix or feature must stay sendable upstream on its own, so:

1. **Own branch from `main`.** `fix/<topic>` or `feat/<topic>`, created from
   `main` (the upstream mirror), never from `personal`. One topic per branch.
2. **Upstream-ready content only.** The branch holds the change and its tests,
   written in upstream's style. Nothing personal goes on it: no
   `docs/personal/`, no `CLAUDE.md` edits, no device names or serials, no
   temporary diagnostics.
3. **Merge into `personal`** with `git merge --no-ff <branch>`, then add the
   branch to the table in BUILD.md ("Current personal branches").
4. **Personal-only commits** (docs, notes, build helpers under
   `scripts/personal/`) go straight on `personal`, prefixed `Personal:`.
5. **Batch the testing.** Do several branches, merge them all, then one build
   and one test round on the devices. Stop early only when something needs the
   owner's decision or a live device test.
6. **Temporary diagnostics** (forced logging, `debuggable` builds) stay
   uncommitted in the working tree (`git stash` them around branch switches),
   and a clean build replaces them on the devices afterwards.
7. **Push only to `origin`** (the fork), and only when the owner asks. Upstream
   pushes are disabled; pull requests to upstream are the owner's call.

Other rules:
- Commits use the per-repo personal GitHub identity (see BUILD.md); never change
  the global git config.
- Never commit signing keys or passwords. The signing properties live in the
  user-level `~/.gradle/gradle.properties`.
- On this Windows machine, tracked symlinks are materialized by
  `scripts/personal/materialize-symlinks.sh`; see BUILD.md before checkouts
  that touch them.
- **Run unit tests only through `scripts/personal/test.sh`** (from a topic
  worktree: `bash /c/source/JugglucoNG/scripts/personal/test.sh`, which tests
  the worktree you are in; `mobile`/`wear` and `--tests <pattern>` narrow it).
  About 70 tests always fail on this Windows machine (JDK 25 vs Robolectric,
  Windows paths, CRLF, no Python/WSL, symlink rights); they are listed in
  `scripts/personal/test-baseline.txt` and pass on Linux. The script hides them
  and prints only NEW failures (exit 0 = nothing new broke, 1 = new failures,
  2 = the build failed). Do not investigate, re-run on a base commit, or work
  around baseline failures; report the script's summary line.
- **Stage explicit paths** (`git add <file>...`), never `git add -A` or
  `git add .` under `Common/src`: they stage the materialized copies (the
  `Common/src/smallSi` directory replacing its symlink). If that happens,
  unstage with `git reset -- Common/src/smallSi`, then restore the flag with
  `git update-index --skip-worktree Common/src/smallSi`.
