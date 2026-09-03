package cn.lgs.knowledgebase.infrastructure.parser;

import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import java.io.InputStream;
import java.util.Set;
import java.util.function.Consumer;

/** Routes text-native formats locally and binary/document formats to MinerU. */
public final class RoutingParserAdapter implements ParserPort {
  private static final Set<String> LOCAL_TEXT_EXTENSIONS =
      Set.of(".txt", ".md", ".csv", ".json", ".html", ".htm");

  private final ParserPort textParser;
  private final ParserPort minerUParser;

  public RoutingParserAdapter(ParserPort textParser, ParserPort minerUParser) {
    this.textParser = textParser;
    this.minerUParser = minerUParser;
  }

  @Override
  public ParsedDocument parse(String filename, InputStream source) {
    return LOCAL_TEXT_EXTENSIONS.contains(TextDocumentParserAdapter.extension(filename))
        ? textParser.parse(filename, source)
        : minerUParser.parse(filename, source);
  }

  @Override
  public ParsedDocument parse(
      String filename, InputStream source, String resumeTaskId, Consumer<String> taskIdSink) {
    return LOCAL_TEXT_EXTENSIONS.contains(TextDocumentParserAdapter.extension(filename))
        ? textParser.parse(filename, source)
        : minerUParser.parse(filename, source, resumeTaskId, taskIdSink);
  }
}
