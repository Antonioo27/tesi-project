package ghs.model;

import java.nio.file.Path;

/** Solo path delle classi di produzione e di test. */
public record ModuleInputs(Path prodClasses, Path testClasses) {
}
