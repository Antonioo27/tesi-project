package ghs.fetcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class GitHubApiFetcher {

    // Funzione che recupera i repository dalla API di GitHub
    public static List<String> fetchRepositories(String apiUrl, String githubToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("Authorization", "Bearer " + githubToken);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        InputStream stream = (responseCode == 200) ? connection.getInputStream() : connection.getErrorStream();

        String response = new String(stream.readAllBytes());
        if (responseCode != 200) {
            System.err.println("Failed to fetch data: " + responseCode);
            return Collections.emptyList();
        }

        // Parsing della risposta JSON
        JSONObject responseObject = new JSONObject(response);
        JSONArray items = responseObject.optJSONArray("items");

        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        // Filtriamo i repository che usano Java come linguaggio e che hanno un file
        // pom.xml
        return items.toList().stream()
                .map(item -> new JSONObject((Map<?, ?>) item))
                .filter(repo -> repo.optString("language").equalsIgnoreCase("Java") && containsPomXml(repo))
                .map(repo -> "https://github.com/" + repo.getString("full_name"))
                .collect(Collectors.toList());
    }

    private static boolean containsPomXml(JSONObject repo) {
        // GitHub non fornisce direttamente una lista di file all'interno del repository
        // in questa chiamata,
        // quindi dobbiamo fare una richiesta separata per ciascun repository per
        // controllare se contiene un 'pom.xml'.
        String repoName = repo.getString("full_name");
        try {
            // URL dell'API per ottenere il contenuto del repository
            String contentsUrl = "https://api.github.com/repos/" + repoName + "/contents";
            HttpURLConnection connection = (HttpURLConnection) new URL(contentsUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setRequestProperty("Authorization", "Bearer " + System.getenv("GITHUB_TOKEN"));
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                System.err.println("Failed to fetch repository content for: " + repoName);
                return false;
            }

            // Parsing della risposta per cercare 'pom.xml'
            InputStream stream = connection.getInputStream();
            String response = new String(stream.readAllBytes());
            JSONArray files = new JSONArray(response);

            // Verifica se esiste un file 'pom.xml'
            return files.toList().stream()
                    .map(file -> new JSONObject((Map<?, ?>) file))
                    .anyMatch(file -> "pom.xml".equals(file.getString("name")));
        } catch (IOException e) {
            System.err.println(
                    "Error while checking 'pom.xml' in " + repo.getString("full_name") + ": " + e.getMessage());
            return false;
        }
    }
}
