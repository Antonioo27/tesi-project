package ghs.fetcher;

import ghs.lfschecker.MavenLfsVerifier;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;

public class App {

  public static void main(String[] args) throws Exception {
    String apiHost = System.getenv()
      .getOrDefault("SEARCH_API_HOST", "http://localhost:48001");
    String apiParamsRaw = System.getenv()
      .getOrDefault(
        "SEARCH_API_PARAMS",
        "nameEquals=false&language=Java&committedMin=2023-01-01&committedMax=2025-06-26&" +
        "starsMin=300&forksMin=50&sort=stargazers,desc"
      );

    int startPage = Integer.parseInt(
      System.getenv().getOrDefault("SEARCH_API_START_PAGE", "0")
    );
    int pageSize = Integer.parseInt(
      System.getenv().getOrDefault("SEARCH_API_PAGE_SIZE", "100")
    );
    int maxPages = Integer.parseInt(
      System.getenv().getOrDefault("SEARCH_API_MAX_PAGES", "4")
    );
    long delayMs = Long.parseLong(
      System.getenv().getOrDefault("SEARCH_API_DELAY_MS", "250")
    ); // tra richieste

    // pulizia: togli eventuali page/size già presenti nei params
    String apiParams = Arrays.stream(apiParamsRaw.split("&"))
      .filter(s -> {
        String t = s.toLowerCase();
        return !(t.startsWith("page=") || t.startsWith("size=") || t.isBlank());
      })
      .collect(Collectors.joining("&"));

    LinkedHashSet<String> allUrls = new LinkedHashSet<>();

    for (int p = startPage; p < startPage + maxPages; p++) {
      String url =
        apiHost +
        "/api/r/search?" +
        apiParams +
        "&page=" +
        p +
        "&size=" +
        pageSize;
      List<String> pageUrls = fetchMavenRepos(url, /*retries*/2);
      System.out.printf("Pagina %d: trovati %d repo%n", p, pageUrls.size());

      if (pageUrls.isEmpty()) break; // niente più risultati: esci
      allUrls.addAll(pageUrls);

      if (delayMs > 0) Thread.sleep(delayMs);
    }

    Path reposFile = Paths.get("repos.txt");
    Files.write(reposFile, allUrls); // deduplicati e in ordine di arrivo
    System.out.println(
      "Salvati " + allUrls.size() + " progetti Maven in repos.txt"
    );

    System.out.println("Avvio verifica clone + build …");
    MavenLfsVerifier.main(new String[] { reposFile.toString() });
  }

  /** Scarica una pagina e filtra solo i repo con metrica 'Maven'. Con retry semplice. */
  private static List<String> fetchMavenRepos(String url, int retries)
    throws InterruptedException {
    for (int attempt = 1; attempt <= retries + 1; attempt++) {
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL(
          url
        ).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);

        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode == 200
          ? connection.getInputStream()
          : connection.getErrorStream();
        if (stream == null) {
          System.err.println(
            "❌ HTTP " + responseCode + " senza corpo per: " + url
          );
          return List.of();
        }

        String body = new String(stream.readAllBytes());
        if (responseCode != 200) {
          System.err.println("Errore HTTP: " + responseCode + " – " + body);
          return List.of();
        }

        JSONObject obj = new JSONObject(body);
        JSONArray items = obj.optJSONArray("items");
        if (items == null || items.isEmpty()) return List.of();

        List<String> urls = items
          .toList()
          .stream()
          .map(it -> new JSONObject((java.util.Map<?, ?>) it))
          .filter(repo -> {
            JSONArray metrics = repo.optJSONArray("metrics");
            if (metrics == null) return false;
            return metrics
              .toList()
              .stream()
              .map(m -> new JSONObject((java.util.Map<?, ?>) m))
              .anyMatch(m -> "Maven".equalsIgnoreCase(m.optString("language")));
          })
          .map(repo -> "https://github.com/" + repo.getString("name"))
          .collect(Collectors.toList());

        return urls;
      } catch (IOException ioe) {
        System.err.println(
          "fetch fallito (" + attempt + "): " + ioe.getMessage()
        );
        Thread.sleep(300L * attempt); // backoff lineare
      }
    }
    return List.of();
  }
}
