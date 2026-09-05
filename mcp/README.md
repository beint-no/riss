# Riss MCP

A build-time OpenAPI-to-MCP compiler and a small tools server. Requires JDK 26.
There are **no dependencies**, including on other Riss modules or test libraries.

The module accepts an OpenAPI 3.1 JSON document and emits a portable tool catalog.
At runtime it loads that catalog, serves discovery from pre-encoded pages, and turns
tool arguments into requests to a fixed API origin. It never scans application
classes, resolves annotations, downloads schemas, or parses OpenAPI at runtime.

The existing Riss annotation processor, plugin, model, Spring adapter, and runtime
are unchanged. No existing task depends on MCP. To extract this module, move this
directory and supply an ordinary Java build; its only integration contract is JSON.

## Build and compile

MCP is available as `no.beint.riss:mcp:0.1.10`. It was added after the 0.1.9
release and is not part of that older release.

```sh
./gradlew :mcp:build
java -jar mcp/build/libs/mcp-0.1.10.jar compile \
  --spec /path/to/openapi.json \
  --out /path/to/build/mcp/catalog.json
```

Run `compile` after the app's existing OpenAPI generation step, as an optional
build task. The output is deterministic, contains no credentials, and carries only
the schemas referenced by each tool. Deploy the catalog alongside the MCP JAR.
Neither the catalog compiler nor the server needs the application on its classpath.

For existing Riss applications, `:public-api:rissSpec` already produces the input.
For example, from ReAI's checkout:

```sh
./gradlew :public-api:rissSpec
java -jar "$RISS_MCP_JAR" compile \
  --spec public-api/build/riss/spec/no/reai/public_api/riss/generated/RissSpec.json \
  --out build/mcp/public.json
```

Utin's corresponding input is
`public-api/build/riss/spec/no/utin/public_api/riss/generated/RissSpec.json`.
There is no app source, annotation, security-chain, or dependency change to make.

All supported operations are compiled by default. Use `--include name1,name2` for
an explicit tool set. Unknown names and unsupported explicitly selected operations
fail compilation. Without an explicit selection, unsupported media types/styles
are reported to stderr and excluded. `--read-only true` selects GET, HEAD, and
OPTIONS; review endpoint behavior before treating HTTP method selection as a
guarantee of no side effects. The compiler does not invent tool safety hints.
Explicit boolean hints may be supplied in an operation's `x-mcp-annotations` object.

## Connect an MCP client

Stdio works with an unchanged running API. Configure the client's command as
`java`, with arguments equivalent to:

```sh
java -jar "$RISS_MCP_JAR" stdio \
  --catalog build/mcp/public.json \
  --upstream http://127.0.0.1:8080 \
  --api-token-env REAI_API_KEY
```

For Utin, use `--api-token-env UTIN_API_KEY`. The named environment variable supplies
the existing API bearer credential. JSON-RPC uses stdout exclusively; CLI diagnostics
go to stderr. Stdio processes concurrent requests on virtual threads, limits active
requests, and handles cancellation notifications. Closing stdin drains pending work
with a bounded shutdown period.

For Streamable HTTP, configure **a separate** `RISS_MCP_TOKEN` environment variable:

```sh
java -jar "$RISS_MCP_JAR" serve \
  --catalog build/mcp/public.json \
  --upstream http://127.0.0.1:8080 \
  --api-token-env REAI_API_KEY \
  --port 8082
```

The MCP endpoint is `http://127.0.0.1:8082/mcp`. Clients authenticate using
`Authorization: Bearer <MCP token>` and send POST requests with
`Content-Type: application/json` and `Accept: application/json, text/event-stream`.
Responses use JSON; GET streams and sessions are not required or advertised.

The HTTP transport binds to loopback by default. `--bind` changes the address.
An Origin header is rejected unless its exact value is listed in `--origins`.
Use HTTPS at the boundary for remote connections. This module provides configured
bearer authentication; OAuth login, token issuance, and authorization-server
discovery are not implemented. Clients requiring that flow need an authorization
gateway. Stdio clients need no MCP HTTP authentication setup.

Each server instance has one configured upstream identity. Everyone holding its
MCP token uses that identity and its API permissions. Use separate instances or a
custom executor for separate identities. Incoming MCP credentials are never sent
to the API. No API credential is copied from the OpenAPI document or accepted in
tool arguments. The executor never follows redirects or automatically retries a
request, including writes.

## Tool contract

Operation IDs become tool names. Summaries, descriptions, enums, required fields,
and referenced DTO schemas are reused. Each tool has up to four argument groups:

```json
{
  "path": {"id": 123},
  "query": {"limit": 20},
  "headers": {"X-Tenant-Id": 456},
  "body": {"name": "Example"}
}
```

