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
    if (textParser == null) throw new IllegalArgumentException("text parser is required");
    this.textParser = textParser;
    this.minerUParser = minerUParser;
  }

  @Override
  public java.util.Optional<cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity>
      currentPipeline(String filename) {
    return delegate(filename).currentPipeline(filename);
  }

  @Override
  public ParsedDocument parse(
      String filename,
      InputStream source,
      String resumeTaskId,
      Consumer<String> taskIdSink,
      cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity expectedPipeline) {
    return delegate(filename).parse(filename, source, resumeTaskId, taskIdSink, expectedPipeline);
  }

  @Override
  public ParsedDocument parse(
      String filename,
      InputStream source,
      String resumeTaskId,
      Consumer<String> taskIdSink,
      cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity expectedPipeline,
      String expectedSourceSha256) {
    return delegate(filename)
        .parse(filename, source, resumeTaskId, taskIdSink, expectedPipeline, expectedSourceSha256);
  }

  @Override
  public ParsedDocument parse(String filename, InputStream source) {
    return delegate(filename).parse(filename, source);
  }

  @Override
  public ParsedDocument parse(
      String filename, InputStream source, String resumeTaskId, Consumer<String> taskIdSink) {
    ParserPort parser = delegate(filename);
    return parser == textParser
        ? parser.parse(filename, source)
        : parser.parse(filename, source, resumeTaskId, taskIdSink);
  }

  private ParserPort delegate(String filename) {
    if (LOCAL_TEXT_EXTENSIONS.contains(TextDocumentParserAdapter.extension(filename)))
      return textParser;
    if (minerUParser == null) {
      throw new IllegalStateException(
          "未配置 MinerU 文档解析服务。文本、Markdown、CSV、JSON 和 HTML 可直接入库；PDF 和 Office 文件请先配置解析服务后重试。");
    }
    return minerUParser;
  }
}
