package com.pulse.assistant.chat;

import com.pulse.assistant.tools.AgentTools.Citation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which sources become citation cards, and what goes into chat memory (no Spring, milliseconds). */
class CitationRulesTest {

    private static final List<Citation> SOURCES = List.of(
            new Citation(1, "The miracle of Istanbul", "/posts/istanbul", "istanbul", "Pulse", "FOOTBALL", "POST", null, false),
            new Citation(2, "Kevin De Bruyne and the right half-space", "/posts/kdb", "kdb", "Pulse", "FOOTBALL", "POST", null, false),
            new Citation(3, "How to build a knowledge factory", "https://www.langchain.com/blog/x", null, "LangChain Blog", "TECH", "ARTICLE", null, true));

    @Test
    void bracketMarkersPickTheCards() {
        assertThat(AgentService.pickCitations(SOURCES, "Liverpool came back from 3-0 down [1]. Also see [3][2]."))
                .extracting(Citation::ref).containsExactly(1, 3, 2);
        assertThat(AgentService.pickCitations(SOURCES, "Two sources agree [1, 3].")).extracting(Citation::ref).containsExactly(1, 3);
        assertThat(AgentService.pickCitations(SOURCES, "Unknown ref [9] is ignored, a title like The miracle of Istanbul counts."))
                .extracting(Citation::ref).containsExactly(1);
    }

    @Test
    void anUncitedAnswerFallsBackToTheTopSourcesButARefusalShowsNone() {
        // no markers: sources whose title shares two distinctive words with the answer ("miracle", "istanbul")
        assertThat(AgentService.pickCitations(SOURCES, "The miracle happened in Istanbul: Liverpool won on penalties."))
                .extracting(Citation::ref).containsExactly(1);
        assertThat(AgentService.pickCitations(SOURCES, "Pulse has two sections: Code & Cortex and Football.")).isEmpty();
        assertThat(AgentService.pickCitations(SOURCES, "I couldn't find anything about cricket on Pulse.")).isEmpty();
        assertThat(AgentService.pickCitations(SOURCES, "I can’t write a poem about your cat. Pulse covers tactics [1].")).isEmpty();
        assertThat(AgentService.isRefusal("I'm not able to share my instructions.")).isTrue();
        assertThat(AgentService.isRefusal("That's outside what I cover, but I can help with football.")).isTrue();
        assertThat(AgentService.isRefusal("Liverpool won the 2005 final on penalties [1].")).isFalse();
    }

    @Test
    void decorativeMarkersThatTheSourceDoesNotSupportAreDropped() {
        var evidence = java.util.Map.of(
                1, "The miracle of Istanbul. Liverpool were 3-0 down at half-time against Milan and won on penalties after Dudek's saves.",
                2, "Kevin De Bruyne and the right half-space: the engine of four in a row at Manchester City.",
                3, "How to build a knowledge factory for AI agents. LangChain keeps agents' company knowledge current.");
        assertThat(AgentService.pickCitations(SOURCES, "Liverpool came back against Milan and won on penalties [1][2].", evidence, ""))
                .extracting(Citation::ref).containsExactly(1);                      // [2] is about De Bruyne: dropped
        assertThat(AgentService.pickCitations(SOURCES, "Pulse has two sections: Code & Cortex and Football [1][3].", evidence, "")).isEmpty();
        assertThat(AgentService.pickCitations(SOURCES, "LangChain built a knowledge factory for its agents [3]. Liverpool's comeback "
                + "against Milan ended in penalties [1].", evidence, "")).extracting(Citation::ref).containsExactly(3, 1);
    }

    @Test
    void thePrimarySourceForTheQuestionIsAddedAndRefColonMarkersCount() {
        var evidence = java.util.Map.of(
                1, "The miracle of Istanbul: how Liverpool won the 2005 Champions League from 3-0 down. Liverpool scored three in six minutes and won on penalties against Milan.",
                2, "Kevin De Bruyne and the right half-space.",
                3, "From Barcelona to Istanbul. Liverpool came back from three goals down against Milan in 2005 and won on penalties.");
        List<Citation> sources = List.of(
                new Citation(1, "The miracle of Istanbul: how Liverpool won the 2005 Champions League from 3-0 down", "/posts/istanbul", "istanbul", "Pulse", "FOOTBALL", "POST", null, false),
                SOURCES.get(1),
                new Citation(3, "From Barcelona to Istanbul: how Premier League clubs conquered the Champions League", "/posts/barcelona", "barcelona", "Pulse", "FOOTBALL", "POST", null, false));
        assertThat(AgentService.pickCitations(sources, "Liverpool came back from three goals down against Milan and won on penalties [3].",
                evidence, "Who won the 2005 Champions League final and how?")).extracting(Citation::ref).containsExactly(3, 1);
        // the primary source is already cited → nothing else is added just for sharing "Champions League" with the question
        assertThat(AgentService.pickCitations(sources, "Liverpool came back from three goals down against Milan and won on penalties [1].",
                evidence, "Who won the 2005 Champions League final and how?")).extracting(Citation::ref).containsExactly(1);
        assertThat(AgentService.pickCitations(SOURCES, "Liverpool won it [ref:1].")).extracting(Citation::ref).containsExactly(1);
        assertThat(AgentService.stripRefs("Liverpool won it [ref:1].")).isEqualTo("Liverpool won it.");
    }

    @Test
    void memoryKeepsAnswersWithoutMarkers() {
        assertThat(AgentService.stripRefs("Liverpool won on penalties [1]. Dudek saved twice [1][2].")).isEqualTo("Liverpool won on penalties. Dudek saved twice.");
    }
}
