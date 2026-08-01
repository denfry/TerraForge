package dev.terraforge.cli.dem;

import dev.terraforge.geo.dem.DemTile;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.MappedDemTile;
import java.io.IOException;
import java.nio.file.Path;

/** Reads a prepared tile as a raw row source so a GEBCO merge never interpolates it. */
public final class PreparedDemSource implements DemSource {

    private final DemTile tile;
    private final double[] row;

    private PreparedDemSource(DemTile tile) {
        this.tile = tile;
        this.row = new double[tile.width()];
    }

    public static PreparedDemSource open(Path file) throws IOException {
        return new PreparedDemSource(MappedDemTile.open(file));
    }

    @Override public DemTileKey key() { return tile.key(); }
    @Override public int width() { return tile.width(); }
    @Override public int height() { return tile.height(); }
    @Override public double[] readRow(int y) {
        for (int x = 0; x < row.length; x++) {
            row[x] = tile.sample(x, y);
        }
        return row;
    }
    @Override public void close() { }
}
