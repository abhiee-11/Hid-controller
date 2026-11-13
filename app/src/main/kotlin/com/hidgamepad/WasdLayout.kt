package com.hidgamepad

/**
 * The WASD page's keys, grouped as the page draws them.
 *
 * A keyboard report is all this page sends, so it reuses the same [KeyCap] the on-screen
 * keyboard uses. The grouping mirrors a controller: a left thumb cluster that moves, a right
 * thumb cluster that acts, and a centre column between them. The shoulders sit above.
 */
data class WasdLayout(
    /** Left to right: the keys on the shoulder row. */
    val shoulders: List<KeyCap>,
    /** Left thumb cluster, one inner list per row, top to bottom. */
    val leftCluster: List<List<KeyCap>>,
    /** Right thumb cluster, one inner list per row, top to bottom. */
    val rightCluster: List<List<KeyCap>>,
    /** The centre column, top to bottom. */
    val centre: List<KeyCap>,
) {
    /** Every key on the page, in no particular order. */
    val keys: List<KeyCap>
        get() = shoulders + leftCluster.flatten() + rightCluster.flatten() + centre
}

/**
 * The WASD page: `W A S D` on the left thumb, the action keys a game needs on the right, and
 * `Space`/`Enter` in the middle. Every usage is inside the range the registered descriptor
 * declares, so the page costs nothing on the wire and needs no re-pair.
 */
fun wasdLayout(): WasdLayout = WasdLayout(
    shoulders = listOf(
        cap("Tab", KeyUsage.TAB),
        cap("Esc", KeyUsage.ESC),
    ),
    leftCluster = listOf(
        listOf(KeyCap("Shift", KeyAction.Modifier(MOD_LEFT_SHIFT))),
        listOf(cap("W", KeyUsage.W)),
        listOf(cap("A", KeyUsage.A), cap("S", KeyUsage.S), cap("D", KeyUsage.D)),
    ),
    rightCluster = listOf(
        listOf(KeyCap("Ctrl", KeyAction.Modifier(MOD_LEFT_CTRL))),
        listOf(cap("E", KeyUsage.E), cap("R", KeyUsage.R)),
        listOf(cap("Q", KeyUsage.Q), cap("F", KeyUsage.F)),
    ),
    centre = listOf(
        cap("Enter", KeyUsage.ENTER),
        cap("Space", KeyUsage.SPACE),
    ),
)

private fun cap(label: String, usage: Int) = KeyCap(label, KeyAction.Usage(usage))
