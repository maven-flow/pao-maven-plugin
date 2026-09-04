# pao-maven-plugin

Maven plugin that prevents SNAPSHOT artifacts from different Git branches from overwriting each other in a Maven repository.

When several branches of a library publish `1.1.0-SNAPSHOT`, whichever pipeline finished last wins, and downstream builds start failing in ways that look random. The fix is to give each branch its own version — `1.1.0-feature-FEA-123-SNAPSHOT` — and to strip that suffix again when the branch is merged. This plugin does both, automatically, in CI.

This is a Maven-native port of the [prevent-artifact-overwrites](https://github.com/maven-flow/prevent-artifact-overwrites) CI script.

## How it works

The `apply` goal looks at the branch being built:

- **On a feature branch** it appends the branch name to the project version, with slashes replaced by hyphens: `1.1.0-SNAPSHOT` becomes `1.1.0-feature-FEA-123-SNAPSHOT`.
- **On a core branch** (`main`, `master`, `develop`, `release*` by default) it strips the suffix back off — from the project version and from any dependency versions that carry one. You can merge a feature branch without hand-editing versions first.

The change is written to `pom.xml`, committed, and pushed. Because the version lives in the POM rather than being computed at build time, IDEs and plain `mvn` on a developer machine see exactly what CI sees, with no local setup.

## Usage

Run the goal as its own invocation, **before** the build that publishes the artifacts:

```bash
mvn -B com.jardoapps:pao-maven-plugin:0.1.0:apply
mvn -B deploy
```

Two invocations are required, not a stylistic choice: Maven reads and interpolates every POM before any mojo runs, so a version written during a build does not change what that same build deploys.

No POM changes are needed — the fully-qualified form above works on any project.

### GitHub Actions

```yaml
name: Java CI with Maven

on: push

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write            # needed to push the version commit

    steps:
      - uses: actions/checkout@v4
        with:
          token: ${{ github.token }}   # needed to push the version commit

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Prevent artifact overwrites
        run: >
          mvn -B com.jardoapps:pao-maven-plugin:0.1.0:apply
          -Dpao.enforceBranchVersion=true
          -Dpao.commitMessageSuffix='[skip ci]'

      - name: Build
        run: mvn -B deploy
```

The goal appends `changes-made=true|false` to `$GITHUB_OUTPUT`, so a later step can react to whether anything was rewritten.

On `pull_request` and `pull_request_target` events the branch is read from `GITHUB_HEAD_REF` rather than `GITHUB_REF_NAME`, which on those events is the synthetic `<n>/merge` ref rather than a branch. GitLab merge request pipelines are handled the same way through `CI_MERGE_REQUEST_SOURCE_BRANCH_NAME`. Pushing back to the source branch of a pull request from a fork will not work regardless, so use `-Dpao.pushChanges=false` there.

### GitLab CI/CD

```yaml
prevent-overwrites:
  stage: prepare
  image: maven:3.9-eclipse-temurin-17
  script:
    - mvn -B com.jardoapps:pao-maven-plugin:0.1.0:apply -Dpao.outputFile=build.env
  artifacts:
    reports:
      dotenv: build.env
```

### Declaring it in the POM

Optional. Putting the plugin in `<pluginManagement>` keeps the configuration with the project instead of in the CI file:

```xml
<plugin>
  <groupId>com.jardoapps</groupId>
  <artifactId>pao-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <enforceBranchVersion>true</enforceBranchVersion>
    <coreBranches>main master develop release*</coreBranches>
  </configuration>
</plugin>
```

The CI call stays fully qualified. The short `mvn pao:apply` form additionally needs `com.jardoapps` in `<pluginGroups>` in `settings.xml`, which is per-developer setup and best avoided.

## Libraries vs applications

- **Libraries** — the project whose version must change. Set `enforceBranchVersion` to `true` (the default).
- **Applications** — a project that consumes branch-versioned libraries. Set `enforceBranchVersion` to `false`. The project keeps its own version, but branch-specific dependency versions are still reset on core branches, so merging does not carry a feature branch's dependency versions into `develop`.

## Configuration

| Parameter | Property | Default | Description |
|---|---|---|---|
| `branchName` | `pao.branchName` | auto-detected | The branch being built. |
| `enforceBranchVersion` | `pao.enforceBranchVersion` | `true` | Whether the project itself gets a branch-specific version. |
| `pushChanges` | `pao.pushChanges` | `true` | Whether to push the resulting commits to `origin`. |
| `commitMessageSuffix` | `pao.commitMessageSuffix` | *(empty)* | Appended to every commit message, e.g. `[skip ci]`. |
| `gitUserName` | `pao.gitUserName` | `ci-bot` | Git user name for the commits. Applied per commit; the repository's `.git/config` is not modified. |
| `gitUserEmail` | `pao.gitUserEmail` | `ci-bot@example.com` | Git email for the commits. Applied per commit; the repository's `.git/config` is not modified. |
| `coreBranches` | `pao.coreBranches` | `main master develop release*` | Branch patterns that keep the plain version. Globs allowed; space- or comma-separated. |
| `configFile` | `pao.configFile` | `.prevent-overwrites.conf` | Optional per-branch pinning file, relative to the top-level project. |
| `outputFile` | `pao.outputFile` | *(none)* | File to append `changes-made=<boolean>` to. |
| `skip` | `pao.skip` | `false` | Skips execution entirely. |

The branch name is taken from `branchName` if set, otherwise from the first of `GITHUB_REF_NAME`, `CI_COMMIT_REF_NAME`, `BITBUCKET_BRANCH`, `CIRCLE_BRANCH` or `TRAVIS_BRANCH` that is present, otherwise from `git rev-parse --abbrev-ref HEAD`.

## Version format

A branch version is `<base>-<suffix>-SNAPSHOT`, where the base is a numeric version with an optional `-rc` qualifier:

| Version | Base | Branch suffix |
|---|---|---|
| `1.2.3-feature-abc-SNAPSHOT` | `1.2.3` | `feature-abc` |
| `1.2.10-feature-abc-SNAPSHOT` | `1.2.10` | `feature-abc` |
| `1.2.3-rc.4-feature-abc-SNAPSHOT` | `1.2.3-rc.4` | `feature-abc` |
| `1.2.3-SNAPSHOT` | — | *(not a branch version)* |
| `1.2.3-rc.4-SNAPSHOT` | — | *(not a branch version)* |

Release versions are left alone. The plugin exists because several branches would otherwise publish over one shared snapshot, so a project version without `-SNAPSHOT` is a sign the goal is running somewhere it was not meant to. Deriving a branch version from `1.2.3` would also be lossy — the trip back on a core branch produces `1.2.3-SNAPSHOT`, not `1.2.3` — so the version is logged and left unchanged instead.

## Multi-module projects

The whole reactor is handled in one run. When the project version changes, every reference from one reactor module to another moves with it: `<parent><version>` in each module that inherits from a reactor project, and any `<dependency>` on a sibling module that spells its version out. Otherwise a module would point at a parent version that no longer exists, or resolve a sibling from the repository — the branch-agnostic snapshot another branch published — instead of from the reactor.

## Versions defined by properties

A version written as `${some.version}` is followed to the `<properties>` entry that defines it, anywhere in the reactor, and the property is updated instead of the reference. This covers the CI-friendly `${revision}` style:

```xml
<version>${revision}</version>
<properties>
  <revision>1.2.3-SNAPSHOT</revision>   <!-- this is what gets rewritten -->
</properties>
```

If the property is not defined anywhere in the reactor, the reference is left alone and a warning is logged.

## Custom per-branch version pinning

By default the branch version is derived from the branch name. To pin explicit values for specific branches, add a configuration file (default `.prevent-overwrites.conf`). If the file is absent, or has no row matching the current branch, behaviour is unchanged.

```
# branch-pattern   target                             value
feature/f1         project-version                    1.2.3-f1-SNAPSHOT
feature/f1         dependency:com.example:d1          2.0.0-f1-SNAPSHOT
feature/f2         project-version                    1.2.3-f2-SNAPSHOT
*                  exclusive-version-suffix           feature-abc
```

- **`branch-pattern`** — glob-matched against the branch name, so `feature/*` works. As in bash, `*` already spans slashes.
- **`target`** — `project-version`, `dependency:<groupId>:<artifactId>`, or `exclusive-version-suffix`.
- **`value`** — the version to pin to, or for `exclusive-version-suffix` the suffix to protect.

Blank lines are ignored, and everything from a `#` to the end of a line is a comment.

### Rules

- **Pinned values must be branch versions** (`1.2.3-f1-SNAPSHOT`, not `vf1`). That is what lets them be reverted to `<base>-SNAPSHOT` on a core branch. An invalid value fails the build — including on rows for other branches, so typos surface on the first run rather than whenever that branch is next built.
- **`project-version`** pins apply only when `enforceBranchVersion` is `true`. A pin wins even over a version that already carries a branch suffix.
- **`dependency:*`** pins apply on non-core branches regardless of `enforceBranchVersion`, so applications can pin what they build against. A pin matches every `<dependency>` with those coordinates, including entries under `<dependencyManagement>` and inside plugin `<dependencies>`.

### Exclusive version suffixes

When a POM already carries a branch version it is normally left alone. That is a problem for long-lived feature branches: branching off `feature/abc` (whose POM says `1.2.3-feature-abc-SNAPSHOT`) means inheriting that version and publishing over `feature/abc`'s artifacts.

Marking a suffix exclusive says it belongs to one branch:

```
# branch-pattern  target                    value
*                 exclusive-version-suffix  feature-abc
```

On any other branch the version is then re-derived instead of inherited. On the owning branch it is left untouched, and re-runs change nothing. The value is a version *suffix*, not a branch name — slashes are already replaced with hyphens (`feature/abc` → `feature-abc`).

## Building and testing

```bash
mvn test                            # unit tests
bash it/run-integration-tests.sh    # integration tests (real Maven invocations and git repositories)
```

The integration tests install the plugin into the local repository first.

## License

MIT — see [LICENSE](LICENSE).
