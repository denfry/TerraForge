package dev.terraforge.core.projection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleFunction;

/**
 * Resolves a projection by its configuration id.
 *
 * <p>Adding a projection means registering a factory here -- no generator code changes. The factory
 * receives the configured origin latitude, which parallel-based projections use as their standard
 * parallel by default.
 */
public final class ProjectionRegistry {

    private final Map<String, DoubleFunction<Projection>> factories = new LinkedHashMap<>();

    public ProjectionRegistry() {
        register("web_mercator", originLatitude -> WebMercatorProjection.INSTANCE);
        register("equirectangular", EquirectangularProjection::new);
        register("plate_carree", originLatitude -> EquirectangularProjection.plateCarree());
    }

    public void register(String id, DoubleFunction<Projection> factory) {
        factories.put(id.toLowerCase(java.util.Locale.ROOT), factory);
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    /**
     * @param id              projection id from {@code terraforge.yml}
     * @param originLatitude  world origin latitude, used as the default standard parallel
     * @throws IllegalArgumentException if the id is unknown -- the plugin fails fast on startup
     *                                  rather than silently generating a differently shaped world
     */
    public Projection create(String id, double originLatitude) {
        DoubleFunction<Projection> factory = factories.get(id.toLowerCase(java.util.Locale.ROOT));
        if (factory == null) {
            throw new IllegalArgumentException("Unknown projection '" + id + "', known: " + ids());
        }
        return factory.apply(originLatitude);
    }
}
