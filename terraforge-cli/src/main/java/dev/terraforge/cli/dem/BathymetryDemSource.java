package dev.terraforge.cli.dem;

import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;

/** Marks a source as GEBCO data without changing its native grid or values. */
public final class BathymetryDemSource implements DemSource {

    private final DemSource delegate;

    public BathymetryDemSource(DemSource delegate) {
        this.delegate = delegate;
    }

    @Override public DemTileKey key() { return delegate.key(); }
    @Override public int width() { return delegate.width(); }
    @Override public int height() { return delegate.height(); }
    @Override public double[] readRow(int y) throws IOException { return delegate.readRow(y); }
    @Override public boolean needsFloat32() { return delegate.needsFloat32(); }
    @Override public boolean containsBathymetry() { return true; }
    @Override public void close() throws IOException { delegate.close(); }
}
