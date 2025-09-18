package ghs.git;

import ghs.config.Settings;
import ghs.exec.ProcessRunner;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import java.nio.file.*;
import java.util.List;

public final class GitCloner {

  public Path cloneShallowJGit(String url, Path dest) throws Exception {
    String token = System.getenv("GITHUB_TOKEN");
    try {
      cloneOnce(url, dest, token, "refs/heads/main");
      return dest;
    } catch (Exception ignore) {
      deleteDirectoryQuiet(dest);
    }
    try {
      cloneOnce(url, dest, token, "refs/heads/master");
      return dest;
    } catch (Exception ignore) {
      deleteDirectoryQuiet(dest);
    }
    String headRef = remoteHead(url, token);
    cloneOnce(url, dest, token, headRef);
    return dest;
  }

  private static void cloneOnce(String url, Path dest, String token, String ref) throws Exception {
    CloneCommand cmd = Git.cloneRepository()
        .setURI(url)
        .setDirectory(dest.toFile())
        .setDepth(1)
        .setCloneAllBranches(false)
        .setBranchesToClone(List.of(ref))
        .setBranch(ref);
    if (token != null && !token.isBlank()) {
      cmd.setCredentialsProvider(
          new UsernamePasswordCredentialsProvider(token, ""));
    }
    cmd.call();
  }

  private static String remoteHead(String url, String token) throws Exception {
    var cmd = Git.lsRemoteRepository().setRemote(url);
    if (token != null && !token.isBlank()) {
      cmd.setCredentialsProvider(
          new UsernamePasswordCredentialsProvider(token, ""));
    }
    return cmd
        .call()
        .stream()
        .filter(r -> "HEAD".equals(r.getName()))
        .findFirst()
        .map(r -> r.getTarget().getName())
        .orElseThrow(() -> new IllegalStateException("HEAD remoto non trovato"));
  }

  public void cloneWithLfsCli(String url, Path dest, Settings s) throws Exception {
    if (Files.exists(dest)) {
      deleteDirectoryQuiet(dest); // Elimina la directory se esiste
    }

    // Esegui il comando di clonazione usando Git con LFS
    ProcessRunner.run(
        List.of(
            "git",
            "clone",
            "--depth",
            "1",
            "--single-branch",
            url,
            dest.toString()),
        null,
        s.gitTimeout() // Timeout configurato
    );

    // Esegui il comando git lfs per recuperare i file LFS
    ProcessRunner.run(List.of("git", "lfs", "pull"), dest, s.gitTimeout());
  }

  private static void deleteDirectoryQuiet(Path path) {
    try {
      if (!Files.exists(path))
        return;
      Files.walk(path)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (Exception ignored) {
            }
          });
    } catch (Exception ignored) {
    }
  }
}
