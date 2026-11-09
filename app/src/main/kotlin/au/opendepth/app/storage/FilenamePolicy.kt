package au.opendepth.app.storage

object FilenamePolicy {

    private const val MAX_LENGTH = 100

    /**
     * Reduces an untrusted display name to something safe to use as a file
     * name inside app-private storage.
     */
    fun safeBaseName(value: String, fallback: String = "layer"): String = value
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim(' ', '.')
        .take(MAX_LENGTH)
        .ifBlank { fallback }

    /** Builds a unique, timestamped fallback name for an unnamed import. */
    fun timestampedFallback(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}"
}
