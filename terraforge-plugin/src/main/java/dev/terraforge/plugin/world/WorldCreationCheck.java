package dev.terraforge.plugin.world;

/** One independently reportable prerequisite for staging the managed primary world. */
public record WorldCreationCheck(String name, boolean passed, String detail) {}
