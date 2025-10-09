package ghs.fetcher;

import ghs.lfschecker.MavenLfsVerifier;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class App {

  public static void main(String[] args) throws Exception {
    String apiHost = System.getenv().getOrDefault("SEARCH_API_HOST", "https://api.github.com"); // 🏭 Parametri avanzati
                                                                                                // per repository
                                                                                                // INDUSTRIALI di
                                                                                                // qualità
                                                                                                // production-ready
    String apiParamsRaw = System.getenv().getOrDefault(
        "SEARCH_API_PARAMS",
        "q=language:Java+stars:>1000+forks:>50+size:>300+size:<150000+pushed:>2022-01-01+"
            + "is:public+archived:false+fork:false+has:readme+has:license");

    int startPage = Integer.parseInt(System.getenv().getOrDefault("SEARCH_API_START_PAGE", "1"));
    int pageSize = Integer.parseInt(System.getenv().getOrDefault("SEARCH_API_PAGE_SIZE", "100"));
    int maxPages = Integer.parseInt(System.getenv().getOrDefault("SEARCH_API_MAX_PAGES", "10"));
    // Delay aumentato per rispettare rate limits GitHub API (5000 req/hour)
    long delayMs = Long.parseLong(System.getenv().getOrDefault("SEARCH_API_DELAY_MS", "500"));

    // Pulizia: togli eventuali page/size già presenti nei params
    String apiParams = Arrays.stream(apiParamsRaw.split("&"))
        .filter(s -> !s.toLowerCase().startsWith("page=") && !s.toLowerCase().startsWith("size="))
        .collect(Collectors.joining("&"));

    LinkedHashSet<String> allUrls = new LinkedHashSet<>();

    // 1. Chiamata all'API per ottenere i repository
    for (int p = startPage; p < startPage + maxPages; p++) {
      String url = apiHost +
          "/search/repositories?" +
          apiParams +
          "&page=" + p +
          "&per_page=" + pageSize;
      List<String> pageUrls = GitHubApiFetcher.fetchRepositories(url, System.getenv("GITHUB_TOKEN"));
      System.out.printf("Pagina %d: trovati %d repo%n", p, pageUrls.size());

      if (pageUrls.isEmpty()) {
        break; // Niente più risultati, esci
      }
      allUrls.addAll(pageUrls);

      if (delayMs > 0) {
        Thread.sleep(delayMs);
      }
    }

    // 2. Scrittura in repos.txt
    Path reposFile = Paths.get("repos.txt");
    Files.write(reposFile, allUrls, StandardCharsets.UTF_8);

    System.out.println("Salvati " + allUrls.size() + " progetti Maven in repos.txt"); // 4. Verifica qualità campione
                                                                                      // repository
    if (!allUrls.isEmpty()) {
      System.out.println("\nAnteprima repository selezionati:");
      allUrls.stream().limit(5).forEach(url -> System.out.println("  ✅ " + url));

      if (allUrls.size() > 5) {
        System.out.println("  ... e altri " + (allUrls.size() - 5) + " repository");
      }
    }

    // 5. Avvio verifica clone + build
    System.out.println("Avvio verifica clone + build …");
    MavenLfsVerifier.main(new String[] { reposFile.toString() });
  }

}
