package ghs.git;

import ghs.config.Settings;
import ghs.exec.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

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

  public void cloneWithLfsCli(String url, Path dest, Settings s)
    throws Exception {
    if (Files.exists(dest)) deleteDirectoryQuiet(dest);
    ProcessRunner.run(
      List.of(
        "git",
        "clone",
        "--depth",
        "1",
        "--single-branch",
        url,
        dest.toString()
      ),
      null,
      s.gitTimeout()
    );
    ProcessRunner.run(List.of("git", "lfs", "pull"), dest, s.gitTimeout());
  }

  private static void cloneOnce(
    String url,
    Path dest,
    String token,
    String ref
  ) throws Exception {
    CloneCommand cmd = Git.cloneRepository()
      .setURI(url)
      .setDirectory(dest.toFile())
      .setDepth(1)
      .setCloneAllBranches(false)
      .setBranchesToClone(List.of(ref))
      .setBranch(ref);
    if (token != null && !token.isBlank()) {
      cmd.setCredentialsProvider(
        new UsernamePasswordCredentialsProvider(token, "")
      );
    }
    cmd.call();
  }

  private static String remoteHead(String url, String token) throws Exception {
    var cmd = Git.lsRemoteRepository().setRemote(url);
    if (token != null && !token.isBlank()) {
      cmd.setCredentialsProvider(
        new UsernamePasswordCredentialsProvider(token, "")
      );
    }
    return cmd
      .call()
      .stream()
      .filter(r -> "HEAD".equals(r.getName()))
      .findFirst()
      .map(r -> r.getTarget().getName())
      .orElseThrow(() -> new IllegalStateException("HEAD remoto non trovato"));
  }

  private static void deleteDirectoryQuiet(Path path) {
    try {
      if (!Files.exists(path)) return;
      Files.walk(path)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p -> {
          try {
            Files.delete(p);
          } catch (Exception ignored) {}
        });
    } catch (Exception ignored) {}
  }
}
