package dev.terraforge.bluemap;

import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import dev.terraforge.core.api.GeoMarkerService.GeoMarker;
import dev.terraforge.core.api.GeoMarkerService.MarkerType;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.MinecraftPos;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;

/**
 * Turns the contents of the marker registry into BlueMap marker sets.
 *
 * <p>Deliberately free of any server or BlueMap-runtime state: it is a pure function from markers
 * to marker sets, which is what makes the mapping testable without a running server.
 *
 * <p>{@link MarkerType#TOWN} and {@link MarkerType#NATION} are skipped on purpose. Towny already
 * publishes those to BlueMap itself; duplicating them would put two labels on every town.
 */
public final class BlueMapMarkerSets {

    /** Every set id TerraForge owns starts with this. Nothing else may be touched on a refresh. */
    public static final String SET_PREFIX = "terraforge-";

    public static final String CITY_SET = SET_PREFIX + "cities";
    public static final String COUNTRY_SET = SET_PREFIX + "countries";
    public static final String POI_SET = SET_PREFIX + "poi";

    private BlueMapMarkerSets() {
    }

    /**
     * Builds the marker sets for the given registry contents.
     *
     * @param markers       registry contents, typically {@code GeoMarkerService.all()}
     * @param transformer   the world's geographic-to-Minecraft mapping
     * @param surfaceY      block Y for a (latitude, longitude) pair; markers float at that height
     * @param cityMarkers   honour {@code bluemap.city-markers}
     * @param countryLabels honour {@code bluemap.country-labels}
     * @return set id to marker set; sets with no markers are omitted
     */
    public static Map<String, MarkerSet> build(Collection<GeoMarker> markers,
                                               CoordinateTransformer transformer,
                                               DoubleBinaryOperator surfaceY,
                                               boolean cityMarkers,
                                               boolean countryLabels) {
        Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(transformer, "transformer");
        Objects.requireNonNull(surfaceY, "surfaceY");

        MarkerSet cities = MarkerSet.builder().label("Cities").toggleable(true).defaultHidden(false)
                .sorting(0).build();
        MarkerSet countries = MarkerSet.builder().label("Countries and regions").toggleable(true)
                .defaultHidden(false).sorting(1).build();
        MarkerSet pointsOfInterest = MarkerSet.builder().label("Points of interest").toggleable(true)
                .defaultHidden(false).sorting(2).build();

        for (GeoMarker marker : markers) {
            MarkerSet target = switch (marker.type()) {
                case CITY, CAPITAL -> cityMarkers ? cities : null;
                case COUNTRY, REGION -> countryLabels ? countries : null;
                case POINT_OF_INTEREST, CUSTOM -> pointsOfInterest;
                // Towny owns these; see the class comment.
                case TOWN, NATION -> null;
            };
            if (target != null) {
                target.put(marker.id(), poiMarker(marker, transformer, surfaceY));
            }
        }

        Map<String, MarkerSet> sets = new LinkedHashMap<>();
        putIfPopulated(sets, CITY_SET, cities);
        putIfPopulated(sets, COUNTRY_SET, countries);
        putIfPopulated(sets, POI_SET, pointsOfInterest);
        return sets;
    }

    private static POIMarker poiMarker(GeoMarker marker, CoordinateTransformer transformer,
                                       DoubleBinaryOperator surfaceY) {
        MinecraftPos position = transformer.toMinecraft(marker.position());
        double y = surfaceY.applyAsDouble(marker.position().latitude(), marker.position().longitude());
        POIMarker.Builder builder = POIMarker.builder()
                .label(marker.label())
                .position(position.x(), y, position.z())
                .styleClasses(styleClass(marker.type()));
        // A capital stays readable from far out; ordinary cities and labels fade in closer so a
        // continental view does not turn into a wall of text.
        builder.maxDistance(maxDistance(marker.type()));
        if (marker.detail() != null && !marker.detail().isBlank()) {
            builder.detail(marker.detail());
        }
        return builder.build();
    }

    private static String styleClass(MarkerType type) {
        return SET_PREFIX + type.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static double maxDistance(MarkerType type) {
        return switch (type) {
            case COUNTRY, CAPITAL -> 10_000_000.0;
            case REGION -> 2_000_000.0;
            default -> 500_000.0;
        };
    }

    private static void putIfPopulated(Map<String, MarkerSet> sets, String id, MarkerSet set) {
        if (!set.getMarkers().isEmpty()) {
            sets.put(id, set);
        }
    }
}
