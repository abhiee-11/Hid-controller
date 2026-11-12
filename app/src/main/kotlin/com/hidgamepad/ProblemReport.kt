package com.hidgamepad

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
 * The messages are aimed at the person who pressed the button, not at a network engineer.
 */
fun reportFailureReason(code: Int): String? = when {
    code in 200..299 -> null
    code == 403 -> "The ntfy server rejected the topic. Check the topic name is correct."
    code == 404 -> "The ntfy topic does not exist. Subscribe to it in the ntfy app first."
    code == 413 -> "The report is too long. Shorten the description."
    code == 429 -> "Too many reports sent in a short time. Wait a moment and try again."
    code == -1 -> "No connection to the ntfy server. Check the phone's internet connection."
    else -> "The ntfy server returned an error ($code). Try again later."
}

/**
 * The reason a report must not be sent, or null when it may go.
 *
 * Both checks happen here so that a bad topic or an empty description never reaches the
 * network: the first would put an unvalidated path segment on a remote server, and the
 * second would post an empty notification.
 */
fun reportPreflightFailure(topic: String, body: String): String? = when {
    ntfyTopicUrl(topic) == null -> "The ntfy topic name is not valid."
    body.isBlank() -> "Describe the problem first."
    else -> null
}
