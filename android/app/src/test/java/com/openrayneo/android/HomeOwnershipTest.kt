package com.openrayneo.android
import org.junit.Assert.*
import org.junit.Test
class HomeOwnershipTest {
    @Test fun connectionDoesNotOpenPageOrMicrophone() {
        val connecting = GlassesCoordinator.reduce(GlassesState(), GlassesEvent.BeginConnection).state
        val connected = GlassesCoordinator.reduce(connecting, GlassesEvent.LinkChanged(LinkState(LinkPhase.FULL)))
        assertEquals(DisplayIntent.Idle, connected.state.intent)
        assertNull(connected.state.session)
        assertFalse(connected.effects.any { it is GlassesEffect.StartPage || it is GlassesEffect.StartAudio })
        val explicit = GlassesCoordinator.reduce(connected.state, GlassesEvent.OpenStandby)
        assertTrue(explicit.effects.any { it is GlassesEffect.StartPage })
    }
}
