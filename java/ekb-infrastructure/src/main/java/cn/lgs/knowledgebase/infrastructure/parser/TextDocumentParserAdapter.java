package cn.lgs.knowledgebase.infrastructure.parser;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses text-native formats locally instead of sending unsupported files to MinerU. */
public final class TextDocumentParserAdapter implements ParserPort {
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern BLOCK_TAG = Pattern.compile("(?i)</?(?:p|div|section|article|header|footer|main|aside|nav|h[1-6]|li|ul|ol|table|tr|td|th|br)\\b[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");

    private final ObjectMapper mapper;

    public TextDocumentParserAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ParsedDocument parse(String filename, InputStream source) {
        String extension = extension(filename);
        String raw;
        try {
            raw = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read text document " + filename, e);
        }
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
        String normalized = normalizeNewlines(".html".equals(extension) || ".htm".equals(extension) ? htmlToText(raw) : raw);
        List<ParsedBlock> blocks = ".md".equals(extension) ? markdownBlocks(normalized) : paragraphBlocks(normalized);
        if (blocks.isEmpty() && !normalized.isBlank()) blocks = List.of(block(normalized.trim(), 0));
        try {
            List<Map<String, Object>> content = new ArrayList<>(blocks.size());
            for (ParsedBlock block : blocks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", block.type());
                item.put("text", block.text());
                item.put("text_level", block.textLevel());
                content.add(item);
            }
            String middle = mapper.writeValueAsString(Map.of("source_type", extension.replace(".", ""), "parser", "local-text"));
            return new ParsedDocument(normalized, middle, mapper.writeValueAsString(content),
                    "local-text", "1", List.copyOf(blocks));
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize local text parse result", e);
        }
    }

    private static List<ParsedBlock> markdownBlocks(String text) {
        List<ParsedBlock> out = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            Matcher heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                flush(paragraph, out);
                out.add(block(heading.group(2).trim(), heading.group(1).length()));
            } else if (line.isBlank()) {
                flush(paragraph, out);
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flush(paragraph, out);
        return out;
    }

    private static List<ParsedBlock> paragraphBlocks(String text) {
        List<ParsedBlock> out = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            if (line.isBlank()) flush(paragraph, out);
            else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flush(paragraph, out);
        return out;
    }

    private static void flush(StringBuilder paragraph, List<ParsedBlock> out) {
        String value = paragraph.toString().trim();
        if (!value.isEmpty()) out.add(block(value, 0));
        paragraph.setLength(0);
    }

    private static ParsedBlock block(String text, int level) {
        return new ParsedBlock("text", text, level, null, List.of(), List.of(), "", "", "",
                Map.of("type", "text", "text", text, "text_level", level));
    }

    private static String htmlToText(String html) {
        String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = ANY_TAG.matcher(text).replaceAll(" ");
        return text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    static String extension(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        return dot > slash ? filename.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
    }
}
