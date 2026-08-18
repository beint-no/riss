# Publishing

This project publishes the `model`, `runtime`, `compiler`, `spring`, and `gradle-plugin`
modules under `no.beint.riss`. The `example` module is not published.

## Credentials

Set these standard environment variables in the shell or CI environment:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_ID
SIGNING_IN_MEMORY_KEY_PASSWORD
```

## Release

Check the version in `build.gradle.kts`, then run:

```sh
./gradlew build
./gradlew publishAndReleaseToMavenCentral
```

After Central Portal release, tag the matching version:

```sh
git tag v0.1.7
git push origin v0.1.7
```

CI is `.github/workflows/ci.yml` and runs `./gradlew build` on pull requests and
`main`. Publishing is `.github/workflows/publish.yml` and runs only from `v*` tags
or manual `workflow_dispatch`.
