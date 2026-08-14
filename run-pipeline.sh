#!/usr/bin/env bash
#
# Runs the same stages as Jenkinsfile, locally, on this machine.
#
# Usage:
#   ./run-pipeline.sh              CI build: build + test only (mirrors a normal push)
#   ./run-pipeline.sh 1.3.7        Release build: bump version, tag, push, deploy, GitHub release
#
# Requires for a release build: GITHUB_TOKEN exported (needs "repo" scope, or "Contents: write" +
# "write:packages" on a fine-grained PAT -- "repo" alone covers both), and a working `git push` to
# origin (SSH key or stored credentials) since git push here uses your normal git auth rather than
# embedding the token in the remote URL. Also requires the `gh` CLI (used to attach the built jars
# to the GitHub release).

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

RELEASE_VERSION="${1:-}"
REPO_SLUG="linoalessio/database-driver-v2"

# --- JDK 21 is mandatory: Lombok's annotation processor breaks on newer JDKs here, and both
# modules' maven-compiler-plugin hardcodes <source>21</source><target>21</target>, which rules
# out 17 too. See JENKINS.md.
JDK21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [ -z "$JDK21_HOME" ]; then
    echo "No JDK 21 found via 'java_home -v 21'. Install one (e.g. 'brew install openjdk@21')." >&2
    exit 1
fi
export JAVA_HOME="$JDK21_HOME"
echo "Using JAVA_HOME=$JAVA_HOME"

if [ -n "$RELEASE_VERSION" ]; then

    # --- Stage: Validate release request ---
    if ! echo "$RELEASE_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        echo "Version must look like X.Y.Z (got: $RELEASE_VERSION)" >&2
        exit 1
    fi
    branch="$(git rev-parse --abbrev-ref HEAD)"
    if [ "$branch" != "master" ]; then
        echo "Releases can only be cut from master (currently on: $branch)" >&2
        exit 1
    fi
    if git rev-parse "v$RELEASE_VERSION" >/dev/null 2>&1; then
        echo "Tag v$RELEASE_VERSION already exists" >&2
        exit 1
    fi
    if [ -n "$(git status --porcelain)" ]; then
        echo "Working tree is not clean; commit or stash before cutting a release." >&2
        exit 1
    fi
    if [ -z "${GITHUB_TOKEN:-}" ]; then
        echo "GITHUB_TOKEN must be exported (needs 'repo' and 'write:packages' scope)." >&2
        exit 1
    fi
    if ! command -v gh >/dev/null 2>&1; then
        echo "gh CLI not found; required to attach release assets." >&2
        exit 1
    fi

    # --- Stage: Bump version ---
    mvn -B -ntp versions:set -DnewVersion="$RELEASE_VERSION"

    mkdir -p pomBackUps/database-driver-api pomBackUps/database-driver-plugin
    for backup in pom.xml.versionsBackup database-driver-api/pom.xml.versionsBackup database-driver-plugin/pom.xml.versionsBackup; do
        [ -f "$backup" ] && mv "$backup" "pomBackUps/$backup"
    done

    RELEASE_VERSION="$RELEASE_VERSION" python3 - <<'PYEOF'
import os, re

version = os.environ["RELEASE_VERSION"]

with open("README.md", encoding="utf-8") as f:
    content = f.read()

content = re.sub(r"(Version-)\d+\.\d+\.\d+(-blue)", rf"\g<1>{version}\g<2>", content)
content = re.sub(r"`\d+\.\d+\.\d+`\)\. `database-driver-api`", f"`{version}`). `database-driver-api`", content)

with open("README.md", "w", encoding="utf-8") as f:
    f.write(content)
PYEOF

fi

# --- Stage: Build & Test ---
mvn -B -ntp clean verify

if [ -n "$RELEASE_VERSION" ]; then

    # --- Stage: Commit, tag & push release ---
    git add pom.xml database-driver-api/pom.xml database-driver-plugin/pom.xml README.md
    git commit -m "Bump version to $RELEASE_VERSION"
    git tag -a "v$RELEASE_VERSION" -m "Release v$RELEASE_VERSION"
    git push origin HEAD:master
    git push origin "v$RELEASE_VERSION"

    # --- Stage: Deploy to GitHub Packages ---
    # Relies on ~/.m2/settings.xml having a <server id="github"> entry with
    # <password>${env.GITHUB_TOKEN}</password>.
    mvn -B -ntp -DskipTests clean deploy

    # --- Stage: Publish GitHub release ---
    # Attaches each module's main jar, sources jar and javadoc jar (built by the deploy step
    # above) as release assets, in addition to the GitHub Packages deployment.
    assets=()
    for module in database-driver-api database-driver-plugin; do
        for suffix in "" -sources -javadoc; do
            jar="$module/target/$module-$RELEASE_VERSION$suffix.jar"
            if [ ! -f "$jar" ]; then
                echo "Expected build artifact missing: $jar" >&2
                exit 1
            fi
            assets+=("$jar")
        done
    done

    GH_TOKEN="$GITHUB_TOKEN" gh release create "v$RELEASE_VERSION" "${assets[@]}" \
        --repo "$REPO_SLUG" \
        --title "v$RELEASE_VERSION" \
        --generate-notes

    echo "Released v$RELEASE_VERSION."
else
    echo "CI build passed."
fi
