package ua.gov.diia.loadtest

import java.util.Properties

/**
 * Where every load test setting comes from, highest priority first:
 *
 * 1. a `-D` system property (`./gradlew runLoadTest -DtargetRate=300`, or `-DloadTest.targetRate=300`);
 * 2. the active preset (`-Dpreset=smoke`): a built-in one, or `preset.<name>.<key>` lines in `loadtest.properties`;
 * 3. a plain `<key>=<value>` line in `loadtest.properties` (the service's own defaults, on the load test classpath,
 *    i.e. `src/loadTest/resources/loadtest.properties`);
 * 4. the default in code.
 */
public object Config {
    public const val FILE: String = "loadtest.properties"
    public const val PRESET: String = "preset"

    /** Optional `-D` prefix: `-DloadTest.port=9091` wins over `-Dport=...`, for when a name clashes with the service's own. */
    public const val PREFIX: String = "loadTest."
    private const val PRESET_PREFIX = "preset."

    /** Built-in presets, available in every service; a file preset of the same name extends/overrides them. */
    @JvmField
    public val BUILT_IN_PRESETS: Map<String, Map<String, String>> =
        mapOf(
            "smoke" to
                mapOf(
                    "warmupRate" to "5",
                    "warmupSeconds" to "2",
                    "rampSeconds" to "2",
                    "targetRate" to "5",
                    "steadySeconds" to "6",
                ),
        )

    private val file: Properties by lazy {
        Properties().apply {
            val loader = Thread.currentThread().contextClassLoader ?: Config::class.java.classLoader
            loader.getResourceAsStream(FILE)?.use { load(it) }
        }
    }

    /** The resolved value of [name], or null when no source sets it. */
    @JvmStatic
    public fun raw(name: String): String? = systemProperty(name) ?: activePreset()[name] ?: file.getProperty(name)

    /** Where the value of [name] comes from (`-D`, `preset <name>`, `loadtest.properties`), or null when it is the code default. */
    @JvmStatic
    public fun source(name: String): String? =
        when {
            systemProperty(name) != null -> "-D"
            activePreset().containsKey(name) -> "preset ${presetName()}"
            file.getProperty(name) != null -> FILE
            else -> null
        }

    /** Name of the active preset, if any. */
    @JvmStatic
    public fun presetName(): String? = systemProperty(PRESET) ?: file.getProperty(PRESET)

    private fun systemProperty(name: String): String? = System.getProperty(PREFIX + name) ?: System.getProperty(name)

    /** Built-in and file presets, with the settings each one applies. */
    @JvmStatic
    public fun presets(): Map<String, Map<String, String>> {
        val fromFile = mutableMapOf<String, MutableMap<String, String>>()
        file.stringPropertyNames().filter { it.startsWith(PRESET_PREFIX) }.forEach { key ->
            val rest = key.removePrefix(PRESET_PREFIX)
            val dot = rest.indexOf('.')
            if (dot > 0) fromFile.getOrPut(rest.substring(0, dot)) { sortedMapOf() }[rest.substring(dot + 1)] = file.getProperty(key)
        }
        return (BUILT_IN_PRESETS.keys + fromFile.keys).associateWith { name ->
            BUILT_IN_PRESETS[name].orEmpty() + fromFile[name].orEmpty()
        }
    }

    private fun activePreset(): Map<String, String> {
        val name = presetName() ?: return emptyMap()
        val all = presets()
        return requireNotNull(all[name]) { "Unknown preset '$name'; available: ${all.keys.sorted().joinToString()}" }
    }
}
