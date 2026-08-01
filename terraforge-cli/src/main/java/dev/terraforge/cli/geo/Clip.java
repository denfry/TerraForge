package dev.terraforge.cli.geo;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/**
 * Decides whether a source feature belongs in a bounding box.
 *
 * <p>An envelope test alone is wrong for exactly the features nobody notices: a country whose
 * territory crosses the antimeridian -- Russia, the United States, Fiji, Kiribati, New Zealand --
 * has parts near both +180 and -180, so its envelope spans the entire planet and intersects every
 * box on Earth. Preparing a box over Germany would import Fiji, and preparing one over the Pacific
 * would import Russia's western border.
 *
 * <p>So the envelope is only a cheap first pass. A feature the box does not fully contain is tested
 * against the box itself, using a prepared geometry so the exact test costs a rectangle-vs-polygon
 * intersection rather than a full overlay. Nothing here reprojects or splits geometry -- a feature
 * is imported whole or not at all, which is what keeps a country's polygon usable for point-in-
 * polygon lookups at runtime.
 */
public final class Clip {

    private static final GeometryFactory FACTORY = new GeometryFactory();

    private final Envelope envelope;
    private final PreparedGeometry prepared;

    private Clip(Envelope envelope) {
        this.envelope = envelope;
        this.prepared = PreparedGeometryFactory.prepare(FACTORY.toGeometry(envelope));
    }

    /** @return {@code null} when {@code envelope} is null, meaning "import everything" */
    public static Clip of(Envelope envelope) {
        return envelope == null || envelope.isNull() ? null : new Clip(envelope);
    }

    /** Whether {@code geometry} should be imported. A null clip keeps everything. */
    public static boolean keeps(Clip clip, Geometry geometry) {
        return clip == null || clip.keeps(geometry);
    }

    public boolean keeps(Geometry geometry) {
        Envelope bounds = geometry.getEnvelopeInternal();
        if (!envelope.intersects(bounds)) {
            return false;
        }
        // A feature wholly inside the box needs no exact test -- the common case for a world-wide
        // box, where skipping it keeps a planet-scale import from paying for 4,000 overlays.
        if (envelope.covers(bounds)) {
            return true;
        }
        return prepared.intersects(geometry);
    }

    public Envelope envelope() {
        return envelope;
    }
}
