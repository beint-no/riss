# Publishing

This project publishes the `model`, `runtime`, `compiler`, `spring`, and `gradle-plugin`
modules under `no.beint.riss`. The `example` module is not published.

## One-time setup

Publishing credentials live in `~/.config/skald/maven-central.env`, the same file used
for Skald and Thim.

```sh
set -a
source ~/.config/skald/maven-central.env
set +a
```

## Release

Check the version in `build.gradle.kts`, then run:

```sh
./gradlew clean test
./gradlew publishAndReleaseToMavenCentral
```

After Central Portal release, tag the matching version:

```sh
git tag v0.1.4
git push origin v0.1.4
```

The GitHub Actions workflow is `.github/workflows/publish.yml`.
It builds on pushes and pull requests, and publishes only from `v*` tags or manual
`workflow_dispatch`.
