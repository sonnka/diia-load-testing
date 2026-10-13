package ua.gov.diia.loadtest

/**
 * Typed setting readers with defaults, resolved through [Config]: `-D` system property, then the active
 * preset, then `loadtest.properties`, then the given default. `./gradlew runLoadTest` (the
 * `ua.gov.diia.loadtest` plugin) forwards every `-D` given on the command line to the test JVM.
 * In a [LoadTestDefinition], prefer declaring a [Parameter]: it shows up in `./gradlew loadTestHelp`.
 */
public object Props {
    @JvmStatic
    public fun get(
        name: String,
        default: String,
    ): String = Config.raw(name) ?: default

    @JvmStatic
    public fun getInt(
        name: String,
        default: Int,
    ): Int = Config.raw(name)?.toInt() ?: default

    @JvmStatic
    public fun getLong(
        name: String,
        default: Long,
    ): Long = Config.raw(name)?.toLong() ?: default

    @JvmStatic
    public fun getDouble(
        name: String,
        default: Double,
    ): Double = Config.raw(name)?.toDouble() ?: default

    @JvmStatic
    public fun getBoolean(
        name: String,
        default: Boolean,
    ): Boolean = Config.raw(name)?.toBooleanStrict() ?: default
}
