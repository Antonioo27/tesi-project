package ghs.fetcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RepoCloner {
    public static void main(String[] args) throws IOException, InterruptedException {
        List<String> repos = Files.readAllLines(Paths.get("repos.txt"));
        Path outputDir = Paths.get("cloned_repos");
        Files.createDirectories(outputDir);

        for (String url : repos) {
            String name = url.substring(url.lastIndexOf('/') + 1);
            Path dest = outputDir.resolve(name);

            if (Files.exists(dest)) {
                System.out.println("⚠️ Repository già clonato: " + name);
                continue;
            }

            System.out.println("🔄 Clonando " + name + "...");
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", url, dest.toString());
            pb.inheritIO(); // stampa l'output in console
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("✅ Clonato: " + name);
            } else {
                System.err.println("❌ Errore nel clonare: " + name);
            }
        }
    }
}
