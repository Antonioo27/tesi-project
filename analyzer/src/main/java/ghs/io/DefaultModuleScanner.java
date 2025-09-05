package ghs.analyzer.io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class DefaultModuleScanner implements ModuleScanner {

  private static final Set<String> SKIP = Set.of(
    "node_modules",
    ".git",
    ".hg",
    ".svn",
    "build",
    "dist",
    "out"
  );

  @Override
  public List<Path> findMavenModules(Path base) throws IOException {
    List<Path> modules = new ArrayList<>();
    Files.walkFileTree(
      base,
      new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(
          Path dir,
          BasicFileAttributes attrs
        ) {
          String n = dir.getFileName() == null
            ? ""
            : dir.getFileName().toString();
          if (
            SKIP.contains(n) || attrs.isSymbolicLink()
          ) return FileVisitResult.SKIP_SUBTREE;
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if ("pom.xml".equals(file.getFileName().toString())) {
            Path m = file.getParent();
            if (
              Files.isDirectory(m.resolve("target/classes")) &&
              Files.isDirectory(m.resolve("target/test-classes"))
            ) modules.add(m);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          System.out.println(
            " (skip path problem) " +
            file +
            " -> " +
            exc.getClass().getSimpleName()
          );
          return FileVisitResult.CONTINUE;
        }
      }
    );
    return modules;
  }
}
