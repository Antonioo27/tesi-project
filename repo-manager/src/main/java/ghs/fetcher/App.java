/*
 * ============================
 *  ghs.fetcher.App  (unchanged, salvo minime note)
 * ============================
 *  ‣ Rimasto quasi invariato: si è solo parametrizzato l'endpoint di ricerca
 *    tramite variabili d'ambiente, in modo da poter puntare ad ambienti diversi
 *    senza ricompilare.
 *  ‣ La logica interessante è tutta dentro MavenLfsVerifier, qui sotto.
 */

package ghs.fetcher;

import ghs.lfschecker.MavenLfsVerifier;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;

public class App {

  public static void main(String[] args) throws Exception {
    String apiHost = System.getenv()
      .getOrDefault("SEARCH_API_HOST", "http://localhost:48001");
    String apiParams = System.getenv()
      .getOrDefault(
        "SEARCH_API_PARAMS",
        "nameEquals=false&language=Java&committedMin=2023-01-01&committedMax=2025-06-26&" +
        "starsMin=300&forksMin=50&sort=stargazers,desc&page=0&size=100"
      );

    String url = apiHost + "/api/r/search?" + apiParams;

    HttpURLConnection connection = (HttpURLConnection) new URL(
      url
    ).openConnection();
    connection.setRequestMethod("GET");
    connection.setRequestProperty("Accept", "application/json");

    int responseCode = connection.getResponseCode();
    InputStream stream = responseCode == 200
      ? connection.getInputStream()
      : connection.getErrorStream();
    if (stream == null) {
      System.err.println("❌ HTTP " + responseCode + " senza corpo.");
      return;
    }

    String body = new String(stream.readAllBytes());
    if (responseCode != 200) {
      System.err.println("❌ Errore HTTP: " + responseCode + " – " + body);
      return;
    }

    JSONObject obj = new JSONObject(body);
    JSONArray items = obj.getJSONArray("items");

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

    Path reposFile = Paths.get("repos.txt");
    Files.write(reposFile, urls);
    System.out.println(
      "Salvati " + urls.size() + " progetti Maven in repos.txt"
    );

    System.out.println("🚀 Avvio verifica clone + build …");
    MavenLfsVerifier.main(new String[] { reposFile.toString() });
  }
}
