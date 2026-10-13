package ua.gov.diia.loadtest

import ua.gov.diia.loadtest.local.LocalEnvironmentException
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/**
 * Runs a [LoadTestDefinition]: `LoadTestMain <definition class> [--help]`, or the class in the
 * `loadtest.definition` system property. This is the main class of `./gradlew runLoadTest` and
 * `./gradlew loadTestHelp` when the plugin's `definition` is set.
 */
public object LoadTestMain {
    public const val DEFINITION_PROPERTY: String = "loadtest.definition"
    private const val HELP = "--help"

    @JvmStatic
    public fun main(args: Array<String>) {
        val className =
            args.firstOrNull { !it.startsWith("--") }
                ?: System.getProperty(DEFINITION_PROPERTY)
                ?: fail("Usage: LoadTestMain <definition class> [$HELP]")
        val definition =
            try {
                load(className)
            } catch (e: IllegalArgumentException) {
                fail(e.message)
            }
        if (HELP in args) {
            print(help(definition))
            return
        }
        try {
            run(definition)
        } catch (e: LoadTestFailedException) {
            fail(e.message)
        } catch (e: IllegalArgumentException) {
            fail(e.message) // a bad setting or scenario name: the message says which
        } catch (e: LocalEnvironmentException) {
            fail(e.message)
        }
        exitProcess(0) // whatever a dependency left running must not keep the JVM alive
    }

    /** The definition named [className]: a Kotlin `object`, or a class with a no-argument constructor. */
    @JvmStatic
    public fun load(className: String): LoadTestDefinition {
        val type =
            try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                throw IllegalArgumentException("Load test definition class '$className' not found on the load test classpath", e)
            }
        require(LoadTestDefinition::class.java.isAssignableFrom(type)) {
            "$className does not extend ${LoadTestDefinition::class.java.name}"
        }
        val objectInstance = type.declaredFields.firstOrNull { it.name == "INSTANCE" && it.type == type && Modifier.isStatic(it.modifiers) }
        val instance =
            objectInstance?.get(null)
                ?: runCatching { type.getDeclaredConstructor() }
                    .getOrElse { throw IllegalArgumentException("$className needs a no-argument constructor (or be a Kotlin object)", it) }
                    .apply { isAccessible = true }
                    .newInstance()
        return instance as LoadTestDefinition
    }

    /**
     * Runs [definition] end to end: settings, [LoadTestDefinition.beforeAll], the selected scenario,
     * [LoadTestDefinition.afterAll], assertions, reports. Throws [LoadTestFailedException] when the
     * assertions fail; resources registered on the context are closed in every case.
     */
    @JvmStatic
    public fun run(definition: LoadTestDefinition): ReportFiles {
        val settings = ProfileSettings.fromSystemProperties()
        val scenarioName = definition.selectedScenario()
        val profile = definition.profile()
        val context = LoadTestContext(definition.title, scenarioName, settings.mode, profile, definition.local.value)
        val cleanup = Thread(context::close, "loadtest-cleanup")
        Runtime.getRuntime().addShutdownHook(cleanup)
        definition.bind(context)
        try {
            println(header(definition, context))
            definition.beforeAll(context)
            val scenario = definition.createScenario(context)
            val options =
                RunOptions(
                    maxInFlight = definition.maxInFlight.value,
                    failureClassifier = definition.failureClassifier(),
                )
            val result = runLoadTest(scenario, profile, options)
            definition.afterAll(context, result)
            val verdict = definition.assertions(context)?.verify(result)
            return result.finish("${definition.title} ($scenarioName)", verdict)
        } finally {
            try {
                context.close()
            } finally {
                definition.bind(null)
                runCatching { Runtime.getRuntime().removeShutdownHook(cleanup) } // fails if the JVM is already exiting
            }
        }
    }

    /** What `./gradlew loadTestHelp` prints. */
    @JvmStatic
    public fun help(definition: LoadTestDefinition): String =
        buildString {
            appendLine(definition.title)
            if (definition.description.isNotBlank()) appendLine(definition.description.trimIndent())
            appendLine()
            appendLine("Run:  ./gradlew runLoadTest [-D<name>=<value> ...]   (or -D${Config.PREFIX}<name>=<value>, which wins)")
            appendLine("Settings come from, highest priority first: -D, the preset (-Dpreset=<name>),")
            appendLine("src/loadTest/resources/${Config.FILE}, the default.")
            appendLine()
            appendLine("Scenarios (-D${LoadTestDefinition.SCENARIO}=<name>):")
            val selected = runCatching { definition.selectedScenario() }.getOrNull()
            definition.scenarios.forEach { (name, description) ->
                appendLine(row(name + if (name == selected) " *" else "", description))
            }
            appendLine()
            appendLine("Presets (-D${Config.PRESET}=<name>):")
            Config.presets().toSortedMap().forEach { (name, values) ->
                val marker = if (name == Config.presetName()) " *" else ""
                appendLine(row(name + marker, values.entries.joinToString { "${it.key}=${it.value}" }))
            }
            section("Profile", ProfileSettings.parameters())
            section("Run", definition.runParameters)
            if (definition.parameters.isNotEmpty()) section("Parameters", definition.parameters)
            appendLine()
            appendLine("* = active")
        }

    private fun StringBuilder.section(
        name: String,
        parameters: List<Parameter<*>>,
    ) {
        appendLine()
        appendLine("$name:")
        parameters.forEach { appendLine(row(it.name, describeValue(it).padEnd(VALUE_WIDTH) + " " + it.description)) }
    }

    private fun describeValue(parameter: Parameter<*>): String {
        val value = runCatching { format(parameter.value) }.getOrElse { "<invalid>" }
        return if (parameter.source == "default") value else "$value [${parameter.source}]"
    }

    private fun format(value: Any): String = if (value is Double && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun row(
        name: String,
        text: String,
    ): String = "  ${name.padEnd(NAME_WIDTH)} $text"

    private fun header(
        definition: LoadTestDefinition,
        context: LoadTestContext,
    ): String {
        val all = ProfileSettings.parameters() + definition.runParameters + definition.parameters
        val overridden = all.filter { it.source != "default" }.joinToString { "${it.name}=${format(it.value)} [${it.source}]" }
        return buildString {
            append("${definition.title}: scenario=${context.scenario} mode=${context.mode.name.lowercase()}")
            Config.presetName()?.let { append(" preset=$it") }
            appendLine()
            appendLine("Profile: ${context.profile}")
            if (overridden.isNotEmpty()) append("Settings: $overridden")
        }.trimEnd()
    }

    private fun fail(message: String?): Nothing {
        System.err.println(message)
        exitProcess(1)
    }

    private const val NAME_WIDTH = 18
    private const val VALUE_WIDTH = 30
}
