package dev.terraforge.geo.karst;

import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.geo.index.JtsSpatialIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.locationtech.jts.io.WKBReader;

/** Immutable, startup-loaded karst index; chunk generation never queries SQLite. */
public final class SqliteKarstProvider implements KarstProvider {
    private final JtsSpatialIndex<Boolean> areas;
    private final List<GeoPoint> entrances;
    private SqliteKarstProvider(JtsSpatialIndex<Boolean> areas, List<GeoPoint> entrances) {
        this.areas = areas; this.entrances = List.copyOf(entrances);
    }
    public static KarstProvider load(Path database) throws IOException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            var areas = new ArrayList<JtsSpatialIndex.Entry<Boolean>>(); var entrances = new ArrayList<GeoPoint>();
            var reader = new WKBReader();
            try (var rows = connection.createStatement().executeQuery("SELECT geometry FROM karst_areas ORDER BY id")) {
                while (rows.next()) areas.add(new JtsSpatialIndex.Entry<>(Boolean.TRUE, reader.read(rows.getBytes(1))));
            }
            try (var rows = connection.createStatement().executeQuery("SELECT latitude, longitude FROM cave_entrances ORDER BY id")) {
                while (rows.next()) entrances.add(new GeoPoint(rows.getDouble(1), rows.getDouble(2)));
            }
            return new SqliteKarstProvider(new JtsSpatialIndex<>(areas), entrances);
        } catch (Exception exception) { throw new IOException("Cannot load prepared karst data", exception); }
    }
    @Override public boolean contains(double latitude, double longitude) { return areas.query(latitude, longitude).isPresent(); }
    @Override public Optional<GeoPoint> nearestEntrance(double latitude, double longitude, double maximum) {
        GeoPoint closest = null; double best = maximum * maximum;
        for (GeoPoint entrance : entrances) { double dLat = entrance.latitude() - latitude; double dLon = entrance.longitude() - longitude; double d = dLat*dLat+dLon*dLon; if (d <= best) { best=d; closest=entrance; } }
        return Optional.ofNullable(closest);
    }
}
