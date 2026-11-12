package com.hidgamepad

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Report a problem": publish a short message to an [ntfy](https://ntfy.sh) topic, which
 * arrives as a push notification on the maintainer's phone.
 *
 * The topic is entered by the user and stored on the device. It is deliberately **not** built
 * into the app: the repository is public, and a topic hard-coded here would let anyone who
 * reads the source read the reports and post fake ones.
 *
 * Only what the user typed is sent, plus app diagnostics. Bluetooth addresses and
 * bonded-device names are never included — they identify other people's devices.
 *
 * Everything in this file is pure: no Compose, no Android context, no network. The network
 * call itself lives in [ProblemReporter].
 */

/** The ntfy server the reports are published to. */
const val NTFY_BASE_URL = "https://ntfy.sh"

/** Separates the user's description from the diagnostics block. */
const val REPORT_SEPARATOR = "---"

/** The title shown on the push notification. */
const val REPORT_TITLE = "HID Controller — problem report"

/** The ntfy topic name format: letters, digits, hyphen and underscore. */
private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * The URL a report is published to, or null when the topic is not usable.
 *
 * A topic is a path segment on the server, so anything that could escape it or inject
 * another request line must be rejected before it reaches the network.
 */
fun ntfyTopicUrl(topic: String): String? {
    val trimmed = topic.trim()
    if (!TOPIC_PATTERN.matches(trimmed)) return null
    return "$NTFY_BASE_URL/$trimmed"
}

/** True when the topic can be used. */
fun isValidTopic(topic: String): Boolean = ntfyTopicUrl(topic) != null

/**
 * The topic to report to, or null when there is none and the user has to supply one.
 *
 * The build-time topic wins: it is the maintainer's own, compiled in from `local.properties`.
 * A stored topic is the fallback for a build that did not carry one.
 */
fun resolveReportTopic(buildTopic: String, storedTopic: String): String? {
    val build = buildTopic.trim()
    if (build.isNotEmpty()) return build
    val stored = storedTopic.trim()
    if (stored.isNotEmpty()) return stored
    return null
}

/**
 * The message body: what the user wrote, optionally followed by the details.
 *
 * The details go *below* the description so the notification preview shows the user's own
 * words first.
 */
fun buildReportBody(description: String, diagnostics: String?): String {
    val text = description.trim()
    if (diagnostics.isNullOrBlank()) return text
    return "$text\n\n$REPORT_SEPARATOR\n${diagnostics.trim()}"
}

/**
 * The reason a publish failed, or null when it succeeded.
 *
 * The messages are aimed at the person who pressed the button, not at a network engineer,
 * and they deliberately do not name the service the report is delivered through: this screen
 * is visible in a published app, and the delivery mechanism is not the user's business.
 */
fun reportFailureReason(code: Int): String? = when {
    code in 200..299 -> null
    code == 403 -> "The report could not be delivered. Check the topic name is correct."
    code == 404 -> "The report could not be delivered. The recipient's topic is not set up."
    code == 413 -> "The report is too long. Shorten the description."
    code == 429 -> "Too many reports sent in a short time. Wait a moment and try again."
    code == -1 -> "Check your device connection pig"
    else -> "The report could not be delivered. Try again later."
}

/**
 * The reason a report must not be sent, or null when it may go.
 *
 * Both checks happen here so that a bad topic or an empty description never reaches the
 * network: the first would put an unvalidated path segment on a remote server, and the
 * second would post an empty notification.
 */
fun reportPreflightFailure(topic: String, body: String): String? = when {
    ntfyTopicUrl(topic) == null -> "The topic name is not valid."
    body.isBlank() -> "Describe the problem first."
    else -> null
}

/** The outcome of a report attempt. */
sealed interface ReportOutcome {
    data object Sent : ReportOutcome
    data class Failed(val reason: String) : ReportOutcome
}

/**
 * Publishes the report.
 *
 * Uses [HttpURLConnection] rather than a library: the app has no HTTP dependency, and one
 * POST does not justify adding one. The call is made on the IO dispatcher so the UI thread is
 * never blocked.
 */
object ProblemReporter {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun send(
        topic: String,
        title: String,
        body: String,
    ): ReportOutcome = withContext(Dispatchers.IO) {
        // Both checks happen before anything touches the network: an unvalidated topic would
        // put an arbitrary path segment on a remote server, and an empty body would post an
        // empty notification.
        reportPreflightFailure(topic, body)?.let { return@withContext ReportOutcome.Failed(it) }

        val url = ntfyTopicUrl(topic) ?: return@withContext ReportOutcome.Failed(
            "The topic name is not valid."
        )

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Title", title)
                setRequestProperty("Priority", "default")
                setRequestProperty("Tags", "warning")
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val reason = reportFailureReason(connection.responseCode)
            if (reason == null) {
                ReportOutcome.Sent
            } else {
                ReportOutcome.Failed(reason)
            }
        } catch (e: IOException) {
            ReportOutcome.Failed(
                reportFailureReason(-1) ?: "Could not reach the report service."
            )
        } catch (e: SecurityException) {
            ReportOutcome.Failed("The app is not allowed to use the internet.")
        } finally {
            connection?.disconnect()
        }
    }
}
