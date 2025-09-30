package ghs.io;

import ghs.model.ModuleInputs;
import java.nio.file.Path;

public interface InputResolver {
  ModuleInputs resolveInputsForModule(Path module) throws java.io.IOException;
}
