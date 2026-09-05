# Riss

Riss compiles a Spring MVC API into an OpenAPI 3.1 JSON document. A public-contract
mistake fails the application build. The runtime serves the compiled bytes and a
small HTML explorer. It does not ship Swagger UI, Scalar, swagger-core, or Jackson 2.

The name is Norwegian *riss*: an outline.

Riss requires JDK 26. Its MVC adapter targets Spring Framework 7 and Spring Boot 4.
A process without Spring can load the compiled `SpecSet` through the service loader.

## Use

```kotlin
plugins {
    kotlin("jvm")
    id("no.beint.riss") version "0.1.9"
}
```

Declare document metadata on a dedicated type. That type is the source of truth for
scan packages, paths, and document name. Existing swagger annotations on controllers
and DTOs are read as input.

```kotlin
@RissDocument(
    name = "public",
    title = "Example API",
    version = "v1",
    scanPackages = ["no.example.api"],
    paths = ["/api/**"],
    security = ["bearer-token"],
)
@RissSecurityScheme(name = "bearer-token", bearerFormat = "JWT")
@RissServer(url = "https://api.example.com", description = "Production")
@RissGlobalHeader(name = "X-Tenant-Id", type = "integer", format = "int32")
class PublicApiDocs
```

The compiled document is served at `GET /openapi`. The explorer is at `GET /openapi/ui`.
Those paths are fixed. Do not configure a custom prefix. `/openapi` includes an `ETag`.

If an application compiles more than one document, `/openapi` lists them and each
document is served at `/openapi/{name}` and `/openapi/{name}/ui`. A single document
does not get a second URL from its name.

`Any`, `Object` and `JsonNode` as nested fields become unconstrained JSON. Using them as the
request or response root fails the build. Generic types keep their type arguments, so
`Page<Invoice>` and `Page<User>` are different schemas.

YAML is not a supported encoding. The JSON is minified. Agents should read `/openapi`
when the application publishes one document. For multiple documents, `/openapi` is a
catalog and agents should follow the `json` URL for the document they need.

Applications can opt into compatibility aliases for tools that expect common Springdoc
or OpenAPI paths:

```yaml
riss:
  compatibility:
    enabled: true
    primary-document: public
```

The primary document is served directly at `/openapi.json`, `/v3/api-docs`, and
`/api-docs`. `/swagger-ui`, `/swagger-ui/`, `/swagger-ui.html`, and `/swagger-ui/index.html` redirect
to its Riss explorer. `primary-document` is optional for an application with one
compiled document and required when several documents are present. Compatibility
aliases are disabled by default to avoid conflicts with Springdoc or application routes.

## Modules

- `model`: OpenAPI 3.1 types
- `runtime`: `SpecSet` and compile-time annotations
- `compiler`: KSP processor. Emits deterministic compact JSON without runtime dependencies
- `spring`: JSON and UI endpoints
- `gradle-plugin`: build integration
- `example`: Spring Boot application used as the integration test

## Swagger annotations

The Gradle plugin supplies `swagger-annotations-jakarta` as a `compileOnly` dependency
for existing `@Operation`, `@Schema`, `@Parameter`, and response documentation. Riss
reads these annotations through KSP without linking its compiler or runtime to the
Swagger package. They do not need to be shipped with the application. Keep them when
migrating an existing documented API; removing them discards metadata that Riss
cannot infer from controller signatures.

See [the performance audit](performance/AUDIT.md) for the optimizations and measurements
behind 0.1.9.
