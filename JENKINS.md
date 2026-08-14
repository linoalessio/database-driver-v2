# Jenkins CI/CD

This repo builds and releases through the `Jenkinsfile` at the root. Every push runs a normal
build+test (CI). Releases (version bump, tag, push, deploy to GitHub Packages, GitHub release) are
triggered by starting a build with the `RELEASE_VERSION` parameter set — nothing needs to be done
by hand beyond that, and there's no per-release conversation needed with an assistant to run it.

This file only covers the one-time setup on the Jenkins side; the pipeline logic itself lives in
`Jenkinsfile` and is version-controlled like everything else.

## One-time setup

**1. Tools** (Manage Jenkins → Tools):
- JDK installation named `jdk21`, pointing at a **JDK 21** distribution. Must be 21, not 17 or a
  newer LTS: Lombok's annotation processor doesn't run under newer JDKs on this project, and both
  modules' `maven-compiler-plugin` hardcodes `<source>21</source><target>21</target>`, which rules
  out 17 too. The "Eclipse Temurin installer" plugin can provision this automatically.
- Maven installation named `maven3`, pointing at Maven 3.9+.

The Jenkins agent also needs `git`, `curl` and `python3` on `PATH` — all standard on Jenkins Linux
agents, nothing extra to install for a normal setup.

**2. Credential** (Manage Jenkins → Credentials): add a **Username with password** credential
with ID `github-pat`:
- Username: your GitHub username (`linoalessio`)
- Password: a GitHub [personal access token](https://github.com/settings/tokens) with `repo` and
  `write:packages` scopes

This one credential covers all three release steps: pushing the version-bump commit/tag,
publishing to GitHub Packages, and creating the GitHub release via the REST API.

**3. Job**: create a Pipeline job (or Multibranch Pipeline, if you also want PR builds) pointing
at this repository, with "Pipeline script from SCM" → this `Jenkinsfile`.

**4. Trigger on push**: add a GitHub webhook to `<jenkins-url>/github-webhook/` and enable
"GitHub hook trigger for GITScm polling" on the job, so every push builds automatically without
needing to click anything in Jenkins. If Jenkins isn't reachable from the internet, use
`pollSCM('H/5 * * * *')` instead (poll every 5 minutes).

## Usage

- **Normal push**: build + test only, nothing is published. This is what CI does on every commit.
- **Cutting a release**: in Jenkins, "Build with Parameters" on this job, set `RELEASE_VERSION` to
  the new version (e.g. `1.3.6`, no leading `v`), and run it. The pipeline then, in order:
  1. Validates the version looks like `X.Y.Z`, that the build is on `master`, and that the tag
     doesn't already exist.
  2. Bumps the version in all three `pom.xml` files (`mvn versions:set`) and in `README.md`.
  3. Builds and runs the full test suite — a failure here stops before anything is committed,
     tagged or published.
  4. Commits ("Bump version to X.Y.Z"), tags (`vX.Y.Z`), and pushes both to `master`.
  5. Runs `mvn clean deploy` against GitHub Packages.
  6. Publishes a GitHub release for the new tag, with auto-generated release notes.

Releases can only be cut from `master` — the pipeline aborts otherwise.
