# Publishing

This project publishes the `model`, `runtime`, `compiler`, `spring`, `gradle-plugin`, and `mcp`
modules under `no.beint.riss`. The `example` module is not published.

## Credentials

Generate a Central Portal user token at
<https://central.sonatype.com/usertoken>.

Set these environment variables in the shell or CI environment:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_ID
SIGNING_IN_MEMORY_KEY_PASSWORD
```

## Release

Set the version in `build.gradle.kts`, merge the change to `main`, and push a
matching tag:

```sh
git tag vX.Y.Z
git push origin vX.Y.Z
```

GitHub Actions verifies that the tag matches the Gradle version, then builds
and publishes that exact tagged commit. No local Maven credentials are needed.
