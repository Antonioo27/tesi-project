package ghs.analyzer.io;

import ghs.analyzer.model.ModuleInputs;
import java.nio.file.Path;

public interface InputResolver {
  ModuleInputs resolveInputsForModule(Path module) throws java.io.IOException;
}
