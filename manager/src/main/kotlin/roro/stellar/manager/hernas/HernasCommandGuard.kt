package roro.stellar.manager.hernas

/**
 * Parses a Hernas relay job's `command` shell string into a validated structured
 * intent and a fixed argv, enforcing the ADB-equivalent allowlist before anything
 * is executed.
 *
 * Pure Kotlin with no Android imports so it can be unit-tested off-device.
 *
 * Security model:
 *  - The raw string is never passed to a shell. We tokenize it, match it against
 *    a fixed allowlist, and build argv arrays ourselves.
 *  - Shell metacharacters (redirection, chaining, substitution) are rejected
 *    outright; there is no env expansion and no escape sequences.
 *  - Unknown ops, unknown flags, too many arguments and bad argument shapes are
 *    rejected with a human-readable reason.
 */
object HernasCommandGuard {

    const val MAX_RAW_LENGTH = 512
    const val EXIT_REJECTED = 125

    sealed class Decision {
        /** `steps` is an ordered sequence of argv arrays run in-code (not a shell chain). */
        data class Allowed(
            val op: String,
            val steps: List<List<String>>,
            val needsConfirmation: Boolean,
            val description: String,
        ) : Decision()

        data class Rejected(val reason: String) : Decision()
    }

    // Characters that must never appear in a job command. Covers command
    // chaining (;&|), redirection (<>), substitution ($(`) and quoting
    // escapes (\) and control characters.
    private val FORBIDDEN = charArrayOf(
        ';', '&', '|', '`', '$', '(', ')', '<', '>', '\\', '{', '}', '^', '~',
        '\n', '\r', '\t', '\u0000'
    )

    private val KEY_REGEX = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val ABS_PATH_REGEX = Regex("^[A-Za-z0-9._/-]{2,200}$")
    private val PKG_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val TEXT_REGEX = Regex("^[A-Za-z0-9_@#%*()+.,:!?/=\\-]{1,200}$")

    // Packages the relay must never touch: system components and Stellar itself.
    private val PKG_BLOCKLIST = listOf(
        "android",
        "com.android",
        "com.google.android",
        "roro.stellar",
        "com.stellar",
    )

    fun decide(raw: String): Decision {
        val command = raw.trim()
        if (command.isEmpty()) return Decision.Rejected("empty command")
        if (command.length > MAX_RAW_LENGTH) {
            return Decision.Rejected("command too long (${command.length} > $MAX_RAW_LENGTH)")
        }
        FORBIDDEN.forEach { c ->
            if (command.indexOf(c) >= 0) {
                return Decision.Rejected("forbidden character ${nameOf(c)}")
            }
        }

        val tokens = tokenize(command)
            ?: return Decision.Rejected("bad quoting (unclosed or nested quote)")

        return when (tokens[0]) {
            "device_info" -> requireNoArgs(tokens, 1)?.let { reject(it) }
                ?: Decision.Allowed("device_info", listOf(listOf("getprop")), false, "dump all system properties")

            "getprop" -> when (tokens.size) {
                1 -> Decision.Allowed("getprop", listOf(listOf("getprop")), false, "dump all system properties")
                2 -> if (KEY_REGEX.matches(tokens[1])) {
                    Decision.Allowed("getprop", listOf(listOf("getprop", tokens[1])), false, "read property ${tokens[1]}")
                } else {
                    reject("invalid property key")
                }
                else -> reject("getprop takes at most one key")
            }

            "install" -> {
                if (tokens.size != 2) return reject("install takes exactly one apk path")
                val path = tokens[1]
                if (!path.startsWith("/")) return reject("apk path must be absolute")
                if (path.contains("..")) return reject("apk path must not contain '..'")
                if (!ABS_PATH_REGEX.matches(path)) return reject("invalid apk path")
                Decision.Allowed("install", listOf(listOf("pm", "install", "-r", path)), true, "install $path")
            }

            "uninstall" -> {
                if (tokens.size != 2) return reject("uninstall takes exactly one package name")
                val pkg = tokens[1]
                if (!PKG_REGEX.matches(pkg)) return reject("invalid package name")
                if (PKG_BLOCKLIST.any { pkg == it || pkg.startsWith("$it.") }) {
                    return reject("refusing to uninstall system or Stellar package")
                }
                Decision.Allowed("uninstall", listOf(listOf("pm", "uninstall", pkg)), true, "uninstall $pkg")
            }

            "logcat" -> when (tokens.size) {
                1 -> Decision.Allowed("logcat", listOf(listOf("logcat", "-d")), false, "dump logcat")
                2 -> when (tokens[1]) {
                    "-d", "dump" -> Decision.Allowed("logcat", listOf(listOf("logcat", "-d")), false, "dump logcat")
                    "-c", "clear" -> Decision.Allowed("logcat", listOf(listOf("logcat", "-c")), false, "clear logcat")
                    else -> reject("unsupported logcat argument")
                }
                else -> reject("logcat takes at most one argument (-d or -c)")
            }

            "input" -> parseInput(tokens)

            "screencap" -> {
                if (tokens.size != 1) return reject("screencap takes no arguments")
                // Fixed path in shell-writable /data/local/tmp; jobs are handled serially.
                Decision.Allowed(
                    "screencap",
                    listOf(listOf("screencap", "-p", "/data/local/tmp/hernas_screencap.png")),
                    false,
                    "capture screenshot to /data/local/tmp/hernas_screencap.png",
                )
            }

            "uiautomator" -> {
                if (tokens.size != 2 || tokens[1] != "dump") {
                    return reject("only 'uiautomator dump' is supported")
                }
                // Two steps composed in code (not by a shell): dump, then read the file back.
                Decision.Allowed(
                    "uiautomator",
                    listOf(
                        listOf("uiautomator", "dump", "/data/local/tmp/hernas_ui.xml"),
                        listOf("cat", "/data/local/tmp/hernas_ui.xml"),
                    ),
                    false,
                    "dump UI hierarchy",
                )
            }

            else -> reject("unknown operation '${tokens[0]}'")
        }
    }

