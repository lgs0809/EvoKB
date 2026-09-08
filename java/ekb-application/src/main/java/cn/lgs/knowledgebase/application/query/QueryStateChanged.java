package cn.lgs.knowledgebase.application.query;

/** A completion lost its expected durable state; it must not overwrite a newer terminal result. */
public final class QueryStateChanged extends IllegalStateException {
  public QueryStateChanged(String sessionId) {
    super("query session state already changed: " + sessionId);
  }
}
