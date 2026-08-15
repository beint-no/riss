# Design

## Objective

Riss compiles a Spring MVC API into one OpenAPI 3.1 JSON document.

It optimizes for four properties:

1. a public contract mistake fails the application build;
2. request time only serves the compiled bytes and a small HTML page;
3. the document has one dialect and one encoding;
4. the runtime stays small enough to audit.

## Output

The compiler emits OpenAPI 3.1.0 JSON. It does not emit OpenAPI 3.0, Swagger 2, or YAML.

JSON Schema uses the 2020-12 dialect. Nullable values use a `type` array that includes `null`, not the OpenAPI 3.0 `nullable` flag.

## Compilation

1. The Gradle plugin applies KSP and points it at the application sources.
2. The compiler finds `@RestController` mappings, `@RissDocument` metadata, and swagger-core annotations used as documentation input.
3. It builds component schemas from Kotlin and Java types, Bean Validation, Jackson names, and `@Schema`.
4. Named string and object schemas, global headers, default responses and security schemes are read from Riss annotations.
5. Unknown `$ref`s, `Any`/`Object` as a request or response root, non-String map keys, missing path variables and unreadable `@JsonValue` enums fail compilation. Nested `Any`, `Object`, `JsonNode` and unbound type parameters are emitted as unconstrained JSON Schema. Generic types such as `Page<Invoice>` become distinct component schemas. Sealed types become `oneOf` their visible subclasses.
6. Jackson 3 writes the JSON next to a generated `SpecSet` and a service-loader registration.

The compiler may read swagger annotations. It never depends on swagger-core, swagger-models or Jackson 2.
Jackson 3 is a compiler-only dependency used to emit JSON. The runtime serves the finished bytes.

## Runtime

The runtime, model, Spring adapter and Gradle plugin are Java. The compiler is Kotlin because it uses KSP.

A request for `GET /openapi` writes the compiled UTF-8 bytes. `GET /openapi/ui` writes one HTML file. There is no schema walk and no YAML conversion at request time.

Those paths are the convention. Apps do not configure a prefix. One compiled document is the default. When several documents exist, `/openapi` lists them and each document is addressed as `/openapi/{name}`.

## Language

Riss documents HTTP JSON APIs and multipart uploads. It does not implement callbacks, links, webhooks, XML, client generation or a plugin marketplace.

`@ParameterObject` flattens a type into query parameters. The same meaning is accepted from SpringDoc's annotation so existing controllers can compile before they are renamed.

## Frameworks and JDK

The model and runtime do not depend on Spring. The MVC adapter serves the compiled spec. A non-Spring process can load `SpecSet` through the service loader.

Riss targets released JDK 26 APIs and uses no preview feature.