Only groups and fields declared by that operation are accepted. Grouping preserves
distinct path/query/header parameters with the same name. Optional absent values
remain absent, allowing the application's existing defaults to apply. Explicit
null is supported in JSON bodies; HTTP parameters cannot represent a JSON null.
Tenant/workspace headers remain ordinary, documented inputs. They select scope;
the existing API checks whether the configured identity may access it.

Paths support Spring regex variables, percent-encoded values, and scalar/flat
collection serialization. Query parameters support form, deepObject, spaceDelimited,
and pipeDelimited styles. JSON bodies and multipart bodies are supported. Multipart
files are objects containing `filename`, `base64`, and optional `contentType`; file
arrays become repeated parts. DTO parts are encoded as JSON. Unsupported request
media types and parameter styles are diagnosed during compilation.

Successful results include both a text representation and structured JSON:

```json
{"status": 200, "result": {"id": 123, "name": "Example"}}
```

The envelope also handles arrays, primitives, and empty responses consistently
across protocol versions. Binary responses use a `contentType`/`base64` object.
Input types, required properties, enums, common bounds, references, and schema
compositions are checked before execution. Declared successful output shapes are
checked too. This is not a general-purpose JSON Schema validator: formats, patterns,
and business validation remain the responsibility of the existing API. Application
filters, MVC binding, Bean Validation, permission checks, audit hooks, and tenant
context all run because execution uses the original HTTP endpoint.

API failures become `isError: true` results. Authentication failures, permission
denials, missing resources, rate limits, transport failures, and timeouts are
distinguished. Internal API error bodies and stack traces are not exposed.
For validation/conflict responses (400, 409, 422), a bounded JSON `detail`, `message`,
or `error` string is retained so the client can correct its request.

## Library API

The same classes can be embedded explicitly in any Java application:

```java
var catalog = McpCompiler.compile(openApiBytes).catalog(); // build step

var api = new HttpApiExecutor(URI.create("http://127.0.0.1:8080"),
        Map.of("Authorization", "Bearer " + apiToken));
var runtime = new McpRuntime(catalog, api);
var server = new McpServer(runtime,
        new InetSocketAddress("127.0.0.1", 8082), mcpToken, Set.of());
server.start();
// Close server and api when the application shuts down.
```

`McpExecutor` is the only execution extension point. Its request contains the
compiled HTTP method, relative path, allowed headers, and encoded body. Custom
executors must preserve the host application's validation and authorization boundary.
`McpRuntime.handle` is transport-independent. `McpServer` and `McpStdio` own transport
framing and concurrency. No Spring auto-configuration or background process is
installed merely by depending on the module.

An application hosting multiple users can call `handle(body, headers, executor)`
with an executor scoped to that authenticated request. The runtime shares its
immutable catalog and encoded discovery pages; it never stores the supplied
executor or credentials. The host owns authentication, authorization, consent and
credential storage. The standalone CLI retains its single upstream identity.

## Protocol and limits

Tools-only capabilities are implemented for `2026-07-28`, with legacy initialization
and tools support for `2025-11-25`, `2025-06-18`, and `2025-03-26`. Current requests
use per-request metadata and `server/discover`; required HTTP method/name/version
headers are checked against the body. The module does not advertise resources,
prompts, sampling, elicitation, task execution, subscriptions, or server-originated
notifications. It does not implement the legacy HTTP+SSE transport.
Custom `x-mcp-header` mirroring and dynamic schema scopes are diagnosed as
unsupported during compilation; they are never advertised and then ignored.

Defaults are 1 MiB per MCP request, 8 MiB per upstream response, 64 concurrent
requests, a 30-second upstream deadline, and a 60-second HTTP exchange deadline.
The CLI accepts `--max-request-bytes` and `--max-response-bytes` up to 64 MiB,
including for larger multipart uploads. Base64 contributes to the request limit.
Library callers can supply request/response limits and an upstream timeout directly.
JSON parsing rejects malformed UTF-8, duplicate object keys, excessive nesting,
and unbounded numeric representations.

Tool pages are pre-encoded once, capped at 32 tools and approximately 128 KiB per
page (a single larger tool occupies its own page). Cursors are tied to the catalog
digest, so cursors from a different build fail clearly. Catalogs are immutable for
the lifetime of a runtime; restart with a newly compiled catalog to update tools.

See [verification and measurements](VERIFICATION.md) for the checked consumer
contracts, live integration coverage, and reproducible performance harness.

Protocol references: [tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools),
[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http),
[schema](https://modelcontextprotocol.io/specification/2026-07-28/schema),
[legacy initialization](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle).
