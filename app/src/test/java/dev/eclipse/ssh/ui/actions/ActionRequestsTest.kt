package dev.eclipse.ssh.ui.actions

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.SessionTab
import org.junit.Test

/**
 * The one-shot handoff both directions, at the level a window and the workspace meet on.
 *
 * The whole reason this exists as a *take* rather than a get is one class of bug: an intent the
 * system re-delivers — a rotation, the recents screen, a relaunch after a crash — must find nothing,
 * and an action delivered twice must not be taken twice. A pause that runs twice is a second write
 * against a transfer the user paused once, and a "why?" window reopened on a spent token is a window
 * about a session that has since been closed. Both are pinned here, since neither is observable from
 * a UI test: the UI can only ever show the first delivery.
 */
class ActionRequestsTest {

    private fun tab(id: String = "tab-1") = SessionTab(
        id = id,
        hostId = "host-1",
        title = "Edge",
        state = SessionConnectionState.ERROR,
        lastError = "Connection refused",
    )

    /** A token names what was stored under it, and only that. */
    @Test
    fun aTokenResolvesToItsOwnSubject() {
        val first = ActionRequests.put(ActionSubject.SessionWhy(tab("tab-1")))
        val second = ActionRequests.put(ActionSubject.SessionWhy(tab("tab-2")))

        assertThat(first).isNotEqualTo(second)
        // Two subjects alive at once, each behind its own token: the windows stack, so the store
        // cannot be one slot the way the answer below is.
        assertThat((ActionRequests.take(first) as ActionSubject.SessionWhy).tab.id).isEqualTo("tab-1")
        assertThat((ActionRequests.take(second) as ActionSubject.SessionWhy).tab.id).isEqualTo("tab-2")
    }

    /** Reading a subject spends it: the re-delivered intent is the case this is for. */
    @Test
    fun aSubjectCannotBeTakenTwice() {
        val token = ActionRequests.put(ActionSubject.SessionWhy(tab()))

        assertThat(ActionRequests.take(token)).isNotNull()
        assertThat(ActionRequests.take(token)).isNull()
    }

    /** And a token nobody minted resolves to nothing rather than throwing. */
    @Test
    fun anUnknownOrMissingTokenResolvesToNothing() {
        assertThat(ActionRequests.take(null)).isNull()
        assertThat(ActionRequests.take("not-a-token")).isNull()
        // The intent a window is launched by is world-writable in the sense that matters here: a
        // hand-written one names an arbitrary string, and it must not reach a subject.
        assertThat(ActionRequests.take(ActionRequests.EXTRA_SUBJECT_TOKEN)).isNull()
    }

    /** The answer is a single slot, and reading it empties it. */
    @Test
    fun anAnswerIsDeliveredOnceAndThenGone() {
        // Drains anything a previous test in this JVM left behind, so the first assertion below is
        // about this test's answer rather than about somebody else's.
        ActionRequests.takeAnswer()

        // No answer at all is the ordinary case — a window closed with the back arrow — and it has
        // to read as "nothing to do" rather than as an error.
        assertThat(ActionRequests.takeAnswer()).isNull()

        ActionRequests.answer(ActionAnswer.TransferAction("transfer-1", TransferActionKind.PAUSE))
        val taken = ActionRequests.takeAnswer()
        assertThat(taken).isEqualTo(ActionAnswer.TransferAction("transfer-1", TransferActionKind.PAUSE))
        assertThat(ActionRequests.takeAnswer()).isNull()
    }

    /**
     * A second answer replaces the first rather than queueing behind it.
     *
     * A slot and not a queue, deliberately: the workspace is resumed between any two windows the user
     * can open by hand, so two answers waiting at once means one of them was written by a process that
     * was never resumed — and acting on the *stale* one, in order, would be acting on the older
     * intent. The newer choice is the one the user made last.
     */
    @Test
    fun theLaterAnswerWins() {
        ActionRequests.takeAnswer()
        ActionRequests.answer(ActionAnswer.TransferAction("transfer-1", TransferActionKind.PAUSE))
        ActionRequests.answer(ActionAnswer.TransferAction("transfer-2", TransferActionKind.CANCEL))

        assertThat(ActionRequests.takeAnswer())
            .isEqualTo(ActionAnswer.TransferAction("transfer-2", TransferActionKind.CANCEL))
    }
}
