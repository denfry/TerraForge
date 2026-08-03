package dev.terraforge.plugin.command;

import dev.terraforge.plugin.world.WorldCreationCheck;
import java.util.List;

/**
 * Everything {@link DoctorCommandHandler} needs: a fresh list of independently reportable
 * diagnostics, computed live at call time (not scanned on the server thread beyond what each
 * individual check already caches).
 */
public interface DoctorCommandContext {
    List<WorldCreationCheck> diagnose();
}
