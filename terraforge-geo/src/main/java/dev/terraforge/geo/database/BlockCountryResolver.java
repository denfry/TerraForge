package dev.terraforge.geo.database;

import dev.terraforge.core.api.CountryHit;
import dev.terraforge.core.api.GeoPointResolver;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link GeoPointResolver} that converts block coordinates back to geography and looks the
 * country up in the spatial index.
 */
public final class BlockCountryResolver implements GeoPointResolver {

    private final SqliteBoundaryIndex index;
    private final CoordinateTransformer transformer;

    public BlockCountryResolver(SqliteBoundaryIndex index, CoordinateTransformer transformer) {
        this.index = Objects.requireNonNull(index, "index");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
    }

    @Override
    public Optional<CountryHit> countryAt(int blockX, int blockZ) {
        GeoPoint point = transformer.toGeographic(blockX, blockZ);
        return index.countryAt(point.latitude(), point.longitude())
                .map(country -> new CountryHit(country.isoCode(), country.name()));
    }
}