    private fun parseInput(tokens: List<String>): Decision {
        if (tokens.size < 2) return reject("input needs a subcommand (tap/swipe/text)")
        return when (tokens[1]) {
            "tap" -> {
                if (tokens.size != 4) return reject("input tap takes x y")
                val (x, y) = intArgs(tokens[2], tokens[3]) ?: return reject("tap coordinates must be integers 0..9999")
                Decision.Allowed("input", listOf(listOf("input", "tap", x, y)), false, "tap ($x, $y)")
            }

            "swipe" -> {
                if (tokens.size !in 6..7) return reject("input swipe takes x1 y1 x2 y2 [durationMs]")
                val ints = intArgs(*tokens.drop(2).take(4).toTypedArray())
                    ?: return reject("swipe arguments must be integers 0..9999")
                val ms = if (tokens.size == 7) tokens[6] else "300"
                if (ms.toIntOrNull() !in 0..60000) return reject("swipe duration must be 0..60000 ms")
                Decision.Allowed("input", listOf(listOf("input", "swipe", *ints, ms)), false, "swipe ${ints.joinToString(" ")}")
            }

            "text" -> {
                if (tokens.size != 3) return reject("input text takes exactly one text token")
                if (!TEXT_REGEX.matches(tokens[2])) {
                    return reject("text must be 1..200 safe characters (use %s for spaces)")
                }
                Decision.Allowed("input", listOf(listOf("input", "text", tokens[2])), false, "type text")
            }

            else -> reject("unsupported input subcommand '${tokens[1]}' (tap/swipe/text only)")
        }
    }

    private fun intArgs(vararg raws: String): Array<String>? {
        val out = arrayOfNulls<String>(raws.size)
        raws.forEachIndexed { i, s ->
            val v = s.toIntOrNull() ?: return null
            if (v !in 0..9999) return null
            out[i] = v.toString()
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<String>
    }

    /** Whitespace tokenizer honoring single/double quotes. Returns null on bad quoting. */
    internal fun tokenize(raw: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        for (c in raw) {
            when {
                quote != null -> {
                    if (c == quote) {
                        quote = null
                    } else {
                        current.append(c)
                    }
                }

                c == '\'' || c == '"' -> {
                    if (!inToken && current.isEmpty()) inToken = true
                    quote = c
                }

                c.isWhitespace() -> {
                    if (inToken || current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        inToken = false
                    }
                }

                else -> {
                    inToken = true
                    current.append(c)
                }
            }
        }
        if (quote != null) return null
        if (inToken || current.isNotEmpty()) tokens.add(current.toString())
        if (tokens.isEmpty()) return null
        return tokens
    }

    private fun requireNoArgs(tokens: List<String>, expected: Int): String? =
        if (tokens.size != expected) "takes no arguments" else null

    private fun reject(reason: String): Decision.Rejected = Decision.Rejected(reason)

    private fun nameOf(c: Char): String = when (c) {
        '\u0000' -> "NUL"
        '\n' -> "newline"
        '\r' -> "carriage return"
        '\t' -> "tab"
        else -> "'$c'"
    }
}
