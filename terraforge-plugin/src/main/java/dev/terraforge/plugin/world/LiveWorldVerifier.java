package dev.terraforge.plugin.world;

import java.util.ArrayList;
import java.util.List;

/** Compares the running primary world with its staged manifest without performing side effects. */
public final class LiveWorldVerifier {
    public List<String> verify(ManagedWorldManifest manifest, LiveWorldSnapshot world) {
        List<String> failures = new ArrayList<>();
        if (!"earth".equals(world.name()) || !manifest.worldName().equals(world.name())) failures.add("world name is not earth");
        if (!world.primary()) failures.add("earth is not the primary world");
        if (!world.terraForgeGenerator()) failures.add("earth is not using the TerraForge generator");
        if (world.minY() != manifest.minY() || world.maxY() != manifest.maxY()) failures.add("world height does not match manifest");
        if (!world.earthHeightDatapackEnabled()) failures.add("TerraForge/earth-height datapack is not enabled");
        if (!manifest.configFingerprint().equals(world.configFingerprint())) failures.add("configuration fingerprint does not match");
        if (!manifest.dataFingerprint().equals(world.dataFingerprint())) failures.add("data fingerprint does not match");
        if (!manifest.datapackFingerprint().equals(world.datapackFingerprint())) failures.add("datapack fingerprint does not match");
        return List.copyOf(failures);
    }
}
