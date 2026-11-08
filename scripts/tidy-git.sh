#!/usr/bin/env bash
# Tidies what Claude Code sessions leave behind: worktrees under .claude/worktrees/ and claude/* branches.
# Run from Git Bash in the main checkout, with no Claude Code session open on this repo.
#   bash scripts/tidy-git.sh           # dry run: says what it would do
#   bash scripts/tidy-git.sh --apply   # does it
# Removes a worktree only if it has no uncommitted or untracked files and its commit is on main, a
# feature/* branch or a tag. Deletes a claude/* branch only if its commit is on one of those too.
# Never touches main, feature/* branches or tags.
set -uo pipefail
apply=0
[ "${1:-}" = "--apply" ] && apply=1
cd "$(git rev-parse --show-toplevel)" || exit 1
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ;;
    *) if git worktree list | grep -Eq '^[A-Za-z]:/'; then
           echo "These worktrees live on Windows. Run this from Git Bash on that PC."; exit 1
       fi ;;
esac

kept_refs=$(git for-each-ref --format='%(refname)' refs/heads/main refs/heads/feature/ refs/tags/)
is_kept() { # $1 = commit; true if main, a feature/* branch or a tag contains it
    local ref
    for ref in $kept_refs; do git merge-base --is-ancestor "$1" "$ref" 2>/dev/null && return 0; done
    return 1
}
act() { if [ "$apply" = 1 ]; then echo "+ $*"; "$@"; else echo "would run: $*"; fi; }

echo "== Worktrees"
path=""; head=""
while IFS= read -r line; do
    case "$line" in
        "worktree "*) path="${line#worktree }"; head="" ;;
        "HEAD "*) head="${line#HEAD }" ;;
        "")
            case "$path" in
                */.claude/worktrees/*)
                    name="${path##*/}"
                    if [ ! -d "$path" ]; then
                        echo "$name: folder already gone (prune clears it)"
                    elif [ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]; then
                        echo "$name: KEPT, has uncommitted or untracked files:"
                        git -C "$path" status --short | head -10
                    elif ! is_kept "$head"; then
                        echo "$name: KEPT, its commit ${head:0:7} is on no main/feature/tag ref"
                    else
                        act git worktree remove "$path" || echo "$name: couldn't remove it. Files in use? Run ./gradlew --stop, close Android Studio windows on it, try again."
                    fi ;;
            esac
            path="" ;;
    esac
done < <(git worktree list --porcelain; echo)
act git worktree prune

echo "== claude/* branches"
for branch in $(git for-each-ref --format='%(refname:short)' refs/heads/claude/); do
    sha=$(git rev-parse --short "$branch")
    if git worktree list --porcelain | grep -qx "branch refs/heads/$branch"; then
        echo "$branch ($sha): KEPT, still checked out in a worktree"
    elif is_kept "$branch"; then
        act git branch -D "$branch"   # safe: its commit is on a kept ref (sha $sha)
    else
        echo "$branch ($sha): KEPT, has commits on no main/feature/tag ref. Look before deleting."
    fi
done

echo "== feature/* branches already in main (delete by hand when the round is closed)"
current=$(git symbolic-ref --short -q HEAD || true)
for branch in $(git for-each-ref --format='%(refname:short)' refs/heads/feature/); do
    [ "$branch" = "$current" ] && continue
    git merge-base --is-ancestor "$branch" main && echo "$branch: in main  ->  git branch -d $branch"
done
[ "$apply" = 1 ] || echo "(dry run: nothing changed. Run with --apply to do it.)"
