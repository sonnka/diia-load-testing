package ua.gov.diia.loadtest

import ua.gov.diia.loadtest.local.LocalEnvironment

/** One run of a [LoadTestDefinition]: what was selected, and the resources to release when it is over. */
public class LoadTestContext internal constructor(
    public val title: String,
    public val scenario: String,
    public val mode: LoadMode,
    public val profile: LoadProfile,
    public val isLocal: Boolean,
) : AutoCloseable {
    private val resources = ArrayDeque<AutoCloseable>()
    private var closed = false

    /** Local dependency checks, fake HTTP servers and service processes; all stopped after the run. */
    public val environment: LocalEnvironment by lazy { closeLater(LocalEnvironment()) }

    /** Closes [resource] after the run (also on failure or Ctrl+C), in reverse order of registration. */
    @Synchronized
    public fun <T : AutoCloseable> closeLater(resource: T): T {
        check(!closed) { "The run is over" }
        resources.addLast(resource)
        return resource
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        while (resources.isNotEmpty()) {
            try {
                resources.removeLast().close()
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }
}
