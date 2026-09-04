#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Integration tests for pao-maven-plugin.
#
# The unit tests cover the version logic; these cover the parts only a real
# Maven invocation exercises: parameter binding, reactor collection from the
# session, and the git operations against an actual repository.
#
# Usage: bash it/run-integration-tests.sh
# ============================================================================

PLUGIN_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN_VERSION=$(cd "$PLUGIN_DIR" && mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout)
GOAL="com.jardoapps:pao-maven-plugin:${PLUGIN_VERSION}:apply"

PASSED=0
FAILED=0
FAILED_BEFORE=0

log() {
    echo "[IT] $*"
}

fail() {
    echo "[IT]   FAILED: $*" >&2
    FAILED=$((FAILED + 1))
}

# Each test function brackets itself with these, so a test whose assertions failed
# is not also counted as passed - which made the summary report the same test in
# both columns.
begin_test() {
    log "$*"
    FAILED_BEFORE=$FAILED
}

end_test() {
    [[ "$FAILED" -eq "$FAILED_BEFORE" ]] && PASSED=$((PASSED + 1))
    return 0
}

assert_version() {
    local file="$1" expected="$2" description="$3"
    local actual
    actual=$(grep -o '<version>[^<]*</version>' "$file" | head -1 | sed 's|</\?version>||g')
    if [[ "$actual" == "$expected" ]]; then
        log "  ok: $description"
    else
        fail "$description (expected '$expected', got '$actual')"
    fi
}

# Creates a two-module project in a fresh git repository.
setup_repo() {
    local dir="$1" version="$2"
    mkdir -p "$dir/core"
    cat > "$dir/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>my-parent</artifactId>
    <version>${version}</version>
    <packaging>pom</packaging>
    <modules>
        <module>core</module>
    </modules>
</project>
EOF
    cat > "$dir/core/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>my-parent</artifactId>
        <version>${version}</version>
    </parent>
    <artifactId>core</artifactId>
</project>
EOF
    git -C "$dir" init -q .
    git -C "$dir" config user.email "it@example.com"
    git -C "$dir" config user.name "it"
    git -C "$dir" add -A
    git -C "$dir" commit -qm "Initial commit"
}

run_goal() {
    local dir="$1"
    shift
    (cd "$dir" && mvn -B -q "$GOAL" -Dpao.pushChanges=false "$@")
}

# --- Test: the whole feature-branch / core-branch round trip ----------------

test_round_trip() {
    begin_test "round trip across a multi-module reactor"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.10-SNAPSHOT"

    run_goal "$dir" -Dpao.branchName=feature/FEA-123
    assert_version "$dir/pom.xml" "1.2.10-feature-FEA-123-SNAPSHOT" "aggregator gained the branch version"
    assert_version "$dir/core/pom.xml" "1.2.10-feature-FEA-123-SNAPSHOT" "module parent reference followed"

    # The reactor must still resolve, which is what breaks if parent references
    # are left behind.
    if (cd "$dir" && mvn -B -q validate > /dev/null 2>&1); then
        log "  ok: reactor still resolves"
    else
        fail "reactor does not resolve after enforcing the branch version"
    fi

    run_goal "$dir" -Dpao.branchName=main
    assert_version "$dir/pom.xml" "1.2.10-SNAPSHOT" "aggregator version restored on a core branch"
    assert_version "$dir/core/pom.xml" "1.2.10-SNAPSHOT" "module parent reference restored"

    local commits
    commits=$(git -C "$dir" log --oneline | wc -l | tr -d ' ')
    if [[ "$commits" == "3" ]]; then
        log "  ok: one commit per change"
    else
        fail "expected 3 commits, found $commits"
    fi

    end_test
}

# --- Test: a second run on a core branch changes nothing --------------------

test_idempotent() {
    begin_test "re-running on a core branch makes no further commits"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    run_goal "$dir" -Dpao.branchName=main

    local commits
    commits=$(git -C "$dir" log --oneline | wc -l | tr -d ' ')
    if [[ "$commits" == "1" ]]; then
        log "  ok: nothing committed"
    else
        fail "expected no new commit, found $((commits - 1))"
    fi

    end_test
}

# --- Test: the branch name is taken from the CI environment -----------------

test_branch_from_environment() {
    begin_test "branch name detected from the CI environment"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    (cd "$dir" && GITHUB_REF_NAME=feature/from-env mvn -B -q "$GOAL" -Dpao.pushChanges=false)
    assert_version "$dir/pom.xml" "1.2.3-feature-from-env-SNAPSHOT" "branch read from GITHUB_REF_NAME"

    end_test
}

