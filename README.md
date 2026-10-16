# diia-load-testing

A library and Gradle plugin for load testing gRPC and HTTP services. The load test is written next to the
service, in Java or Kotlin. A gRPC test calls the service's own generated stubs, so it always uses the real API
contract; an HTTP test sends requests with the JDK HTTP client.

You describe three things: what one virtual user does (a scenario of calls), how many users arrive per second
(a load profile) and what results are acceptable (latency and error limits). The library generates the load,
measures every call, checks the limits and writes a console summary, an HTML report and a JSON summary.
When a limit is broken, the Gradle task fails, so the test can also run in CI.

## Contents

- [Getting the library](#getting-the-library)
- [How it works](#how-it-works)
- [Files you need](#files-you-need)
- [Gradle tasks](#gradle-tasks)
- [Writing a load test](#writing-a-load-test)
- [Settings](#settings)
- [Pass/fail rules](#passfail-rules)
- [Reports](#reports)
- [Local runs](#local-runs)
- [Building the library](#building-the-library)

## Getting the library

The library is not published to a Maven registry yet: its source is on GitHub, and every machine that builds a
service using it builds the library once and publishes it to its local Maven repository (`~/.m2`):

```bash
git clone https://github.com/OWNER/diia-load-testing.git
cd diia-load-testing
./gradlew publishToMavenLocal
```

This publishes the plugin and the libraries with the version of `gradle.properties` (`version=...`): use that
version in the service's build. After pulling changes of the library, run `./gradlew publishToMavenLocal` again;
the service picks up the new build on its next run.

In the service, `mavenLocal()` must come first among the repositories the plugin and the libraries are resolved
from, see [Files you need](#files-you-need).

The service's build resolves the plugin on every build, not only for `runLoadTest`. A machine without the library
in `~/.m2` cannot build the service at all, and neither can a CI job or a Docker build, which do not see the
developer's `~/.m2`. Until the library is in a registry the CI and Docker builds can reach, a service builds there
only if the version it uses is available from one of its other repositories.

## How it works

A **run** is one virtual user going through the scenario once, for example "create an order, then read it".
The load profile says how many new runs start every second. Runs start on schedule regardless of how fast the
service answers, so a slow service gets more concurrent requests instead of fewer, just like in production.

The standard profile has three **phases**:

1. **warm-up**: a low constant rate, so the service can warm up (JIT, connection pools, caches);
2. **ramp**: the rate grows linearly to the target rate;
3. **steady**: the target rate is held for a fixed time.

Statistics are collected separately for each phase. Latency limits are checked only in the steady phase, so a
slow start of the service does not fail the test.

Each scenario consists of **steps**. A step is a block of code whose duration is measured, usually one gRPC
call or one HTTP request. Code outside steps (building requests, generating keys, signing) is not measured.

## Files you need

| File | Required | What it contains |
|---|---|---|
| `settings.gradle(.kts)` | yes | the repository the plugin is resolved from |
| `build.gradle(.kts)` | yes | the plugin and the name of the load test class |
| `src/loadTest/java/...` or `src/loadTest/kotlin/...` | yes | the load test class: its scenarios and, optionally, its own parameters |
| `src/loadTest/resources/loadtest.properties` | no | the service's default test settings, for example the target rate or latency limits |
| `src/loadTest/resources/local-service.yaml` | no | only for local runs: changes to the service's Spring configuration for the test run |

**`settings.gradle.kts`.** The plugin is resolved from the local Maven repository (see
[Getting the library](#getting-the-library)):

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

**`build.gradle.kts`.** The libraries the plugin adds come from the same place, so `mavenLocal()` goes first in the
project's repositories too:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
```

In Groovy (`settings.gradle`, `build.gradle`) it is the same `mavenLocal()` line in the same blocks.

Then apply the plugin, with the version you published, and point it to the load test class:

```kotlin
plugins {
    id("ua.gov.diia.loadtest") version "0.0.1"
}

loadTest {
    definition = "com.example.orders.loadtest.OrdersLoadTest"
}
```

The plugin adds the `loadTest` source set (`src/loadTest`), which sees the service's classes, including the
generated gRPC stubs, and its dependencies. It also adds the library itself as a dependency and the two tasks
described below. The load test is not part of `build` or `test` and only runs when you call it.

The `loadTest { }` block has four options:

| Option | Default | Meaning |
|---|---|---|
| `definition` | none | the fully qualified name of the load test class; required |
| `serviceMainClass` | the Spring Boot main class | the service's main class, for local runs; only needed without Spring Boot |
| `mainClass` | none | a class with its own `main`, used instead of `definition` |
| `transports` | `["grpc"]` | what the test talks to the service over: `grpc` adds `loadtest-grpc`, `http` adds `loadtest-http`; both can be listed |

For an HTTP service:

```kotlin
loadTest {
    definition = "com.example.lists.loadtest.ListsLoadTest"
    transports = listOf("http")      // Groovy: transports = ['http']
}
```

**`loadtest.properties`.** Plain `name=value` lines with the settings described in [Settings](#settings).
A comment must be on its own line, not after a value:

```properties
# Latency limits for this service
p99Ms=100
maxMs=1000
# A longer run for nightly checks: -Dpreset=night
preset.night.steadySeconds=1800
```

## Gradle tasks

**`./gradlew runLoadTest`** runs the load test. It compiles `src/loadTest`, reads the settings, calls
`beforeAll` (for example, to start the service locally), connects to the service and generates the load
according to the profile. Every five seconds it prints the current rate, successes, errors and p99. After the
run it prints a summary per phase and step, checks the pass/fail rules and writes the reports. If a rule is
broken, the task fails. Every `-D` option given to Gradle is passed to the test:

```bash
./gradlew runLoadTest                                  # the standard run: warm-up, ramp, 3 minutes steady
./gradlew runLoadTest -Dpreset=smoke                   # about 10 seconds: checks that the whole chain works
./gradlew runLoadTest -Dhost=orders.stage -Dport=443 -Dtls=true
./gradlew runLoadTest -Dmode=stress -DpeakRate=1000    # keeps raising the rate to find the breaking point
```

**`./gradlew loadTestHelp`** runs nothing. It prints the scenarios of the load test, the available presets and
every setting with its description, its current value and where that value comes from (`-D`, a preset,
`loadtest.properties` or the default). Use it to see what can be configured and what a run will use.

## Writing a load test

The load test is one class that extends `GrpcLoadTestDefinition` (gRPC) or `HttpLoadTestDefinition` (HTTP). It has
no `main` method: the plugin runs it.
In the class you add one or more scenarios with `addScenario`. The first one is the default; others are
selected with `-Dscenario=<name>`.

The main classes:

| Class | Purpose |
|---|---|
| `GrpcLoadTestDefinition` | the base class of a gRPC load test. It adds the connection settings (`host`, `port`, `tls`, ...), opens the connection and gives you stubs |
| `HttpLoadTestDefinition` | the base class of an HTTP load test. It adds the connection settings (`baseUrl`, `timeoutMs`) and gives you an `HttpTarget` with `target()` |
| `stubs { ... }` → `StubPool` | creates stubs for the run's connection. Every call through them gets the `deadlineMs` deadline automatically |
| `scenario { }` (Kotlin), `LoadTesting.scenario(...)` (Java) | describes what one run does |
| `step(name) { }` (Kotlin), `run.step(name, () -> ...)` (Java) | a measured block; it returns the block's result, so values pass to the next steps as ordinary variables |
| `stringParam`, `intParam`, `longParam`, `doubleParam`, `booleanParam` | the test's own settings; they work like the built-in ones and appear in `loadTestHelp` |
| `LoadTestContext` | the current run: the selected scenario, `isLocal`, `environment` for local runs |

Kotlin, with coroutine stubs:

```kotlin
object OrdersLoadTest : GrpcLoadTestDefinition("orders-service load test") {
    private val itemCount = intParam("itemCount", 3, "Items in every created order")

    init {
        addScenario("order", "Create an order, then read it") {
            val stubs = stubs { OrdersGrpcKt.OrdersCoroutineStub(it) }
            scenario("Create and read") {
                val stub = stubs()
                val request = createOrderRequest(itemCount.value)       // not measured
                val order = step("Create order") { stub.createOrder(request) }
                step("Get order") { stub.getOrder(getOrderRequest(order.id)) }
            }
        }
    }
}
```

Java, with blocking stubs:

```java
public final class OrdersLoadTest extends GrpcLoadTestDefinition {

  private final Parameter<Integer> itemCount = intParam("itemCount", 3, "Items in every created order");

  public OrdersLoadTest() {
    super("orders-service load test");
    addScenario("order", "Create an order, then read it", context -> {
      final StubPool<OrdersBlockingStub> stubs = stubs(OrdersGrpc::newBlockingStub);
      return LoadTesting.scenario("Create and read", run -> {
        final OrdersBlockingStub stub = stubs.next();
        final CreateOrderRequest request = createOrderRequest(itemCount.getValue());   // not measured
        final Order order = run.step("Create order", () -> stub.createOrder(request));
        run.step("Get order", () -> stub.getOrder(getOrderRequest(order.getId())));
      });
    });
  }
}
```

Kotlin scenarios run on coroutines. Java scenarios run each run on its own virtual thread, so blocking stubs
scale to thousands of concurrent runs.

If a step throws, the failure is recorded under the step's name with its gRPC status code, and the rest of that
run is skipped. To check a response, throw an exception inside the step: it is then counted as a failed call.

### HTTP

`target()` returns the run's `HttpTarget`: `request(path)` gives a `java.net.http.HttpRequest.Builder` for a path
relative to `baseUrl` (an absolute URL is used as it is), `send(request)` sends it from Kotlin (suspending) and
`sendBlocking(request)` from Java. The response comes back with its body as a string. Any 2xx status is
expected; list the codes when others are expected too, for example `send(request, 200, 304)` for a conditional
GET. Any other status fails the step as `HTTP_<status>`. Every request without its own timeout gets `timeoutMs`.

```kotlin
object ListsLoadTest : HttpLoadTestDefinition("lists-service load test") {
    init {
        addScenario("download", "Download a list, then revalidate it with its ETag") {
            val http = target()
            scenario("Download and revalidate") {
                val list = step("Download") { http.send(http.request("/lists/1").GET().build()) }
                val etag = list.headers().firstValue("ETag").orElseThrow()        // not measured
                step("Revalidate") { http.send(http.request("/lists/1").header("If-None-Match", etag).build(), 304) }
            }
        }
    }
}
```

```java
public final class ListsLoadTest extends HttpLoadTestDefinition {

  public ListsLoadTest() {
    super("lists-service load test");
    addScenario("download", "Download a list", context -> {
      final HttpTarget http = target();
      return LoadTesting.scenario("Download", run ->
              run.step("Download", () -> http.sendBlocking(http.request("/lists/1").GET().build())));
    });
  }
}
```

A gRPC load test that also sends some HTTP requests (for example downloads that a service serves next to its gRPC
API) opens the client itself: `context.closeLater(HttpTarget.connect(new HttpSettings(url, timeoutMillis)))`, with
`transports = ['grpc', 'http']` in the build.

The client speaks HTTP/1.1 and opens as many connections as there are requests in flight, so behind a
Kubernetes Service the load spreads over the pods. Override `connect(settings)` for anything else, for example
`HttpTarget.connect(settings) { sslContext(...) }` for a custom TLS trust store or `version(HTTP_2)`.

Optional methods you can override:

| Method | Purpose |
|---|---|
| `beforeAll(context)`, `afterAll(context, result)` | code before and after the load, for example starting the service locally |
| `stepP99Limits()` | a separate p99 limit for a step that is slower by design |
| `requiredSteps(context)` | steps that must be called at least once; useful for steps that only run under a condition |
| `assertions(context)` | your own pass/fail rules instead of the default ones |
| `profile()` | your own load profile instead of the standard one |
| `connect(settings)` | your own gRPC channel or HTTP client, for example with interceptors or custom TLS |

## Settings

Every setting can be set in three places. If it is set in several, the first one wins:

1. on the command line: `-D<name>=<value>` (or `-DloadTest.<name>=<value>` if the name clashes with a
   system property the service itself reads);
2. in a preset, selected with `-Dpreset=<name>`;
3. in `src/loadTest/resources/loadtest.properties`.

If a setting is set nowhere, the default below is used. A **preset** is a named set of settings. `smoke` is
built in; your own presets go into `loadtest.properties` as `preset.<name>.<setting>=<value>`.

**Load profile**

| Setting | Default | Meaning |
|---|---|---|
| `mode` | `steady` | `steady`: warm-up, ramp, then the target rate is held and the rules are checked. `stress`: after the warm-up the rate keeps growing to `peakRate`, nothing is checked; use it to find the breaking point |
| `warmupRate` | `20` | runs per second during the warm-up |
| `warmupSeconds` | `60` | length of the warm-up, in seconds |
| `targetRate` | `150` | `steady`: runs per second held in the steady phase |
| `rampSeconds` | `60` | `steady`: how long the rate grows from `warmupRate` to `targetRate`, in seconds |
| `steadySeconds` | `180` | `steady`: how long the target rate is held, in seconds |
| `peakRate` | `600` | `stress`: runs per second at the end of the run |
| `peakRampSeconds` | `360` | `stress`: how long the rate grows to `peakRate`, in seconds |
| `maxInFlight` | `10000` | the maximum number of runs in progress at once; new runs beyond it are dropped and fail the test |

Rates are runs per second, not calls: a scenario with three steps makes three calls per run.

**Pass/fail limits**

| Setting | Default | Meaning |
|---|---|---|
| `p99Ms` | `200` | the p99 latency of every step must stay below this value, in milliseconds |
| `maxMs` | `2000` | the maximum latency of every step must stay below this value, in milliseconds |
| `maxErrorRate` | `0.0` | the allowed share of failed calls per step, from `0.0` to `1.0` |
| `maxRunErrorRate` | `1.0` | the allowed share of failed runs; `1.0` means it is not checked |
| `maxClientLagMs` | `100` | the allowed p99 delay with which the test starts runs; see [Pass/fail rules](#passfail-rules) |

**Connection (gRPC)**

| Setting | Default | Meaning |
|---|---|---|
| `host` | `localhost` | the service's host |
| `port` | `9090` | the service's gRPC port |
| `tls` | `false` | connect with TLS |
| `channels` | `1` | the number of gRPC connections. Behind a Kubernetes Service one connection goes to one pod, so use at least the number of replicas |
| `deadlineMs` | `5000` | the deadline of every call, in milliseconds |

**Connection (HTTP)**

| Setting | Default | Meaning |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | the service's base URL; request paths are resolved against it |
| `timeoutMs` | `5000` | the timeout of every request without its own, in milliseconds; also the connect timeout |

**Run**

| Setting | Default | Meaning |
|---|---|---|
| `scenario` | the first added | which scenario to run |
| `preset` | none | which preset to apply |
| `local` | `false` | the test prepares a local instance itself in `beforeAll`; see [Local runs](#local-runs) |

Settings the load test declares itself (`intParam(...)` and the like) are set the same way.

## Pass/fail rules

In `steady` mode the test fails if any of these rules is broken. In `stress` mode nothing is checked.

- In the steady phase, the p99 of every step is below `p99Ms` and its maximum is below `maxMs`.
  `stepP99Limits()` can set a different p99 limit for a particular step.
- In all phases, the share of failed calls of every step is at most `maxErrorRate` (by default, no call may
  fail).
- In the steady phase, the p99 delay with which the test started runs is below `maxClientLagMs`. If the test
  machine is overloaded, runs start late, and the results then describe the test machine rather than the
  service.
- No runs were dropped because of `maxInFlight`, and every run finished by the end of the test.

Latency includes failed calls: a call that hit the deadline after 5 seconds counts as 5 seconds.

## Reports

Every run writes its reports to `build/reports/loadtest/<start time>/`:

- `report.html`: tables per phase and step (successes, failures, p50/p95/p99/max), a table of failures with
  their messages, and charts of latency and throughput per second, separately for every step;
- `summary.json`: the same data in machine-readable form, for CI;
- the console: a summary per phase and the result of every rule.

How to read the most common results:

| You see | It means |
|---|---|
| `DEADLINE_EXCEEDED` | a call took longer than `deadlineMs` |
| `UNAVAILABLE` | the connection failed or the service reported itself unavailable; the root cause follows `<-` in the message |
| `HTTP_<status>` | the response had a status the step did not expect; the message starts with the response body |
| `TIMEOUT`, `CONNECT_TIMEOUT` | an HTTP request or connection took longer than `timeoutMs` |
| `CONNECTION_REFUSED` | nothing accepted the HTTP connection |
| a failure named after an exception class | the scenario's own check failed, not the call |
| client start lag p99 in the tens of milliseconds or more | the test machine is overloaded; run the test on another machine or lower the rate |
| the number of runs in progress and p99 keep growing, while successes per second do not | the service has reached its throughput limit |

## Local runs

With `-Dlocal=true` the load test can start the service from the project itself. The library does not start
databases or message brokers: they must already be running. Everything the test starts is stopped after the
run, also when it fails or is interrupted with Ctrl+C.

This is done in `beforeAll`:

```kotlin
override fun beforeAll(context: LoadTestContext) {
    if (!context.isLocal) return
    val env = context.environment
    env.requireListening("PostgreSQL", "localhost", 5432)                // stop with a clear message if it is down
    val billing = env.fakeHttp().post("/charge") { FakeResponse.json("""{"ok":true}""") }   // a fake HTTP dependency
    springGrpcService("orders-service")                                  // the service, gRPC on -Dport
        .springConfig("classpath:/application.yaml", "local-service.yaml")
        .property("billing.url", billing.baseUrl)
        .start()
}
```

- `springGrpcService(name)` starts the project's Spring Boot application in a separate JVM, with its gRPC
  server on `-Dport`, and waits until that port answers. Its output goes to `build/loadtest/<name>.log`.
  In an HTTP load test, `springHttpService(name)` does the same with the HTTP server (`server.port`) on the
  port of `baseUrl`.
- `springConfig(...)` lists the configuration files the service starts with; later files override earlier
  ones. `classpath:/...` is a file of the service, a plain name is a file in `src/loadTest/resources`.
- `local-service.yaml` contains only what the test run changes in the service's configuration, for example
  a free management port or a test account for a local broker:

  ```yaml
  management:
    server:
      port: 0          # a free port, so an instance that is already running does not get in the way
  ```

- `property(name, value)` overrides one property with a value known only at run time, such as the URL of a fake
  dependency.

If nothing listens on `host:port` (or on the host and port of `baseUrl`) when the load starts, the run stops at
once with a message saying so.

## Building the library

```bash
./gradlew build                  # compile and run the tests
./gradlew publishToMavenLocal    # publish to the local Maven repository, see Getting the library
```

`./gradlew publish` publishes to the `diia` Maven repository instead (`-PdiiaMavenUrl`, with the token in
`-DmavenToken` or `MAVEN_TOKEN`); a service then resolves the library from that repository in place of
`mavenLocal()`.

The library consists of four modules: `loadtest-core` (the engine, reports and local runs; it does not depend
on gRPC), `loadtest-grpc` (gRPC support), `loadtest-http` (HTTP support, on the JDK HTTP client) and
`loadtest-gradle-plugin` (the plugin `ua.gov.diia.loadtest`).
The libraries require Java 21; the plugin runs on Java 17 or newer.