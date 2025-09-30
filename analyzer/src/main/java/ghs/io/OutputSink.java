package ghs.io;

import ghs.model.TestRecord;

public interface OutputSink extends AutoCloseable {
  void write(TestRecord rec) throws Exception;

  @Override
  default void close() throws Exception {
  }
}