# --- Test: an invalid pin fails the build -----------------------------------

test_invalid_pin_fails() {
    begin_test "an invalid pin fails the build"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    echo "feature/f1  project-version  vf1" > "$dir/.prevent-overwrites.conf"

    if run_goal "$dir" -Dpao.branchName=feature/f1 > /dev/null 2>&1; then
        fail "expected a non-zero exit for an invalid pin"
    else
        log "  ok: build failed as expected"
    fi

    end_test
}

# --- Test: the commit covers the poms and nothing else ----------------------

test_commit_scope() {
    begin_test "unrelated working tree changes stay out of the commit"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"

    # Stand-ins for what an earlier pipeline step, or a developer, might leave
    # behind: one modification to a tracked file and one untracked file.
    echo "scratch" >> "$dir/core/pom.xml.bak"
    git -C "$dir" add "$dir/core/pom.xml.bak"
    git -C "$dir" commit -qm "Add a tracked file"
    echo "touched by something else" >> "$dir/core/pom.xml.bak"
    echo "untracked" > "$dir/untracked.txt"

    run_goal "$dir" -Dpao.branchName=feature/FEA-123

    local committed
    committed=$(git -C "$dir" show --name-only --format= HEAD | sort | tr '\n' ' ')
    if [[ "$committed" == "core/pom.xml pom.xml " ]]; then
        log "  ok: only the poms were committed"
    else
        fail "expected only the poms in the commit, got '$committed'"
    fi

    if git -C "$dir" status --porcelain | grep -q 'core/pom.xml.bak'; then
        log "  ok: the unrelated modification is still uncommitted"
    else
        fail "the unrelated modification was swept into the commit"
    fi

    end_test
}

# --- Test: the run leaves no git identity behind ----------------------------

test_leaves_no_git_config() {
    begin_test "the run does not write a git identity into the repository"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    git -C "$dir" config --unset user.name
    git -C "$dir" config --unset user.email

    run_goal "$dir" -Dpao.branchName=feature/FEA-123 -Dpao.gitUserName=pao-bot \
        -Dpao.gitUserEmail=pao-bot@example.com

    local author
    author=$(git -C "$dir" log -1 --format='%an <%ae>')
    if [[ "$author" == "pao-bot <pao-bot@example.com>" ]]; then
        log "  ok: the commit carries the configured identity"
    else
        fail "expected the configured identity on the commit, got '$author'"
    fi

    if git -C "$dir" config --local --get user.name > /dev/null 2>&1; then
        fail "a local user.name was left behind in .git/config"
    else
        log "  ok: nothing left behind in .git/config"
    fi

    # A core branch with nothing to strip must not touch git at all, which is what
    # made the goal die outside a working copy.
    local plain
    plain=$(mktemp -d)
    trap 'rm -rf "$dir" "$plain"' RETURN
    mkdir -p "$plain/core"
    cp "$dir/pom.xml" "$plain/pom.xml"
    cp "$dir/core/pom.xml" "$plain/core/pom.xml"
    sed -i 's|-feature-FEA-123-SNAPSHOT|-SNAPSHOT|g' "$plain/pom.xml" "$plain/core/pom.xml"
    if run_goal "$plain" -Dpao.branchName=main > /dev/null 2>&1; then
        log "  ok: a no-op run succeeds outside a git working copy"
    else
        fail "a no-op run failed outside a git working copy"
    fi

    end_test
}

# --- Test: pao.skip short-circuits ------------------------------------------

test_skip() {
    begin_test "pao.skip leaves the project alone"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    run_goal "$dir" -Dpao.branchName=feature/FEA-123 -Dpao.skip=true
    assert_version "$dir/pom.xml" "1.2.3-SNAPSHOT" "version untouched"

    end_test
}

log "Using goal: $GOAL"
log "Installing the plugin into the local repository..."
(cd "$PLUGIN_DIR" && mvn -q -B install -DskipTests)

test_round_trip
test_idempotent
test_branch_from_environment
test_invalid_pin_fails
test_commit_scope
test_leaves_no_git_config
test_skip

echo ""
echo "================================================================"
echo "Integration tests: $PASSED passed, $FAILED failed"
echo "================================================================"

[[ "$FAILED" -eq 0 ]]
