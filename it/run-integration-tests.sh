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

log() {
    echo "[IT] $*"
}

fail() {
    echo "[IT]   FAILED: $*" >&2
    FAILED=$((FAILED + 1))
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
    log "round trip across a multi-module reactor"
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

    PASSED=$((PASSED + 1))
}

# --- Test: a second run on a core branch changes nothing --------------------

test_idempotent() {
    log "re-running on a core branch makes no further commits"
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

    PASSED=$((PASSED + 1))
}

# --- Test: the branch name is taken from the CI environment -----------------

test_branch_from_environment() {
    log "branch name detected from the CI environment"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    (cd "$dir" && GITHUB_REF_NAME=feature/from-env mvn -B -q "$GOAL" -Dpao.pushChanges=false)
    assert_version "$dir/pom.xml" "1.2.3-feature-from-env-SNAPSHOT" "branch read from GITHUB_REF_NAME"

    PASSED=$((PASSED + 1))
}

# --- Test: an invalid pin fails the build -----------------------------------

test_invalid_pin_fails() {
    log "an invalid pin fails the build"
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

    PASSED=$((PASSED + 1))
}

# --- Test: pao.skip short-circuits ------------------------------------------

test_skip() {
    log "pao.skip leaves the project alone"
    local dir
    dir=$(mktemp -d)
    trap 'rm -rf "$dir"' RETURN

    setup_repo "$dir" "1.2.3-SNAPSHOT"
    run_goal "$dir" -Dpao.branchName=feature/FEA-123 -Dpao.skip=true
    assert_version "$dir/pom.xml" "1.2.3-SNAPSHOT" "version untouched"

    PASSED=$((PASSED + 1))
}

log "Using goal: $GOAL"
log "Installing the plugin into the local repository..."
(cd "$PLUGIN_DIR" && mvn -q -B install -DskipTests)

test_round_trip
test_idempotent
test_branch_from_environment
test_invalid_pin_fails
test_skip

echo ""
echo "================================================================"
echo "Integration tests: $PASSED passed, $FAILED failed"
echo "================================================================"

[[ "$FAILED" -eq 0 ]]
