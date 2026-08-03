package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Deletes a staged-but-not-yet-created earth world only when TerraForge provably owns every entry inside it. */
public final class ManagedWorldAbort {
    public static final String MARKER_FILE_NAME = ".terraforge-staging";
    private static final Set<String> FORBIDDEN_TOP_LEVEL_ENTRIES = Set.of("level.dat", "uid.dat", "region", "entities", "poi");

    private final ManagedWorldManifestStore manifests;

    public ManagedWorldAbort(ManagedWorldManifestStore manifests) { this.manifests = manifests; }

    /** Refuses unless the manifest is PENDING_RESTART and the earth directory contains only the marker and
     *  manifest-listed staged files; otherwise deletes the staged directory and the manifest. */
    public void abort(Path serverRoot, Path worldContainer) throws IOException {
        Optional<ManagedWorldManifest> loaded = manifests.load();
        if (loaded.isEmpty() || loaded.get().state() != ManagedWorldState.PENDING_RESTART) {
            throw new IOException("abort requires a pending-restart managed-world manifest");
        }
        ManagedWorldManifest manifest = loaded.get();
        Path realServerRoot = serverRoot.toRealPath();
        Path earth = worldContainer.resolve("earth").normalize();
        if (!Files.isDirectory(earth)) throw new IOException("no staged earth directory to abort");

        Set<Path> allowedRelativeFiles = new HashSet<>();
        for (String owned : manifest.ownedStagingFiles()) {
            Path absolute = realServerRoot.resolve(Path.of(owned)).normalize();
            if (absolute.startsWith(earth)) allowedRelativeFiles.add(earth.relativize(absolute));
        }
        Path markerRelative = Path.of(MARKER_FILE_NAME);

        verifyExactOwnership(earth, allowedRelativeFiles, markerRelative);

        deleteRecursively(earth);
        manifests.delete();
    }

    private static void verifyExactOwnership(Path earth, Set<Path> allowedRelativeFiles, Path markerRelative) throws IOException {
        try (var walk = Files.walk(earth)) {
            for (Path path : walk.filter(candidate -> !candidate.equals(earth)).toList()) {
                Path relative = earth.relativize(path);
                String topLevel = relative.getName(0).toString();
                if (FORBIDDEN_TOP_LEVEL_ENTRIES.contains(topLevel)) {
                    throw new IOException("refusing to abort: earth directory contains " + topLevel);
                }
                if (Files.isDirectory(path)) {
                    boolean isAncestorOfAnAllowedFile = allowedRelativeFiles.stream().anyMatch(file -> file.startsWith(relative));
                    if (!isAncestorOfAnAllowedFile) throw new IOException("refusing to abort: unknown directory " + relative);
                    continue;
                }
                boolean isMarker = relative.equals(markerRelative);
                boolean isOwnedFile = allowedRelativeFiles.contains(relative);
                if (!isMarker && !isOwnedFile) throw new IOException("refusing to abort: unknown entry " + relative);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
