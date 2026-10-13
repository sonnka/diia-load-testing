package ua.gov.diia.loadtest

/**
 * A named load test setting with a default and a description, declared in a [LoadTestDefinition]
 * (`stringParam`, `intParam`, ...). Its [value] is resolved through [Config] on every read, so `-D`,
 * presets and `loadtest.properties` all apply; `./gradlew loadTestHelp` lists every declared parameter
 * with its current value and where that value comes from.
 */
public class Parameter<T : Any> internal constructor(
    public val name: String,
    public val default: T,
    public val description: String,
    private val parse: (String) -> T,
) {
    /** The resolved value; a value that does not parse fails with the parameter's name in the message. */
    public val value: T
        get() {
            val raw = Config.raw(name) ?: return default
            return try {
                parse(raw.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid value '$raw' for $name (${Config.source(name)}): ${e.message}", e)
            }
        }

    /** Where [value] comes from: `-D`, `preset <name>`, `loadtest.properties`, or `default`. */
    public val source: String get() = Config.source(name) ?: "default"

    override fun toString(): String = "$name=$value"

    internal companion object {
        fun string(
            name: String,
            default: String,
            description: String,
        ): Parameter<String> = Parameter(name, default, description) { it }

        fun int(
            name: String,
            default: Int,
            description: String,
        ): Parameter<Int> = Parameter(name, default, description, String::toInt)

        fun long(
            name: String,
            default: Long,
            description: String,
        ): Parameter<Long> = Parameter(name, default, description, String::toLong)

        fun double(
            name: String,
            default: Double,
            description: String,
        ): Parameter<Double> = Parameter(name, default, description, String::toDouble)

        fun boolean(
            name: String,
            default: Boolean,
            description: String,
        ): Parameter<Boolean> = Parameter(name, default, description, String::toBooleanStrict)
    }
}
