package dev.terraforge.plugin.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves a relative path only when every existing ancestor remains inside the approved root. */
public final class SafePathResolver {
    private SafePathResolver() {}
    public static Path resolve(Path root, Path relative) throws IOException {
        if (relative.isAbsolute()) throw new IOException("absolute path is not permitted");
        Path realRoot = root.toRealPath();
        Path target = realRoot.resolve(relative).normalize();
        if (!target.startsWith(realRoot)) throw new IOException("path escapes approved root");
        Path ancestor = target;
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null || !ancestor.toRealPath().startsWith(realRoot)) {
            throw new IOException("symlink escapes approved root");
        }
        return target;
    }
}
