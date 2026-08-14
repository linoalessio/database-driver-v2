pipeline {
    agent any

    tools {
        // Configure under Manage Jenkins > Tools. Must be JDK 21: Lombok's annotation
        // processor does not run under newer JDKs on this project, and both modules'
        // maven-compiler-plugin is hardcoded to <source>21</source><target>21</target>,
        // which rules out JDK 17 too.
        jdk 'jdk21'
        maven 'maven3'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
    }

    parameters {
        string(
            name: 'RELEASE_VERSION',
            defaultValue: '1.3.6',
            description: 'Set to e.g. 1.3.6 to cut a release from this build: bumps the version, tags, pushes, deploys to GitHub Packages and publishes a GitHub release. Leave empty for a normal CI build (build + test only).'
        )
    }

    environment {
        RELEASE_VERSION = "${params.RELEASE_VERSION.trim()}"
        REPO_SLUG       = 'linoalessio/database-driver-v2'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Validate release request') {
            when { expression { return env.RELEASE_VERSION } }
            steps {
                sh '''
                    if ! echo "$RELEASE_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
                        echo "RELEASE_VERSION must look like X.Y.Z (got: $RELEASE_VERSION)" >&2
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
                '''
            }
        }

        stage('Bump version') {
            when { expression { return env.RELEASE_VERSION } }
            steps {
                sh '''
                    mvn -B -ntp versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false

                    python3 - <<'PYEOF'
import os, re

version = os.environ["RELEASE_VERSION"]

with open("README.md", encoding="utf-8") as f:
    content = f.read()

content = re.sub(r"(Version-)\d+\.\d+\.\d+(-blue)", rf"\g<1>{version}\g<2>", content)
content = re.sub(r"`\d+\.\d+\.\d+`\)\. `database-driver-api`", f"`{version}`). `database-driver-api`", content)

with open("README.md", "w", encoding="utf-8") as f:
    f.write(content)
PYEOF
                '''
            }
        }

        stage('Build & Test') {
            steps {
                sh 'mvn -B -ntp clean verify'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Commit, tag & push release') {
            when { expression { return env.RELEASE_VERSION } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                    sh '''
                        git config user.name "Jenkins"
                        git config user.email "jenkins@ci.local"
                        git add pom.xml database-driver-api/pom.xml database-driver-plugin/pom.xml README.md
                        git commit -m "Bump version to $RELEASE_VERSION"
                        git tag -a "v$RELEASE_VERSION" -m "Release v$RELEASE_VERSION"
                        git push "https://${GH_USER}:${GH_TOKEN}@github.com/${REPO_SLUG}.git" HEAD:master
                        git push "https://${GH_USER}:${GH_TOKEN}@github.com/${REPO_SLUG}.git" "v$RELEASE_VERSION"
                    '''
                }
            }
        }

        stage('Deploy to GitHub Packages') {
            when { expression { return env.RELEASE_VERSION } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                    sh '''
                        mkdir -p .ci
                        cat > .ci/settings.xml <<SETTINGS
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${GH_USER}</username>
      <password>${GH_TOKEN}</password>
    </server>
  </servers>
</settings>
SETTINGS
                        mvn -B -ntp -s .ci/settings.xml -DskipTests clean deploy
                        rm -rf .ci
                    '''
                }
            }
        }

        stage('Publish GitHub release') {
            when { expression { return env.RELEASE_VERSION } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                    sh '''
                        curl -sSL -f -X POST \
                          -H "Authorization: Bearer ${GH_TOKEN}" \
                          -H "Accept: application/vnd.github+json" \
                          "https://api.github.com/repos/${REPO_SLUG}/releases" \
                          -d "{\\"tag_name\\":\\"v${RELEASE_VERSION}\\",\\"name\\":\\"v${RELEASE_VERSION}\\",\\"generate_release_notes\\":true}"
                    '''
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
