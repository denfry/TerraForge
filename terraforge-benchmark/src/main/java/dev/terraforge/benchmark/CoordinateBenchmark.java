package dev.terraforge.benchmark;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.geodesy.Geodesy;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.projection.WebMercatorProjection;
import dev.terraforge.core.terrain.VerticalScale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Baseline cost of the coordinate maths.
 *
 * <p>These run for every one of the 256 columns in a chunk, so they set the floor for generation
 * time. The DEM and biome stages are benchmarked separately once they land.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class CoordinateBenchmark {

    private CoordinateTransformer mercator;
    private CoordinateTransformer equirectangular;
    private VerticalScale verticalScale;

    @Setup
    public void setUp() {
        GeoPoint origin = new GeoPoint(51.0, 10.0);
        mercator = new CoordinateTransformer(WebMercatorProjection.INSTANCE, origin, 1.0);
        equirectangular = new CoordinateTransformer(new EquirectangularProjection(51.0), origin, 1.0);
        verticalScale = new VerticalScale(63, -64, 320, 1.0, 1.0);
    }

    @Benchmark
    public void blockToGeographicMercator(Blackhole bh) {
        bh.consume(mercator.toGeographic(1234.0, -5678.0));
    }

    @Benchmark
    public void blockToGeographicEquirectangular(Blackhole bh) {
        bh.consume(equirectangular.toGeographic(1234.0, -5678.0));
    }

    /** One full chunk's worth of coordinate resolution: 256 columns. */
    @Benchmark
    public void chunkColumnsToGeographic(Blackhole bh) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                bh.consume(equirectangular.toGeographic(x, z));
            }
        }
    }

    @Benchmark
    public void elevationToBlockY(Blackhole bh) {
        bh.consume(verticalScale.toBlockY(1234.5));
    }

    @Benchmark
    public void vincentyDistance(Blackhole bh) {
        bh.consume(Geodesy.vincentyMeters(52.520008, 13.404954, 48.856613, 2.352222));
    }

    @Benchmark
    public void haversineDistance(Blackhole bh) {
        bh.consume(Geodesy.haversineMeters(52.520008, 13.404954, 48.856613, 2.352222));
    }
}
