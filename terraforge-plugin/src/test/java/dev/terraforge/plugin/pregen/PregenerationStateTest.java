package dev.terraforge.plugin.pregen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PregenerationStateTest {
    @Test
    void permitsOnlyDocumentedLifecycleTransitions() {
        assertThat(PregenerationState.PAUSED.mayTransitionTo(PregenerationState.RUNNING)).isTrue();
        assertThat(PregenerationState.RUNNING.mayTransitionTo(PregenerationState.AUTO_PAUSED)).isTrue();
        assertThat(PregenerationState.AUTO_PAUSED.mayTransitionTo(PregenerationState.RUNNING)).isTrue();
        assertThat(PregenerationState.RUNNING.mayTransitionTo(PregenerationState.COMPLETED)).isTrue();
        assertThat(PregenerationState.RUNNING.mayTransitionTo(PregenerationState.CANCELLED)).isTrue();
        assertThat(PregenerationState.COMPLETED.mayTransitionTo(PregenerationState.RUNNING)).isFalse();
        assertThat(PregenerationState.CANCELLED.mayTransitionTo(PregenerationState.RUNNING)).isFalse();
    }
}
