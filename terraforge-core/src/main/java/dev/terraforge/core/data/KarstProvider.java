package dev.terraforge.core.data;

import dev.terraforge.core.coord.GeoPoint;
import java.util.Optional;

/** Prepared evidence of where soluble rock occurs and where a surveyed entrance is mapped. */
public interface KarstProvider {
    boolean contains(double latitude, double longitude);

    Optional<GeoPoint> nearestEntrance(double latitude, double longitude, double maximumDistanceDegrees);

    static KarstProvider absent() {
        return new KarstProvider() {
            @Override public boolean contains(double latitude, double longitude) { return false; }
            @Override public Optional<GeoPoint> nearestEntrance(double latitude, double longitude,
                                                                  double maximumDistanceDegrees) {
                return Optional.empty();
            }
        };
    }
}
