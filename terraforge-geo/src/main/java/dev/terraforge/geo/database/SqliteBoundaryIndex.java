package dev.terraforge.geo.database;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.api.GeoService;
import dev.terraforge.core.geodesy.Geodesy;
import dev.terraforge.core.geo.City;
import dev.terraforge.core.geo.Country;
import dev.terraforge.core.geo.Region;
import dev.terraforge.geo.index.JtsSpatialIndex;
import dev.terraforge.geo.index.SpatialIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

/** Read-only country and first-level-region lookup loaded from the prepared SQLite database. */
public final class SqliteBoundaryIndex implements GeoService {

    private final SpatialIndex<Country> countries;
    private final SpatialIndex<Region> regions;
    private final List<Country> countryList;
    private final List<Region> regionList;
    private final List<City> cities;
    private final GeoBounds coverage;

    /**
     * Lower-cased alternate name to city, largest population first.
     *
     * <p>Built once at load time. Without it a player has to type the exact primary name a dataset
     * happens to use -- "München" rather than "Munich" -- which is not a reasonable thing to ask.
     */
    private final Map<String, List<City>> citiesByAlias;

    /**
     * Cities ordered by population, with their names pre-lowercased and positions unboxed.
     *
     * <p>Built once at load time because the alternative is paying for it on every keystroke: name
     * search runs per tab-completion character and the nearest-place lookup runs per
     * {@code /earth whereami}, both on the server thread, over a gazetteer of tens of thousands of
     * entries. Measured by {@code GeographyBenchmark}.
     */
    private final City[] citiesByPopulation;
    private final String[] lowerCaseNames;
    private final double[] latitudes;
    private final double[] longitudes;

    private SqliteBoundaryIndex(SpatialIndex<Country> countries, SpatialIndex<Region> regions,
                                List<Country> countryList, List<Region> regionList, List<City> cities,
                                Map<String, List<City>> citiesByAlias) {
        this.countries = countries;
        this.regions = regions;
        this.countryList = countryList;
        this.regionList = regionList;
        this.cities = cities;
        this.citiesByAlias = citiesByAlias;
        this.coverage = coverageOf(countryList);

        this.citiesByPopulation = cities.stream()
                .sorted(Comparator.comparingLong(City::population).reversed().thenComparing(City::name))
                .toArray(City[]::new);
        this.lowerCaseNames = new String[citiesByPopulation.length];
        this.latitudes = new double[citiesByPopulation.length];
        this.longitudes = new double[citiesByPopulation.length];
        for (int i = 0; i < citiesByPopulation.length; i++) {
            City city = citiesByPopulation[i];
            lowerCaseNames[i] = city.name().toLowerCase(Locale.ROOT);
            latitudes[i] = city.position().latitude();
            longitudes[i] = city.position().longitude();
        }
    }

