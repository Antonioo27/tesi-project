package ghs.fetcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class GitHubApiFetcher {

    /**
     * Recupera gli URL GitHub dei repository dalla Search API,
     * filtrando solo quelli che hanno un `pom.xml` nella root.
     */
    public static List<String> fetchRepositories(String apiUrl, String githubToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        setAuth(connection, githubToken);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        InputStream stream = (responseCode == 200) ? connection.getInputStream() : connection.getErrorStream();

        String response;
        try (InputStream is = stream) {
            response = new String(is.readAllBytes());
        }

        if (responseCode != 200) {
            System.err.println("Failed to fetch data (" + responseCode + "): " + response);
            return Collections.emptyList();
        }

        JSONObject obj = new JSONObject(response);
        JSONArray items = obj.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return Collections.emptyList();
        }

        return items.toList().stream()
                .map(item -> new JSONObject((Map<?, ?>) item))
                .filter(repo -> containsPomXml(repo.getString("full_name"), githubToken))
                .map(repo -> "https://github.com/" + repo.getString("full_name"))
                .collect(Collectors.toList());
    }

    /**
     * Verifica l'esistenza di `pom.xml` nella root del repository
     * con una chiamata diretta al file (più leggera di elencare tutta la root).
     */
    private static boolean containsPomXml(String fullName, String githubToken) {
        String contentsUrl = "https://api.github.com/repos/" + fullName + "/contents/pom.xml";
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(contentsUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            setAuth(connection, githubToken);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int code = connection.getResponseCode();
            if (code == 200) {
                // Consumiamo e chiudiamo lo stream per buona norma
                try (InputStream is = connection.getInputStream()) {
                    is.readAllBytes();
                }
                return true;
            }
            if (code == 404) {
                return false; // file non presente
            }
            // Altri codici (es. rate limit / 403 / 5xx)
            try (InputStream es = connection.getErrorStream()) {
                if (es != null)
                    es.readAllBytes();
            }
            System.err.printf("Impossibile verificare pom.xml per %s (HTTP %d)%n", fullName, code);
            return false;
        } catch (IOException e) {
            System.err.println("Errore su " + fullName + " (pom.xml): " + e.getMessage());
            return false;
        }
    }

    private static void setAuth(HttpURLConnection connection, String token) {
        if (token != null && !token.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
    }
}
