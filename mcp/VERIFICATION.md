# MCP verification

Verified on 2026-09-05 using JDK 26 on an Apple M5 Max.

## Isolation

- No dependencies in `mcp/build.gradle.kts`, including test dependencies.
- Generated Maven POM contains no dependency declarations.
- `jdeps --print-module-deps` reports only `java.base,java.net.http,jdk.httpserver`.
- The executable JAR is approximately 66 KB, including the compiler and both transports.
- No existing module source or build file was changed. The root settings file only
  includes the new module. Existing OpenAPI consumer builds do not invoke it.
- `./gradlew clean build` passed for the whole repository. Subsequent MCP changes
  were checked with `:mcp:check` and the live consumer probe again.

## Protocol and transport checks

`./gradlew :mcp:check` runs a plain Java verification program with **396 checks**.
These cover strict UTF-8/JSON handling (including deterministic randomized Unicode
round trips), reference closure, recursive DTOs, literal `$ref` properties/examples,
Spring regex paths, parameter-name collisions, multipart arrays/JSON parts, URL
encoding, reserved headers, protocol versions, discovery, stale cursors, concurrent
requests, and cancellation.

HTTP tests start real JDK servers on ephemeral loopback ports. They check upstream
credential separation, authorization failures, origins, content negotiation, request
size bounds, response size bounds, timeouts, and redirect refusal. They verify that
discovery never invokes the API, that invalid inputs do not execute operations, and
that API errors remain tool errors with internal diagnostics withheld. Stdio tests
verify that cancelling a blocked tool does not block another request.

The tests use only the JDK. `:mcp:test` delegates to the same verification task;
the usual framework-based test runner is disabled for this module.

## ReAI and Utin

Tests used dedicated worktrees at these commits:

| Consumer | Commit | Compiled tools | Catalog bytes |
| --- | --- | ---: | ---: |
| ReAI public API | `0e38ab5d1c` | 472 | 1,600,822 |
| Utin public API | `d2c1a876` | 207 | 663,245 |

Both unchanged consumer builds passed, and their generated OpenAPI documents
compiled without exclusions. No application classes or dependencies were needed
by the MCP compiler. Multipart endpoints were included in the catalog checks;
multipart wire encoding was checked in the module's controlled fixtures,
not by uploading files into consumer business data.

Both applications were then started locally with their existing security
configuration. Scheduled jobs were disabled only in the disposable test worktrees.
ReAI used the existing local database and temporary API credentials. Utin used an
isolated temporary database initialized with its migrations and synthetic fixtures.
No production endpoints were called.

The live MCP probe negotiated the protocol over stdio, paged through the complete
tool catalogs, and compared tool results with direct HTTP responses:

| Consumer / operation | Verified behavior |
| --- | --- |
| ReAI `customerApi_list` | HTTP 200 JSON identical to the direct API |
| ReAI unauthorized tenant | API's HTTP 403 remains an MCP tool error |
| Utin `listCompanies` | HTTP 200 JSON identical to the direct API |
| Utin `listAccounts` | HTTP 200 JSON identical within the authorized workspace |
| Utin unauthorized workspace | API's HTTP 403 remains an MCP tool error |
| Both consumers, invalid API credential | API returns HTTP 401 |

Consumer configurations are not committed or upgraded. Test credentials are
deleted and the temporary Utin database is removed after verification.

## Measurements

The reproducible harness is `src/test/java/no/beint/riss/mcp/McpBenchmark.java`:

```sh
./gradlew :mcp:testClasses
java -cp mcp/build/classes/java/main:mcp/build/classes/java/test \
  no.beint.riss.mcp.McpBenchmark /path/to/generated/RissSpec.json
```

One local warmed run produced:

| Contract | Compile median | First discovery page | Engine time / request | Allocation / request |
| --- | ---: | ---: | ---: | ---: |
| ReAI | 13.0 ms | 104,944 bytes | 7.5 µs | 106,720 bytes |
| Utin | 8.1 ms | 69,419 bytes | 2.5 µs | 71,200 bytes |

Compilation is the median of nine runs following six warmups. Discovery uses
2,000 warmups and 2,000 measured calls, with per-thread allocated bytes from the
JDK management API. These numbers measure the protocol engine and its response
buffer, excluding transport, upstream HTTP, and application/database work. They
are not an end-to-end latency guarantee or a comparison against another SDK.

Catalog pages are encoded once. Per-request work is a bounded JSON parse, a cursor
lookup, and one response buffer containing the request ID plus cached page bytes.
Tool calls use a pooled JDK HTTP client and virtual threads. A call makes one
upstream HTTP request so the application's existing validation and authorization
pipeline runs; no controller reflection or second annotation scan is introduced.
