package dev.terraforge.cli.geo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;

/**
 * Reads the identity of an administrative boundary out of whichever schema the file uses.
 *
 * <p>TerraForge's own schema -- an explicit {@code boundary_type} with {@code iso_code} beside it --
 * remains the contract, and a file that declares it is still held to it strictly: it was written by
 * hand for this purpose, so a malformed feature is a mistake worth failing on.
 *
 * <p>Natural Earth is recognised as well, because it is the dataset the documentation recommends and
 * the one {@code fetch} downloads. Its files carry no notion of a "boundary type": whether a feature
 * is a country or a province is decided by which file it came from, so the two layers are told apart
 * by the attributes they publish -- {@code ISO_A2} on admin-0, lower-case {@code iso_a2} plus
 * {@code admin} on admin-1.
 *
 * <p>Features that carry no usable ISO 3166-1 alpha-2 code are reported and skipped rather than
 * failed on. Natural Earth marks disputed and partially recognised territories with {@code -99}, and
 * refusing to import Europe because Kosovo is contested would be the wrong trade.
 */
final class BoundaryAttributes {

    /** Alpha-2 candidates in descending order of how well each identifies a sovereign state. */
    private static final List<String> COUNTRY_CODE_KEYS =
            List.of("ISO_A2", "ISO_A2_EH", "WB_A2", "FIPS_10");

    private static final List<String> COUNTRY_NAME_KEYS = List.of("NAME", "NAME_EN", "ADMIN", "SOVEREIGNT");

    private BoundaryAttributes() {
    }

    /** What a feature turned out to be. */
    enum Kind {
        COUNTRY,
        REGION,
        /** Recognisable schema, unusable feature -- no ISO code, or no name. */
        UNIDENTIFIED
    }

    /**
     * @param adminLevel first-level regions are level 1; deeper levels are not imported
     */
    record Identity(Kind kind, String name, String isoCode, String countryIsoCode, int adminLevel) {

        static Identity unidentified() {
            return new Identity(Kind.UNIDENTIFIED, null, null, null, 0);
        }
    }

    /** True when the file uses TerraForge's own explicitly typed schema. */
    static boolean isNative(JsonNode properties) {
        return properties.hasNonNull("boundary_type");
    }

    /**
     * Reads a Natural Earth feature.
     *
     * <p>Admin-1 is checked first: its lower-case {@code iso_a2} is the country the province belongs
     * to, and a file mixing the two would otherwise import provinces as countries.
     */
    static Identity naturalEarth(JsonNode properties) {
        String regionCountry = alpha2(properties, List.of("iso_a2", "adm0_a2"));
        if (properties.hasNonNull("admin") && properties.hasNonNull("name")) {
            String name = text(properties, List.of("name", "name_en", "woe_name"));
            return regionCountry == null || name == null
                    ? Identity.unidentified()
                    : new Identity(Kind.REGION, name, null, regionCountry, 1);
        }
        String isoCode = alpha2(properties, COUNTRY_CODE_KEYS);
        String name = text(properties, COUNTRY_NAME_KEYS);
        return isoCode == null || name == null
                ? Identity.unidentified()
                : new Identity(Kind.COUNTRY, name, isoCode, null, 1);
    }

    /** First property that holds a real two-letter code. Natural Earth writes {@code -99} for none. */
    private static String alpha2(JsonNode properties, List<String> keys) {
        for (String key : keys) {
            String value = properties.path(key).asText("").trim().toUpperCase(Locale.ROOT);
            if (value.matches("[A-Z]{2}")) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode properties, List<String> keys) {
        for (String key : keys) {
            String value = properties.path(key).asText("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