    public static SqliteBoundaryIndex load(Path database) throws IOException {
        List<JtsSpatialIndex.Entry<Country>> countries = new ArrayList<>();
        List<JtsSpatialIndex.Entry<Region>> regions = new ArrayList<>();
        List<City> cities = new ArrayList<>();
        Map<String, List<City>> aliases;
        String url = "jdbc:sqlite:file:" + database.toAbsolutePath().normalize().toUri().getRawPath() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            readCountries(statement, countries);
            readRegions(statement, regions);
            readCities(statement, cities);
            aliases = readCityAliases(statement, cities);
        } catch (SQLException | ParseException | IllegalArgumentException exception) {
            throw new IOException("Cannot load administrative boundaries from " + database, exception);
        }
        return new SqliteBoundaryIndex(new JtsSpatialIndex<>(countries), new JtsSpatialIndex<>(regions),
                countries.stream().map(JtsSpatialIndex.Entry::value).toList(),
                regions.stream().map(JtsSpatialIndex.Entry::value).toList(), List.copyOf(cities), aliases);
    }

    public Optional<Country> countryAt(double latitude, double longitude) {
        return countries.query(latitude, longitude);
    }

    @Override
    public Optional<Country> getCountry(double latitude, double longitude) {
        return countryAt(latitude, longitude);
    }

    public Optional<Region> regionAt(double latitude, double longitude) {
        return regions.query(latitude, longitude);
    }

    @Override
    public Optional<Region> getRegion(double latitude, double longitude) {
        return regionAt(latitude, longitude);
    }

    public int countryCount() {
        return countries.size();
    }

    /** Finds a country by its two-letter ISO code or exact display name, ignoring case. */
    public Optional<Country> findCountry(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String normalized = query.strip().toUpperCase(Locale.ROOT);
        return countryList.stream()
                .filter(country -> country.isoCode().equalsIgnoreCase(normalized)
                        || country.name().equalsIgnoreCase(query.strip()))
                .findFirst();
    }

    @Override
    public Optional<Country> findCountryByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String normalized = name.strip();
        return countryList.stream().filter(country -> country.name().equalsIgnoreCase(normalized)).findFirst();
    }

    @Override
    public Optional<Country> findCountryByIsoCode(String isoCode) {
        if (isoCode == null || isoCode.isBlank()) return Optional.empty();
        String normalized = isoCode.strip();
        return countryList.stream().filter(country -> country.isoCode().equalsIgnoreCase(normalized)).findFirst();
    }

    @Override
    public List<Country> searchCountries(String prefix, int limit) {
        if (prefix == null || limit <= 0) return List.of();
        String normalized = prefix.strip().toLowerCase(Locale.ROOT);
        return countryList.stream()
                .filter(country -> country.name().toLowerCase(Locale.ROOT).startsWith(normalized)
                        || country.isoCode().toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(java.util.Comparator.comparing(Country::name))
                .limit(limit)
                .toList();
    }

    public int regionCount() {
        return regions.size();
    }

    public int cityCount() { return cities.size(); }

    // --- full listings ------------------------------------------------------
    // Marker publishers (BlueMap, the CLI reports) need the whole dataset, not a point query.
    // All three lists are immutable and built once at load time, so handing them out is free.

    /** Every country in the prepared database, in import order. */
    public List<Country> countries() {
        return countryList;
    }

    /** Every first-level region in the prepared database, in import order. */
    public List<Region> regions() {
        return regionList;
    }

    /** Every gazetteer city in the prepared database, in import order. */
    public List<City> cities() {
        return cities;
    }

    /** Finds a city by primary name first, then by any alternate or ASCII name. */
    public Optional<City> findCity(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        // Population order, so an ambiguous name resolves to the place the player probably meant.
        for (int i = 0; i < citiesByPopulation.length; i++) {
            if (lowerCaseNames[i].equals(normalized)) {
                return Optional.of(citiesByPopulation[i]);
            }
        }
        return citiesByAlias.getOrDefault(normalized, List.of()).stream().findFirst();
    }

    @Override
    public Optional<City> findCityByName(String name) {
        return findCity(name);
    }

    /**
     * Prefix search over primary names and alternate names alike, largest place first.
     *
     * <p>Runs on every tab-completion keystroke, so it walks the pre-lowercased, population-ordered
     * array and stops as soon as it has enough: for the prefixes people actually type it never
     * reaches the end, and it allocates nothing per candidate.
     */
    @Override
    public List<City> searchCities(String prefix, int limit) {
        if (prefix == null || limit <= 0) return List.of();
        String normalized = prefix.strip().toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<City> matches = new java.util.LinkedHashSet<>();
        for (int i = 0; i < citiesByPopulation.length && matches.size() < limit; i++) {
            if (lowerCaseNames[i].startsWith(normalized)) {
                matches.add(citiesByPopulation[i]);
            }
        }
        if (matches.size() < limit) {
            // Alias keys are already lower case, and each list is ordered by population.
            for (Map.Entry<String, List<City>> entry : citiesByAlias.entrySet()) {
                if (!entry.getKey().startsWith(normalized)) {
                    continue;
                }
                for (City city : entry.getValue()) {
                    matches.add(city);
                    if (matches.size() >= limit) {
                        break;
                    }
                }
                if (matches.size() >= limit) {
                    break;
                }
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingLong(City::population).reversed().thenComparing(City::name))
                .toList();
    }

    /**
     * The closest gazetteer place, or empty when none is prepared.
     *
     * <p>Selection uses a flat-earth approximation rather than a geodesic. Vincenty's formula
     * iterates, and running it over every city on the server thread costs tens of milliseconds --
     * measured, not guessed. For picking a nearest neighbour the approximation orders candidates
     * identically at any distance where "nearest place" means anything, and callers that need a
     * real distance compute one geodesic afterwards.
     */
    public Optional<City> nearestCity(double latitude, double longitude) {
        if (!isValid(latitude, longitude) || citiesByPopulation.length == 0) return Optional.empty();
        double scale = Math.cos(Math.toRadians(latitude));
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < citiesByPopulation.length; i++) {
            double candidate = approximateSquaredDegrees(latitude, longitude, latitudes[i], longitudes[i], scale);
            if (candidate < best) {
                best = candidate;
                nearest = i;
            }
        }
        return nearest < 0 ? Optional.empty() : Optional.of(citiesByPopulation[nearest]);
    }

    /** Squared distance in degrees, longitude scaled for latitude. Monotonic in true distance. */
    private static double approximateSquaredDegrees(double latitude, double longitude,
                                                    double cityLatitude, double cityLongitude, double scale) {
        double deltaLatitude = cityLatitude - latitude;
        double deltaLongitude = GeoPoint.normaliseLongitude(cityLongitude - longitude) * scale;
        return deltaLatitude * deltaLatitude + deltaLongitude * deltaLongitude;
    }

    private static boolean isValid(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    @Override
    public Optional<City> getNearestCity(double latitude, double longitude) {
        return nearestCity(latitude, longitude);
    }

    @Override
    public List<City> getNearestCities(double latitude, double longitude, double radiusKm, int limit) {
        if (!Double.isFinite(radiusKm) || radiusKm < 0 || limit <= 0) return List.of();
        if (!isValid(latitude, longitude)) return List.of();
        GeoPoint point = new GeoPoint(latitude, longitude);
        double radiusMeters = radiusKm * 1_000.0;
        // Cheap approximate pre-filter, generous by 20% so no city near the edge is lost, then the
        // exact geodesic on the handful that survive.
        double scale = Math.cos(Math.toRadians(latitude));
        double cutoff = radiusKm / 111.32 * 1.2;
        double squaredCutoff = cutoff * cutoff;
        List<CityDistance> candidates = new ArrayList<>();
        for (int i = 0; i < citiesByPopulation.length; i++) {
            if (approximateSquaredDegrees(latitude, longitude, latitudes[i], longitudes[i], scale) > squaredCutoff) {
                continue;
            }
            City city = citiesByPopulation[i];
            double meters = Geodesy.vincentyMeters(point, city.position());
            if (meters <= radiusMeters) {
                candidates.add(new CityDistance(city, meters));
            }
        }
        candidates.sort(Comparator.comparingDouble(CityDistance::meters));
        return candidates.stream().limit(limit).map(CityDistance::city).toList();
    }

    @Override
    public Optional<GeoPoint> getCountryAnchor(Country country) {
        if (country == null) return Optional.empty();
        return cities.stream()
                .filter(city -> city.countryId() == country.id() && city.capital())
                .max(java.util.Comparator.comparingLong(City::population))
                .map(City::position)
                .or(() -> Optional.of(country.bounds().center()));
    }

    @Override
    public GeoBounds coverage() {
        return coverage;
    }

    private static void readCountries(Statement statement, List<JtsSpatialIndex.Entry<Country>> target)
            throws SQLException, ParseException {
        WKBReader reader = new WKBReader();
        try (ResultSet rows = statement.executeQuery(
                "SELECT id, iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry FROM countries ORDER BY id")) {
            while (rows.next()) {
                Country country = new Country(rows.getInt("id"), rows.getString("iso_code"), rows.getString("name"),
                        bounds(rows));
                target.add(new JtsSpatialIndex.Entry<>(country, geometry(reader, rows)));
            }
        }
    }

    private static void readRegions(Statement statement, List<JtsSpatialIndex.Entry<Region>> target)
            throws SQLException, ParseException {
        WKBReader reader = new WKBReader();
        try (ResultSet rows = statement.executeQuery(
                "SELECT id, country_id, name, min_lat, min_lon, max_lat, max_lon, geometry FROM regions ORDER BY id")) {
            while (rows.next()) {
                Region region = new Region(rows.getInt("id"), rows.getInt("country_id"), rows.getString("name"), bounds(rows));
                target.add(new JtsSpatialIndex.Entry<>(region, geometry(reader, rows)));
            }
        }
    }

    private static void readCities(Statement statement, List<City> target) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT id, name, latitude, longitude, population, country_id, capital FROM cities ORDER BY id")) {
            while (rows.next()) {
                int countryId = rows.getInt("country_id");
                if (rows.wasNull()) countryId = 0;
                target.add(new City(rows.getInt("id"), rows.getString("name"), new GeoPoint(rows.getDouble("latitude"), rows.getDouble("longitude")), rows.getLong("population"), countryId, rows.getInt("capital") != 0));
            }
        }
    }

    /**
     * Reads alternate names for cities, including the ASCII form.
     *
     * <p>The table is optional: a database prepared before alternate names existed simply has none,
     * and lookups fall back to primary names.
     */
    private static Map<String, List<City>> readCityAliases(Statement statement, List<City> cities)
            throws SQLException {
        Map<Integer, City> byId = new java.util.HashMap<>();
        for (City city : cities) {
            byId.put(city.id(), city);
        }
        Map<String, List<City>> aliases = new LinkedHashMap<>();
        try (ResultSet rows = statement.executeQuery(
                "SELECT entity_id, name FROM geographic_names WHERE entity_type = 'city' ORDER BY id")) {
            while (rows.next()) {
                City city = byId.get(rows.getInt("entity_id"));
                String name = rows.getString("name");
                if (city == null || name == null || name.isBlank()) {
                    continue;
                }
                aliases.computeIfAbsent(name.strip().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(city);
            }
        } catch (SQLException exception) {
            return Map.of();
        }
        // Ambiguous aliases resolve to the largest place, which is what a player almost always means.
        aliases.values().forEach(matches ->
                matches.sort(java.util.Comparator.comparingLong(City::population).reversed()));
        return aliases;
    }

    private static GeoBounds bounds(ResultSet rows) throws SQLException {
        return new GeoBounds(rows.getDouble("min_lat"), rows.getDouble("min_lon"),
                rows.getDouble("max_lat"), rows.getDouble("max_lon"));
    }

    private static Geometry geometry(WKBReader reader, ResultSet rows) throws SQLException, ParseException {
        return reader.read(rows.getBytes("geometry"));
    }

    private static GeoBounds coverageOf(List<Country> countries) {
        if (countries.isEmpty()) return GeoBounds.world();
        return new GeoBounds(
                countries.stream().map(Country::bounds).mapToDouble(GeoBounds::minLatitude).min().orElse(-90),
                countries.stream().map(Country::bounds).mapToDouble(GeoBounds::minLongitude).min().orElse(-180),
                countries.stream().map(Country::bounds).mapToDouble(GeoBounds::maxLatitude).max().orElse(90),
                countries.stream().map(Country::bounds).mapToDouble(GeoBounds::maxLongitude).max().orElse(180));
    }

    private record CityDistance(City city, double meters) { }
}
