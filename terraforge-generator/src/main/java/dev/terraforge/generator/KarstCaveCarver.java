package dev.terraforge.generator;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/**
 * Deterministic generated cave passages constrained by prepared karst polygons.
 * This deliberately derives every decision from geographic coordinates (and a nearby real OSM
 * entrance when available), never from the Minecraft world seed. It is not 3D survey geometry.
 */
final class KarstCaveCarver {
    private final CoordinateTransformer transformer; private final KarstProvider karst; private final TerrainPipeline terrain;
    KarstCaveCarver(CoordinateTransformer transformer, KarstProvider karst, TerrainPipeline terrain) { this.transformer=transformer; this.karst=karst; this.terrain=terrain; }
    void carve(int chunkX, int chunkZ, ChunkData chunk) {
        int baseX=chunkX<<4, baseZ=chunkZ<<4;
        // Cache hit in practice: generateNoise/generateSurface already sampled this chunk.
        ChunkSampler.ChunkSamples samples = terrain.sampleChunk(chunkX, chunkZ);
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            GeoPoint point=transformer.toGeographic(baseX+x+0.5,baseZ+z+0.5);
            if (!karst.contains(point.latitude(),point.longitude())) continue;
            TerrainSample anchorSample=samples.at(x,z);
            // Karst polygons can graze the coast; a cave must never start under the sea or a lake.
            if (anchorSample.isWater()) continue;
            GeoPoint anchor=karst.nearestEntrance(point.latitude(),point.longitude(),0.05).orElse(point);
            long h=mix(Double.doubleToLongBits(anchor.latitude()) ^ Long.rotateLeft(Double.doubleToLongBits(anchor.longitude()),17)
                    ^ ((long)Math.floor(point.latitude()*10_000)<<32) ^ (long)Math.floor(point.longitude()*10_000));
            if ((h & 0x7fL) != 0L) continue;
            int surface=anchorSample.surfaceY();
            int y=Math.max(chunk.getMinHeight()+8, Math.min(surface-8, chunk.getMinHeight()+16+(int)((h>>>8)&31)));
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                int lx=x+dx,lz=z+dz;
                if (lx<0||lx>=16||lz<0||lz>=16) continue;
                // Each neighbour is capped by its own surface (and skipped if it is water), so the
                // sphere never digs out from under a shallower or wet column two blocks away --
                // exactly the seam that used to breach cave mouths into the seabed near a coast.
                TerrainSample neighbourSample=samples.at(lx,lz);
                if (neighbourSample.isWater()) continue;
                int neighbourSurface=neighbourSample.surfaceY();
                for(int dy=-2;dy<=2;dy++) if(dx*dx+dy*dy+dz*dz<=4) {
                    int ly=y+dy; if(ly>chunk.getMinHeight()+1&&ly<neighbourSurface) chunk.setBlock(lx,ly,lz,Material.AIR);
                }
            }
        }
    }
    private static long mix(long value) { value^=value>>>33; value*=0xff51afd7ed558ccdL; value^=value>>>33; value*=0xc4ceb9fe1a85ec53L; return value^(value>>>33); }
}
