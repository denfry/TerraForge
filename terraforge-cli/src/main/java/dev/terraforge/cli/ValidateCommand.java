package dev.terraforge.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Checks a prepared database for the mistakes that survive import but ruin lookups.
 *
 * <p>Every check here answers a question the importer cannot: it validates one feature at a time,
 * while these problems are about how features relate. Two countries claiming the same ISO code,
 * two polygons overlapping, a capital sitting in the wrong country -- each imports cleanly and then
 * makes {@code /earth whereami} quietly wrong.
 *
 * <p>Read-only. Nothing is repaired: the fix belongs in the source dataset, where it stays fixed.
 */
@Command(name = "validate", description = "Check a prepared database for data-quality problems.")
public final class ValidateCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_DATAERR = 65;
    private static final int EX_NOINPUT = 66;
    private static final int EX_IOERR = 74;

    /** Examples printed per problem class. The count is always reported in full. */
    private static final int EXAMPLES = 10;

    /**
     * Overlap below this share of the smaller polygon is treated as a shared border rather than a
     * real conflict: neighbouring countries legitimately touch, and datasets snap their edges
     * imperfectly.
     */
    private static final double OVERLAP_TOLERANCE = 0.01;

    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    @Option(names = {"-d", "--database"}, required = true,
            description = "Prepared SQLite file, e.g. plugins/TerraForge/terraforge.db")
    Path database;

    @Option(names = "--strict", description = "Treat warnings as failures too.")
    boolean strict;

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @Override
    public Integer call() {
        if (!Files.isRegularFile(database)) {
            System.err.println("Database does not exist: " + database);
            return EX_NOINPUT;
        }
        List<Country> countries;
        List<Region> regions;
        List<City> cities;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            countries = readCountries(connection);
            regions = readRegions(connection);
            cities = readCities(connection);
        } catch (SQLException | ParseException | IOException exception) {
            System.err.println("Cannot read " + database + ": " + exception.getMessage());
            return EX_IOERR;
        }

        reportCoverage(countries, regions, cities);
        checkDuplicateIsoCodes(countries);
        checkDuplicateNames(countries);
        checkOverlappingCountries(countries);
        checkRegionsInsideTheirCountry(countries, regions);
        checkCapitals(countries, cities);
        checkCitiesInsideTheirCountry(countries, cities);

        System.out.println();
        print("Error", errors);
        print("Warning", warnings);
        if (errors.isEmpty() && warnings.isEmpty()) {
            System.out.println("No problems found.");
            return EX_OK;
        }
        System.out.printf(Locale.ROOT, "%d error(s), %d warning(s).%n", errors.size(), warnings.size());
        return errors.isEmpty() && !strict ? EX_OK : EX_DATAERR;
    }

    // --- checks -------------------------------------------------------------

    private void reportCoverage(List<Country> countries, List<Region> regions, List<City> cities) {
        System.out.println("Countries: " + countries.size());
        System.out.println("Regions:   " + regions.size());
        System.out.println("Cities:    " + cities.size()
                + " (" + cities.stream().filter(City::capital).count() + " capitals)");
        if (countries.isEmpty()) {
            warnings.add("The database has no countries; country and region lookups will find nothing.");
            return;
        }
        Envelope coverage = new Envelope();
        countries.forEach(country -> coverage.expandToInclude(country.geometry().getEnvelopeInternal()));
        System.out.printf(Locale.ROOT, "Coverage:  %.4f..%.4f N, %.4f..%.4f E%n",
                coverage.getMinY(), coverage.getMaxY(), coverage.getMinX(), coverage.getMaxX());
        long outside = cities.stream()
                .filter(city -> !coverage.contains(city.longitude(), city.latitude()))
                .count();
        if (outside > 0) {
            warnings.add(outside + " city/cities lie outside the area the boundaries cover.");
        }
    }

    private void checkDuplicateIsoCodes(List<Country> countries) {
        Map<String, List<String>> byCode = new LinkedHashMap<>();
        for (Country country : countries) {
            byCode.computeIfAbsent(country.isoCode().toUpperCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(country.name());
        }
        byCode.forEach((code, names) -> {
            if (names.size() > 1) {
                // The schema has a UNIQUE constraint on iso_code, so this can only fire for a
                // database built by other tooling. It stays because the consequence is severe: the
                // gazetteer resolves a city's country by ISO code, so a duplicate silently assigns
                // every city of one country to the other.
                errors.add("ISO code " + code + " is used by " + names.size() + " countries: "
                        + String.join(", ", names));
            }
        });
    }

    private void checkDuplicateNames(List<Country> countries) {
        Map<String, Integer> byName = new HashMap<>();
        for (Country country : countries) {
            byName.merge(country.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        byName.forEach((name, count) -> {
            if (count > 1) {
                warnings.add("Country name '" + name + "' appears " + count + " times; "
                        + "'/earth country " + name + "' will always find the same one.");
            }
        });
    }

    private void checkOverlappingCountries(List<Country> countries) {
        STRtree index = new STRtree();
        countries.forEach(country -> index.insert(country.geometry().getEnvelopeInternal(), country));
        index.build();
        int found = 0;
        for (Country country : countries) {
            @SuppressWarnings("unchecked")
            List<Country> candidates = index.query(country.geometry().getEnvelopeInternal());
            for (Country other : candidates) {
                // Each pair is examined once, in id order.
                if (other.id() <= country.id()) {
                    continue;
                }
                double overlap = overlapShare(country.geometry(), other.geometry());
                if (overlap > OVERLAP_TOLERANCE) {
                    found++;
                    if (found <= EXAMPLES) {
                        errors.add(String.format(Locale.ROOT,
                                "%s and %s overlap by %.1f%% of the smaller one; "
                                        + "a point inside both resolves to whichever imported first.",
                                country.name(), other.name(), overlap * 100.0));
                    }
                }
            }
        }
        if (found > EXAMPLES) {
            errors.add("... and " + (found - EXAMPLES) + " further country overlap(s).");
        }
    }

    private static double overlapShare(Geometry a, Geometry b) {
        if (!a.getEnvelopeInternal().intersects(b.getEnvelopeInternal())) {
            return 0.0;
        }
        try {
            double area = a.intersection(b).getArea();
            double smaller = Math.min(a.getArea(), b.getArea());
            return smaller <= 0 ? 0.0 : area / smaller;
        } catch (RuntimeException exception) {
            // A self-intersecting source polygon can break the overlay; that is a finding itself.
            return 0.0;
        }
    }

    private void checkRegionsInsideTheirCountry(List<Country> countries, List<Region> regions) {
        Map<Integer, Country> byId = new HashMap<>();
        countries.forEach(country -> byId.put(country.id(), country));
        int orphans = 0;
        int outside = 0;
        for (Region region : regions) {
            Country country = byId.get(region.countryId());
            if (country == null) {
                orphans++;
                continue;
            }
            if (!country.geometry().getEnvelopeInternal().intersects(region.geometry().getEnvelopeInternal())) {
                outside++;
                if (outside <= EXAMPLES) {
                    errors.add("Region '" + region.name() + "' lies outside its country "
                            + country.name() + ".");
                }
            }
        }
        if (orphans > 0) {
            errors.add(orphans + " region(s) reference a country that is not in the database.");
        }
        if (outside > EXAMPLES) {
            errors.add("... and " + (outside - EXAMPLES) + " further misplaced region(s).");
        }
    }

    private void checkCapitals(List<Country> countries, List<City> cities) {
        Map<Integer, List<City>> capitals = new HashMap<>();
        cities.stream().filter(City::capital)
                .forEach(city -> capitals.computeIfAbsent(city.countryId(), key -> new ArrayList<>()).add(city));
        int missing = 0;
        for (Country country : countries) {
            List<City> owned = capitals.getOrDefault(country.id(), List.of());
            if (owned.isEmpty()) {
                missing++;
            } else if (owned.size() > 1) {
                warnings.add(country.name() + " has " + owned.size() + " capitals: "
                        + owned.stream().map(City::name).limit(EXAMPLES).toList());
            }
        }
        if (missing > 0) {
            warnings.add(missing + " country/countries have no capital; '/earth teleport country' "
                    + "falls back to the bounding-box centre for them.");
        }
        List<City> unassigned = capitals.getOrDefault(0, List.of());
        if (!unassigned.isEmpty()) {
            warnings.add(unassigned.size() + " capital(s) belong to no country in this database: "
                    + unassigned.stream().map(City::name).limit(EXAMPLES).toList());
        }
    }

    private void checkCitiesInsideTheirCountry(List<Country> countries, List<City> cities) {
        Map<Integer, Country> byId = new HashMap<>();
        countries.forEach(country -> byId.put(country.id(), country));
        int misplaced = 0;
        for (City city : cities) {
            Country country = byId.get(city.countryId());
            if (country == null) {
                continue;
            }
            var point = GEOMETRIES.createPoint(new Coordinate(city.longitude(), city.latitude()));
            if (!country.geometry().covers(point)) {
                misplaced++;
                if (misplaced <= EXAMPLES) {
                    warnings.add(String.format(Locale.ROOT,
                            "%s (%.4f, %.4f) is assigned to %s but lies outside its boundary.",
                            city.name(), city.latitude(), city.longitude(), country.name()));
                }
            }
        }
        if (misplaced > EXAMPLES) {
            warnings.add("... and " + (misplaced - EXAMPLES) + " further misplaced city/cities.");
        }
    }

    // --- loading ------------------------------------------------------------

    private static void print(String label, List<String> messages) {
        messages.forEach(message -> System.out.println(label + ": " + message));
    }

    private static List<Country> readCountries(Connection connection)
            throws SQLException, ParseException, IOException {
        WKBReader reader = new WKBReader();
        List<Country> countries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, iso_code, name, geometry FROM countries ORDER BY id")) {
            while (rows.next()) {
                countries.add(new Country(rows.getInt("id"), text(rows, "iso_code"), text(rows, "name"),
                        reader.read(rows.getBytes("geometry"))));
            }
        }
        return countries;
    }

    private static List<Region> readRegions(Connection connection)
            throws SQLException, ParseException, IOException {
        WKBReader reader = new WKBReader();
        List<Region> regions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, country_id, name, geometry FROM regions ORDER BY id")) {
            while (rows.next()) {
                regions.add(new Region(rows.getInt("id"), rows.getInt("country_id"), text(rows, "name"),
                        reader.read(rows.getBytes("geometry"))));
            }
        }
        return regions;
    }

    private static List<City> readCities(Connection connection) throws SQLException, IOException {
        List<City> cities = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, name, latitude, longitude, country_id, capital FROM cities ORDER BY id")) {
            while (rows.next()) {
                cities.add(new City(rows.getInt("id"), text(rows, "name"), rows.getDouble("latitude"),
                        rows.getDouble("longitude"), rows.getInt("country_id"), rows.getInt("capital") != 0));
            }
        }
        return cities;
    }

    private static String text(ResultSet rows, String column) throws SQLException, IOException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            throw new IOException("Column " + column + " is empty; the database was not prepared by the CLI");
        }
        return value;
    }

    private record Country(int id, String isoCode, String name, Geometry geometry) { }

    private record Region(int id, int countryId, String name, Geometry geometry) { }

    private record City(int id, String name, double latitude, double longitude, int countryId, boolean capital) { }
}
