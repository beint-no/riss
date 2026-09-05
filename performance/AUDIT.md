# Riss performance audit — 2026-09-05

Riss already has a sound performance model: compile the OpenAPI document during the
application build, load its bytes once, and serve those bytes without schema scanning
or JSON serialization at request time. The useful immediate improvements are in
repeated compiler work, compiler resource lifetime, and explorer HTML decoding.

## Implemented

| Area | Previous work | Change |
| --- | --- | --- |
| Document metadata | Parse global headers and default responses for every operation; recreate their models | Parse and construct once per document; preserve explicit endpoint responses and header filters |
| Tags | Resolve descriptions even when the tag already exists | Resolve each emitted tag once |
| DTO properties | Search all declared properties for every constructor parameter, and resolve its type twice | Index properties once per DTO and resolve each parameter type once |
| Enums | Re-read source or reflect the same enum for each use; discover getters for each constant | Cache successful enum reads within a compilation; discover getters once per reflected enum |
| Compiler resources | Keep annotation symbols in a static map after processing; leave the URL classloader open | Isolate annotation caches per processing thread and remove them in `finally`; close the enum reader on success and failure |
| Explorer HTML | Decode the same UTF-8 HTML resource for every request | Decode once at class initialization; keep request-specific URL rendering |

No public API or generated document format changes. Compiler caches do not survive
into another compilation. Shared header/response models are immutable.

## Validation and measurements

Baseline: Riss `5a9c8f182168c2b435a40302fd2e4d3788aefa03` (0.1.8).
Consumer sources: ReAI `ae87edd9e1eeb4104b4ed564f7f83cff98a5a2ef`.
Environment: local macOS, OpenJDK 26.0.2.1, Kotlin 2.4.10, KSP 2.3.10.

The isolated consumer fixture compiled ReAI's actual `public-api` and `site-api`
Kotlin sources against their existing local dependency classpaths, substituting the
local Riss projects. This checks Riss against the real API without rebuilding every
ReAI domain module or starting ReAI.

| Regenerated document | Operations | Schemas | Bytes | Before/after |
| --- | ---: | ---: | ---: | --- |
| Public API | 471 | 531 | 1,112,731 | Byte-for-byte identical |
| Site API | 10 | 25 | 28,780 | Byte-for-byte identical |

SHA-256 of each output:

- Public: `c51589839ce8371eab045fd5263a6d637271645145ca96fe2fc661677dad46ed`
- Site: `7ee50abdaba8ed04b592fba54d34391ac1bcfddef759617c0ed42bd44eae1b5c`

After an initial warm-up, three clean consumer generations per version were run with
`--no-build-cache --profile`. Compiler/library artifacts and dependency downloads were
already available. The clean tasks cleared the consumer outputs and KSP state.

| Gradle task | Baseline samples (s) | Improved samples (s) | Median change |
| --- | --- | --- | --- |
| `:public-api:kspKotlin` | 3.139, 4.603, 4.212 | 3.070, 3.195, 3.210 | 4.212 → 3.195 s, 24% lower |
| `:site-api:kspKotlin` | 0.447, 0.519, 0.670 | 0.394, 0.426, 0.475 | 0.519 → 0.426 s, 18% lower |

These are local, sequential measurements with noticeable baseline variability, not
an isolated processor microbenchmark or a whole-ReAI build benchmark. They suggest
an improvement but are not a guaranteed production build-time saving. Removing the
repeated work and releasing resources are the stronger reasons for the changes.
Unchanged consumer builds leave both `rissClasspath` and `kspKotlin` up to date, and
configuration-cache reuse remains available.

The explorer benchmark calls the controller directly with a servlet context path,
20,000 warm-up requests and three samples of 20,000 requests. A volatile sink retains
the result. ThreadMXBean measures allocation on the calling thread.

| Explorer request | Baseline | Improved |
| --- | ---: | ---: |
| Allocated bytes per request | 225,256 | 123,656 |
| Observed elapsed range across reruns | 33.9–54.2 µs | 28.2–39.7 µs |

That is **45% less allocation** for this controller path, reproduced with both the
standalone harness and the Gradle benchmark task. Elapsed timings overlap across
reruns, so they do not establish a stable latency improvement. The UTF-8 decoder previously
created several temporary buffers because the template contains non-ASCII text.
These measurements exclude servlet/network overhead and do not describe throughput
of ReAI's business endpoints. The JSON endpoint already reuses its compiled byte
array; it is unchanged.

`./gradlew build` passes: 32 tests, including Spring application startup, endpoint,
compatibility alias, forwarded-prefix, servlet-path, compiler and runtime checks.
Added regression tests verify that enum caches are refreshed between compilations,
filters remain independent, and annotation caches are released and isolated between
processing threads. No dependency versions were changed.

## Reproduce the explorer measurement

The optional benchmark adds no normal build tasks or dependencies. From the Riss
checkout:

```sh
./gradlew -I performance/runtime-benchmark.init.gradle :spring:benchmarkRuntime --no-configuration-cache
```

To compare a previously built adapter JAR with the same harness and dependencies:

```sh
./gradlew -I performance/runtime-benchmark.init.gradle :spring:benchmarkRuntime --no-configuration-cache -PrissBenchmarkJar=/absolute/path/to/baseline-spring.jar
```

The local ReAI compilation fixture is in the ignored `build/audit-consumer` directory.
Its generation command is:

```sh
./gradlew -p build/audit-consumer :public-api:clean :site-api:clean :public-api:rissCheck :site-api:rissCheck --no-build-cache --profile
```

The fixture depends on this machine's prebuilt ReAI classpaths and is not a portable
integration test.

## Follow-up candidates

1. **Make Gradle classpath handling relocatable.** `rissClasspath` snapshots a full
   classpath even though its output contains only absolute filenames. Absolute
   `riss.classpathFile` processor arguments also deserve attention when sharing build
   caches across worktrees or machines. This needs Gradle TestKit coverage for moved
   classpaths, project dependency ordering and enum changes before changing inputs.
   Merely weakening the inputs could produce stale output. See
   [Gradle input normalization](https://docs.gradle.org/current/userguide/incremental_build.html).
2. **Profile schema traversal and JSON writing before larger compiler changes.** There
   are further repeated declaration walks and an intermediate map/list JSON tree.
   Rewriting these could help larger APIs, but adds substantially more complexity than
   the changes above. Preserve deterministic ordering and schema/annotation behavior.
3. **Keep the document output aggregating.** It represents many controllers and DTOs;
   switching it to isolating to force faster incremental builds would be incorrect.
   KSP tracks dependencies reached by type resolution, as described in
   [KSP incremental processing](https://kotlinlang.org/docs/ksp-incremental.html).
4. **Avoid an unbounded URL-dependent explorer/catalog cache.** ReAI has two documents,
   so linear name lookup and catalog construction are small. Request prefixes can
   vary, and caching every prefix would trade modest CPU savings for retained memory.
5. **Keep dependency cleanup separate from this patch.** The runtime's transitive
   `model` dependency is unused by its implementation, but removing it changes the
   classpath exposed to consumers. It is small and not an immediate performance win.

The library must be released and ReAI's Riss version updated before production gains
these changes. This audit does not change ReAI dependencies or deploy an application.
