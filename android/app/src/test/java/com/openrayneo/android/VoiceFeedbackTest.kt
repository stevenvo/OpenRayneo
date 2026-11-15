// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class VoiceFeedbackTest {
    private val ref = AnswerRef(AnswerSource.MOBILE_AGENT, 1)
    private val permit = AnswerPermit(ref, 7)
    private fun ready() = GlassesState(
        intent = DisplayIntent.PromptStandby, link = LinkState(LinkPhase.FULL),
        session = PageSession(1, PageMode.PROMPT, PagePhase.READY, PageContent("standby", "OpenRayneo")),
        revision = 7, answerPermit = permit,
    )
    @Test fun statusUpdatesPreservePendingAnswerAndSession() {
        val feedback = PageContent("thinking", "Thinking", "What is two plus two?")
        val update = GlassesCoordinator.reduce(ready(), GlassesEvent.VoiceFeedbackChanged(feedback))
        assertEquals(permit, update.state.answerPermit)
        assertEquals(7L, update.state.revision)
        assertEquals(1L, update.state.session?.id)
        assertTrue(update.effects.any { it is GlassesEffect.UpdatePage && it.content == feedback })
        assertFalse(update.effects.any { it is GlassesEffect.StartAudio || it is GlassesEffect.StartPage })
        val cleared = GlassesCoordinator.reduce(update.state, GlassesEvent.VoiceFeedbackChanged(null))
        val answered = GlassesCoordinator.reduce(cleared.state, GlassesEvent.AnswerCompleted(AnswerContent(ref, "Question", "Four"), permit))
        assertTrue(answered.state.intent is DisplayIntent.Answer)
        assertEquals("Four", answered.state.session?.content?.answer)
    }
    @Test fun voiceOffCannotReopenAnExternallyClosedPage() {
        val closed = GlassesCoordinator.reduce(ready(), GlassesEvent.ExternalPage("User closed page")).state
        val update = GlassesCoordinator.reduce(closed, GlassesEvent.VoiceFeedbackChanged(PageContent("off", "Voice is off")))
        assertEquals(DisplayOwnership.YIELDED, update.state.ownership)
        assertEquals(DisplayIntent.Idle, update.state.intent)
        assertFalse(update.effects.any { it is GlassesEffect.StartPage })
    }
    @Test fun listeningStatusCanKeepTheLastAnswerVisible() {
        val answering = ready().copy(intent = DisplayIntent.Answer(ref, ReturnTarget.PROMPT_STANDBY), answer = AnswerContent(ref, "Question", "Four"))
        val feedback = PageContent("listening", "Listening", "Four")
        val update = GlassesCoordinator.reduce(answering, GlassesEvent.VoiceFeedbackChanged(feedback))
        assertEquals("Four", update.state.session?.content?.answer)
        assertEquals("Listening", update.state.session?.content?.text)
    }
}
