package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RawKnowledgeBuilderTest {
    @Test
    void rawBlocksPreserveHeadingPageBboxAndPageSize() {
        var parsed = List.of(
                new ParsedBlock("text", "第一章", 1, 0, List.of(1d, 2d, 3d, 4d), List.of(595d, 842d), "", "", "", Map.of()),
                new ParsedBlock("text", "正文内容", 0, 0, List.of(5d, 6d, 7d, 8d), List.of(595d, 842d), "", "", "", Map.of()));
        var blocks = RawKnowledgeBuilder.rawBlocks("v1", parsed);

        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).id().startsWith("rb_"));
        assertEquals(43, blocks.get(0).id().length());
        assertEquals(List.of("第一章"), blocks.get(1).headingPath());
        assertEquals(1, blocks.get(1).pageStart());
        assertEquals(List.of(595d, 842d), blocks.get(1).pageSize());
        assertEquals(List.of(5d, 6d, 7d, 8d), blocks.get(1).bbox());
    }

    @Test
    void tableRemainsAtomicAndChunkIdsAreStableForSameVersionAndBlocks() {
        String longText = "测试内容".repeat(1200);
        var parsed = List.of(
                new ParsedBlock("text", longText, 0, 0, List.of(), List.of(), "", "", "", Map.of()),
                new ParsedBlock("table", "", 0, 1, List.of(), List.of(), "", "<table>x</table>", "a|b\n1|2", Map.of()),
                new ParsedBlock("text", "结尾", 0, 2, List.of(), List.of(), "", "", "", Map.of()));
        var blocks = RawKnowledgeBuilder.rawBlocks("v1", parsed);
        var md = new Metadata("dept", "cat", List.of(), "company", null, "REPORT");
        var first = RawKnowledgeBuilder.smartChunks("d1", "v1", md, blocks);
        var second = RawKnowledgeBuilder.smartChunks("d1", "v1", md, blocks);

        assertEquals(first.stream().map(RawKnowledgeBuilder.ChunkDraft::id).toList(), second.stream().map(RawKnowledgeBuilder.ChunkDraft::id).toList());
        assertTrue(first.stream().anyMatch(c -> c.content().contains("a|b\n1|2")));
        assertTrue(first.stream().filter(c -> c.content().contains("a|b\n1|2")).allMatch(c -> c.sourceBlockIds().contains(blocks.get(1).id())));
    }

    @Test
    void evidenceIdsFollowGoDeterministicUuidContract() {
        var blocks = RawKnowledgeBuilder.rawBlocks("version-1",
                List.of(new ParsedBlock("text", "evidence", 0, 0, List.of(), List.of(), "", "", "", Map.of())));
        var a = RawKnowledgeBuilder.evidence("doc", "original/doc/version/file.pdf", blocks).get(0);
        var b = RawKnowledgeBuilder.evidence("doc", "original/doc/version/file.pdf", blocks).get(0);
        assertEquals(a.id(), b.id());
        assertEquals(36, a.id().length());
        assertEquals(blocks.get(0).contentHash(), a.hash());
        assertEquals(blocks.get(0).id(), a.location().get("block_id"));
    }
}
