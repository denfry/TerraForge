package dev.terraforge.plugin.pregen;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SpiralCursorTest {
    @Test void startsWithTheDocumentedDeterministicSpiral() {
        assertThat(SpiralCursor.from(0).next(9)).containsExactly(new SpiralCursor.Chunk(0,0), new SpiralCursor.Chunk(1,0), new SpiralCursor.Chunk(1,1), new SpiralCursor.Chunk(0,1), new SpiralCursor.Chunk(-1,1), new SpiralCursor.Chunk(-1,0), new SpiralCursor.Chunk(-1,-1), new SpiralCursor.Chunk(0,-1), new SpiralCursor.Chunk(1,-1));
    }
}
