package com.extrumz.app

import com.extrumz.app.data.telegram.MissMemo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The miss memo is what stops a coverless row — most rows of a scanned
 * library — from paying two RPCs every time it rebinds during a scroll. Its
 * two failure modes are symmetric and both user-visible: a memo that never
 * expires would swallow a cover that arrives later (an offline fetch that
 * resolves once TDLib reconnects), and one that expires too early puts the
 * scroll back on the hook for the same absent file. The TTL is the contract,
 * so the TTL is what the tests pin down.
 */
class CoverMissMemoTest {

    private var clock = 0L
    private val memo = MissMemo(ttlMs = 5 * 60_000L, now = { clock })

    @Test
    fun `a recorded miss blocks the fetch within the TTL`() {
        memo.record("ht:1:2")

        assertTrue(memo.isMissed("ht:1:2"))
        clock = 5 * 60_000L - 1
        assertTrue("the last millisecond of the TTL still stands", memo.isMissed("ht:1:2"))
    }

    @Test
    fun `an expired miss lets the fetch try again`() {
        memo.record("ht:1:2")
        clock = 5 * 60_000L + 1

        assertFalse(memo.isMissed("ht:1:2"))
    }

    @Test
    fun `one row's miss says nothing about another's`() {
        memo.record("ht:1:2")

        assertFalse(memo.isMissed("ht:1:3"))
        assertFalse(memo.isMissed("hp:1"))
        assertFalse(memo.isMissed("t:1:2"))
    }

    @Test
    fun `re-recording pushes the expiry out again`() {
        memo.record("ht:1:2")
        clock = 4 * 60_000L
        memo.record("ht:1:2") // a second miss inside the window restarts it

        clock = 5 * 60_000L
        assertTrue(memo.isMissed("ht:1:2"))
        clock = 9 * 60_000L + 1
        assertFalse(memo.isMissed("ht:1:2"))
    }
}
