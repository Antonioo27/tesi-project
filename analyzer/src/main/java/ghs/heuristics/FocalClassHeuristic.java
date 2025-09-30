package ghs.heuristics;

public interface FocalClassHeuristic {
  /**
   * Dato il FQN della classe di test, prova a dedurre il FQN della classe focal.
   */
  String guessFocalClassFromTestName(String testClassFqn);
}
