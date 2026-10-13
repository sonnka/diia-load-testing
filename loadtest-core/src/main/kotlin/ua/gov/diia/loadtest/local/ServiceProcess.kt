package ua.gov.diia.loadtest.local

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/**
 * How to start the service under test as a child JVM. By default it is the project's own service:
 * the Gradle plugin passes the main source set's runtime classpath and the main class (`serviceMainClass`,
 * or the one Spring Boot resolves) - so the load test needs nothing but the service's arguments.
 */
public class ServiceLauncher internal constructor(
    private val name: String,
    private val register: (ServiceProcess) -> ServiceProcess,
) {
    private var mainClass: String? = null
    private var classpath: String? = null
    private val jvmArgs = mutableListOf<String>()
    private val args = mutableListOf<String>()
    private val properties = linkedMapOf<String, String>()
    private val env = linkedMapOf<String, String>()
    private val readiness = mutableListOf<Pair<String, BooleanSupplier>>()
    private val ports = mutableListOf<Int>()
    private val failureMarkers = mutableListOf<String>()
    private var timeout: Duration = Duration.ofMinutes(2)
    private var log: Path = Path.of("build", "loadtest", "$name.log")

    public fun mainClass(mainClass: String): ServiceLauncher = apply { this.mainClass = mainClass }

    public fun classpath(classpath: String): ServiceLauncher = apply { this.classpath = classpath }

    public fun jvmArgs(vararg jvmArgs: String): ServiceLauncher = apply { this.jvmArgs += jvmArgs }

    /** Program arguments, e.g. Spring Boot's `--some.property=value`. */
    public fun args(vararg args: String): ServiceLauncher = apply { this.args += args }

    /**
     * A Spring Boot property, passed as `--name=value`: command-line arguments override every config file the
     * service has. Set again, the later value wins.
     */
    public fun property(
        name: String,
        value: Any,
    ): ServiceLauncher = apply { properties[name] = value.toString() }

    /**
     * The Spring Boot config files the service starts with (`spring.config.location`), later ones overriding
     * earlier ones. `classpath:/...` is a file of the service itself; a plain name is a file of the load test
     * (`src/loadTest/resources`), e.g. the service's settings for the run on top of its own config:
     * `springConfig("classpath:/application-example.yaml", "local-service.yaml")`.
     */
    public fun springConfig(vararg files: String): ServiceLauncher =
        apply {
            require(files.isNotEmpty()) { "springConfig needs at least one file" }
            property("spring.config.location", files.joinToString(",") { if (it.contains(':')) it else "file:" + loadTestFile(it) })
        }

    /** A resource of the load test as a file the service can read; copied out first if it is inside a jar. */
    private fun loadTestFile(resource: String): String {
        val loader = Thread.currentThread().contextClassLoader ?: ServiceLauncher::class.java.classLoader
        val file = resource.removePrefix("/")
        val url =
            loader.getResource(file)
                ?: throw LocalEnvironmentException("$name: no $file on the load test classpath (src/loadTest/resources)")
        val path =
            if (url.protocol == "file") {
                Path.of(url.toURI())
            } else {
                log.toAbsolutePath().resolveSibling(file.substringAfterLast('/')).also { copy ->
                    Files.createDirectories(copy.parent)
                    url.openStream().use { Files.copy(it, copy, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        return path.toAbsolutePath().toString().replace('\\', '/')
    }

    public fun env(
        name: String,
        value: String,
    ): ServiceLauncher = apply { env[name] = value }

    /** The service counts as started once something listens on localhost:[port] (e.g. its gRPC server). */
    public fun waitForPort(port: Int): ServiceLauncher =
        apply {
            ports += port
            readiness += "port $port" to BooleanSupplier { LocalEnvironment.isListening(LocalEnvironment.LOCALHOST, port) }
        }

    /** The service counts as started once [check] returns true; checked every 200 ms. */
    public fun waitFor(
        description: String,
        check: BooleanSupplier,
    ): ServiceLauncher = apply { readiness += description to check }

    /**
     * The start fails as soon as the service logs any of [markers] (e.g. Spring Boot's `Application run failed`),
     * instead of waiting out the startup timeout when the failed process does not exit.
     */
    public fun failOnOutput(vararg markers: String): ServiceLauncher = apply { failureMarkers += markers }

    public fun startupTimeout(timeout: Duration): ServiceLauncher = apply { this.timeout = timeout }

    /** Where the service's stdout/stderr go; default `build/loadtest/<name>.log`. */
    public fun log(log: Path): ServiceLauncher = apply { this.log = log }

    /** Starts the process and waits until every readiness check passes; fails with the log's tail if it exits or times out. */
    public fun start(): ServiceProcess {
        ports.filter { LocalEnvironment.isListening(LocalEnvironment.LOCALHOST, it) }.forEach {
            throw LocalEnvironmentException(
                "$name: port $it is already in use on localhost - stop whatever runs there or pick another port",
            )
        }
        Files.createDirectories(log.toAbsolutePath().parent)
        // Everything after `java` goes into an @argfile, which the java launcher parses the same way on every OS.
        // Passed on the command line instead, Windows drops quotes inside arguments (--x=["a"] arrives as --x=[a])
        // and a long classpath can exceed its command line limit.
        val argFile = log.toAbsolutePath().resolveSibling("$name.args")
        val launcherArgs =
            jvmArgs + listOf("-cp", classpath ?: defaultClasspath(), mainClass ?: defaultMainClass()) + args +
                properties.map { (key, value) -> "--$key=$value" }
        Files.writeString(argFile, launcherArgs.joinToString("\n") { quoted(it) })
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@$argFile")
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .apply { environment().putAll(env) }
                .start()
        val service = register(ServiceProcess(name, process, log))
        println("Starting $name (pid ${process.pid()}), log: ${log.toAbsolutePath()}")
        service.awaitReady(readiness, failureMarkers, timeout)
        return service
    }

    /** An @argfile token: in double quotes, with backslashes and quotes escaped. */
    private fun quoted(arg: String): String = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun defaultClasspath(): String {
        val file =
            System.getProperty(CLASSPATH_FILE)
                ?: error("No classpath for $name: run through the loadtest Gradle plugin, or set it with classpath(...)")
        return File(file).readText().trim()
    }

    private fun defaultMainClass(): String =
        System.getProperty(MAIN_CLASS)?.takeIf { it.isNotBlank() }
            ?: System
                .getProperty(MAIN_CLASS_FILE)
                ?.let { File(it) }
                ?.takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: error("No main class for $name: set loadTest { serviceMainClass = \"...\" } in the build, or mainClass(...) here")

    public companion object {
        /** System properties the Gradle plugin sets for [start]'s defaults. */
        public const val CLASSPATH_FILE: String = "loadtest.service.classpathFile"
        public const val MAIN_CLASS: String = "loadtest.service.mainClass"
        public const val MAIN_CLASS_FILE: String = "loadtest.service.mainClassFile"
    }
}

/** A running service started by [ServiceLauncher]; [close] stops it (SIGTERM, then a forced kill after 20 s). */
public class ServiceProcess internal constructor(
    public val name: String,
    private val process: Process,
    public val log: Path,
) : AutoCloseable {
    public val pid: Long get() = process.pid()

    public val isAlive: Boolean get() = process.isAlive

    internal fun awaitReady(
        checks: List<Pair<String, BooleanSupplier>>,
        failureMarkers: List<String>,
        timeout: Duration,
    ) {
        val started = System.nanoTime()
        val deadline = started + timeout.toNanos()
        val pending = checks.toMutableList()
        val output = LogScanner(log, failureMarkers)
        while (pending.isNotEmpty()) {
            if (!process.isAlive) fail("exited with code ${process.exitValue()} before it was ready")
            output.find()?.let { fail("logged \"$it\" while starting") }
            if (System.nanoTime() >
                deadline
            )
                fail("was not ready within ${timeout.toSeconds()} s (waiting for ${pending.joinToString { it.first }})")
            pending.removeAll { it.second.asBoolean }
            if (pending.isNotEmpty()) Thread.sleep(POLL_MS)
        }
        println("$name is up in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms")
    }

    private fun fail(reason: String): Nothing {
        close()
        val tail = runCatching { Files.readAllLines(log).takeLast(TAIL_LINES).joinToString("\n") }.getOrDefault("")
        throw LocalEnvironmentException("$name $reason; last lines of ${log.toAbsolutePath()}:\n$tail")
    }

    override fun close() {
        if (!process.isAlive) return
        val children = process.descendants().toList()
        process.destroy()
        if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        println("Stopped $name")
    }

    override fun toString(): String = "$name (pid $pid)"

    private companion object {
        const val POLL_MS = 200L
        const val STOP_SECONDS = 20L
        const val TAIL_LINES = 40
        const val NANOS_PER_MILLI = 1_000_000
    }
}

/** Reads what a log file gained since the last call and looks for any of [markers] in it. */
private class LogScanner(
    private val log: Path,
    private val markers: List<String>,
) {
    private var position = 0L
    private var carry = ""

    fun find(): String? {
        if (markers.isEmpty() || !Files.exists(log)) return null
        val added =
            RandomAccessFile(log.toFile(), "r").use { file ->
                if (file.length() <= position) return null
                file.seek(position)
                ByteArray((file.length() - position).toInt()).also {
                    file.readFully(it)
                    position += it.size
                }
            }
        val text = carry + added.decodeToString() // a marker may straddle two reads
        carry = text.takeLast(markers.maxOf { it.length })
        return markers.firstOrNull { it in text }
    }
}
