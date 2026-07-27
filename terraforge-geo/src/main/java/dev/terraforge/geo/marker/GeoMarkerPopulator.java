package dev.terraforge.geo.marker;

import dev.terraforge.core.api.GeoMarkerService;
import dev.terraforge.core.api.GeoMarkerService.GeoMarker;
import dev.terraforge.core.api.GeoMarkerService.MarkerType;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.geo.City;
import dev.terraforge.core.geo.Country;
import dev.terraforge.core.geo.Region;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fills a {@link GeoMarkerService} from the prepared geographic database.
 *
 * <p>Only the {@code terraforge:} namespace is touched. Markers registered by other plugins through
 * the public API survive every repopulation, and so do Towny's own town and nation markers, which
 * TerraForge never publishes.
 *
 * <p>The gazetteer can hold hundreds of thousands of places. Publishing all of them would make the
 * web map unusable and the marker payload enormous, so cities are ranked by population and capped;
 * capitals are always kept, whatever their population.
 */
public final class GeoMarkerPopulator {

    /** Prefix owned by TerraForge. Everything under it is replaced on each populate. */
    public static final String NAMESPACE = "terraforge:";

    private GeoMarkerPopulator() {
    }

    /**
     * Publication limits.
     *
     * @param cityMarkers   publish cities and capitals
     * @param countryLabels publish country and region labels
     * @param maxCities     upper bound on non-capital city markers; capitals are never dropped
     * @param minPopulation smallest population a non-capital city needs to be published; a city
     *                      whose population is unknown (0) passes only when this is 0
     */
    public record Options(boolean cityMarkers, boolean countryLabels, int maxCities, long minPopulation) {

        public Options {
            if (maxCities < 0) {
                throw new IllegalArgumentException("maxCities must not be negative: " + maxCities);
            }
            if (minPopulation < 0) {
                throw new IllegalArgumentException("minPopulation must not be negative: " + minPopulation);
            }
        }

        /** Sensible defaults for a web map: every capital, the 2000 largest other cities. */
        public static Options defaults(boolean cityMarkers, boolean countryLabels) {
            return new Options(cityMarkers, countryLabels, 2_000, 0);
        }
    }

    /**
     * Replaces every {@code terraforge:} marker with the current contents of the database.
     *
     * @return the number of markers published
     */
    public static int populate(GeoMarkerService markers, SqliteBoundaryIndex geography, Options options) {
        Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(options, "options");
        clear(markers);
        if (geography == null) {
            return 0;
        }

        Map<Integer, Country> countriesById = new HashMap<>();
        for (Country country : geography.countries()) {
            countriesById.put(country.id(), country);
        }

        int published = 0;
        if (options.countryLabels()) {
            for (Country country : geography.countries()) {
                markers.register(countryMarker(country));
                published++;
            }
            for (Region region : geography.regions()) {
                markers.register(regionMarker(region, countriesById.get(region.countryId())));
                published++;
            }
        }
        if (options.cityMarkers()) {
            for (City city : selectCities(geography.cities(), options)) {
                markers.register(cityMarker(city, countriesById.get(city.countryId())));
                published++;
            }
        }
        return published;
    }

    /** Removes every marker TerraForge owns, leaving third-party registrations alone. */
    public static int clear(GeoMarkerService markers) {
        Objects.requireNonNull(markers, "markers");
        List<String> owned = markers.all().stream()
                .map(GeoMarker::id)
                .filter(id -> id.startsWith(NAMESPACE))
                .toList();
        owned.forEach(markers::unregister);
        return owned.size();
    }

    /**
     * Capitals first and unconditionally, then the largest remaining cities up to the cap.
     *
     * <p>Ordering is by population descending and name ascending, so the published set is
     * deterministic even when populations tie.
     */
    private static List<City> selectCities(List<City> cities, Options options) {
        List<City> capitals = cities.stream().filter(City::capital).toList();
        List<City> rest = cities.stream()
                .filter(city -> !city.capital())
                // population 0 means "unknown", so it only survives a min-population of 0
                .filter(city -> city.population() >= options.minPopulation())
                .sorted(Comparator.comparingLong(City::population).reversed().thenComparing(City::name))
                .limit(options.maxCities())
                .toList();
        return java.util.stream.Stream.concat(capitals.stream(), rest.stream()).toList();
    }

    private static GeoMarker cityMarker(City city, Country country) {
        return new GeoMarker(
                NAMESPACE + (city.capital() ? "capital/" : "city/") + city.id(),
                city.name(),
                city.capital() ? MarkerType.CAPITAL : MarkerType.CITY,
                city.position(),
                cityDetail(city, country));
    }

    private static GeoMarker countryMarker(Country country) {
        GeoPoint anchor = country.bounds().center();
        return new GeoMarker(
                NAMESPACE + "country/" + country.id(),
                country.name(),
                MarkerType.COUNTRY,
                anchor,
                country.isoCode());
    }

    private static GeoMarker regionMarker(Region region, Country country) {
        return new GeoMarker(
                NAMESPACE + "region/" + region.id(),
                region.name(),
                MarkerType.REGION,
                region.bounds().center(),
                country == null ? "" : country.name());
    }

    private static String cityDetail(City city, Country country) {
        StringBuilder detail = new StringBuilder();
        if (city.capital()) {
            detail.append("Capital");
        }
        if (country != null) {
            if (detail.length() > 0) {
                detail.append(" of ");
            }
            detail.append(country.name());
        }
        city.populationIfKnown().ifPresent(population -> {
            if (detail.length() > 0) {
                detail.append(" -- ");
            }
            detail.append(String.format(Locale.ROOT, "population %,d", population));
        });
        return detail.toString();
    }
}
