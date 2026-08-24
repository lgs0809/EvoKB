package cn.lgs.knowledgebase.application.compilation;

/** A model proposal failed grounding; never publish it, allow the existing bounded job retry. */
public final class ModelOutputRejectedException extends RuntimeException {
  public ModelOutputRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
