package ghs.analyzer.model;


import java.nio.file.Path;
import java.util.List;


public record ModuleInputs(Path prodClasses, Path testClasses, List<Path> dependencyJars) {}