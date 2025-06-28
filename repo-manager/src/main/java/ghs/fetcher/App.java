package ghs.fetcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

public class App {
    public static void main(String[] args) throws IOException, InterruptedException {
        String url = "http://localhost:48001/api/r/search" +
                "?nameEquals=false" +
                "&language=Java" +
                "&committedMin=2023-01-01" +
                "&committedMax=2025-06-26" +
                "&starsMin=300" +
                "&forksMin=50" +
                "&sort=stargazers,desc" +
                "&page=0" +
                "&size=100";

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        int responseCode = connection.getResponseCode();
        InputStream responseStream = (responseCode == 200)
                ? connection.getInputStream()
                : connection.getErrorStream();

        if (responseStream != null) {
            String body = new String(responseStream.readAllBytes());

            if (responseCode == 200) {
                JSONObject obj = new JSONObject(body);
                JSONArray items = obj.getJSONArray("items");

                List<String> urls = items.toList().stream()
                        .map(item -> new JSONObject((java.util.Map<?, ?>) item))
                        .filter(repo -> {
                            JSONArray metrics = repo.optJSONArray("metrics");
                            if (metrics == null)
                                return false;

                            return metrics.toList().stream()
                                    .map(m -> new JSONObject((java.util.Map<?, ?>) m))
                                    .anyMatch(m -> m.optString("language").equalsIgnoreCase("Maven"));
                        })
                        .map(repo -> "https://github.com/" + repo.getString("name"))
                        .collect(Collectors.toList());

                Path reposFile = Paths.get("repos.txt");
                Files.write(reposFile, urls);
                System.out.println("✅ Salvati " + urls.size() + " progetti Maven in repos.txt");

                // 🔽 Avvio il clone subito dopo
                // 🔽 Avvio cloni in parallelo
                System.out.println("🚀 Inizio clonazione parallela...");
                cloneRepositoriesInBatches(urls, 10, 2);

            } else {
                System.err.println("❌ Errore HTTP: " + responseCode);
                System.err.println("📦 Corpo risposta: " + body);
            }
        } else {
            System.err.println("❌ Errore HTTP " + responseCode + " senza corpo (responseStream è null)");
        }
    }

    private static void cloneRepositoriesInBatches(List<String> urls, int batchSize, int numThreads) throws InterruptedException {
        int total = urls.size();
        int batches = (int) Math.ceil((double) total / batchSize);

        System.out.println("🔹 Clonazione in " + batches + " batch da " + batchSize + " repository ciascuno...");

        for (int i = 0; i < batches; i++) {
            int start = i * batchSize;
            int end = Math.min(start + batchSize, total);

            List<String> sublist = urls.subList(start, end);

            System.out.println("🚀 Inizio batch " + (i + 1) + "/" + batches + " [" + start + "-" + (end - 1) + "]");

            // Qui chiamiamo il metodo di clonazione parallela per il batch corrente
            cloneRepositoriesParallel(sublist, numThreads);

            System.out.println("✅ Batch " + (i + 1) + " completato.\n");
        }
    }


    private static void cloneRepositoriesParallel(List<String> urls, int numThreads) throws InterruptedException {
        // Leggi il token da variabile ambiente
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("⚠️ Variabile ambiente GITHUB_TOKEN non definita!");
        }

        Path outputDir = Paths.get("cloned_repos");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.println("❌ Errore nella creazione della directory cloned_repos: " + e.getMessage());
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (String url : urls) {
            executor.submit(() -> {
                String name = url.substring(url.lastIndexOf('/') + 1);
                Path dest = outputDir.resolve(name);

                if (Files.exists(dest)) {
                    System.out.println("⚠️ " + name + " già presente, salto.");
                    return;
                }

                System.out.println("🔄 Clonando " + name + " con JGit...");

                try {
                    Git.cloneRepository()
                            .setURI(url)
                            .setDirectory(dest.toFile())
                            .setDepth(1)
                            .setCredentialsProvider(
                                    new UsernamePasswordCredentialsProvider(token, ""))
                            .call();
                    System.out.println("✅ Clonato: " + name);
                } catch (Exception e) {
                    System.err.println("❌ Errore durante il clone di " + name + ": " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        System.out.println("🏁 Clonazione completata.");
    }

}
