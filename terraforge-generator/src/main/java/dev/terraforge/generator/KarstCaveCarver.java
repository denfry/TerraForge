package dev.terraforge.generator;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.KarstProvider;
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
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            GeoPoint point=transformer.toGeographic(baseX+x+0.5,baseZ+z+0.5);
            if (!karst.contains(point.latitude(),point.longitude())) continue;
            GeoPoint anchor=karst.nearestEntrance(point.latitude(),point.longitude(),0.05).orElse(point);
            long h=mix(Double.doubleToLongBits(anchor.latitude()) ^ Long.rotateLeft(Double.doubleToLongBits(anchor.longitude()),17)
                    ^ ((long)Math.floor(point.latitude()*10_000)<<32) ^ (long)Math.floor(point.longitude()*10_000));
            if ((h & 0x7fL) != 0L) continue;
            int surface=terrain.sampleColumn(point.latitude(),point.longitude()).surfaceY();
            int y=Math.max(chunk.getMinHeight()+8, Math.min(surface-8, chunk.getMinHeight()+16+(int)((h>>>8)&31)));
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=-2;dy<=2;dy++) if(dx*dx+dy*dy+dz*dz<=4) {
                int lx=x+dx,lz=z+dz,ly=y+dy; if(lx>=0&&lx<16&&lz>=0&&lz<16&&ly>chunk.getMinHeight()+1&&ly<surface) chunk.setBlock(lx,ly,lz,Material.AIR);
            }
        }
    }
    private static long mix(long value) { value^=value>>>33; value*=0xff51afd7ed558ccdL; value^=value>>>33; value*=0xc4ceb9fe1a85ec53L; return value^(value>>>33); }
}
