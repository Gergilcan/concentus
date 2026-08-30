package com.concentus.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only channel between workers of a fan-out.
 *
 * <p>Deliberately narrow: short notes, no conversation, no way to ask a sibling something and
 * wait. A worker that could block on another worker would be a worker that can deadlock with it.
 */
class SharedNotesTest {

    private final AgentRun run = new AgentRun("run1", "flow1", "Flow");

    @Test
    void aWorkerReadsWhatTheOthersFoundAndNotItsOwn() {
        assertThat(run.shareNote("Researcher", "The 2023 figures on the wiki are wrong.")).isNull();
        assertThat(run.shareNote("Writer", "Using the PDF instead.")).isNull();

        // Telling a worker what it just said costs it context to learn nothing.
        assertThat(run.notesFor("Researcher")).extracting(AgentRun.SharedNote::author)
                .containsExactly("Writer");
        assertThat(run.notesFor("Nobody")).hasSize(2);
    }

    @Test
    void theSameNoteTwiceIsRefused() {
        run.shareNote("Researcher", "The API needs a token.");

        // A worker repeating itself adds nothing and costs every sibling a read.
        assertThat(run.shareNote("Researcher", "the api needs a token."))
                .contains("already there");
        assertThat(run.sharedNotes).hasSize(1);
    }

    @Test
    void anEmptyNoteIsRefusedRatherThanStored() {
        assertThat(run.shareNote("Researcher", "   ")).isNotNull();
        assertThat(run.shareNote("Researcher", null)).isNotNull();
        assertThat(run.sharedNotes).isEmpty();
    }

    @Test
    void aLongNoteIsCutRatherThanRejected() {
        String essay = "x".repeat(AgentRun.MAX_SHARED_NOTE_CHARS + 500);

        // Refusing it would lose the finding; the first six hundred characters keep the useful
        // part, and a finding that needs more than that is a report rather than a note.
        assertThat(run.shareNote("Researcher", essay)).isNull();
        assertThat(run.sharedNotes.get(0).text().length())
                .isEqualTo(AgentRun.MAX_SHARED_NOTE_CHARS + 1);
    }

    @Test
    void aWorkerThatNarratesIsStoppedBeforeItFillsEverybodysContext() {
        for (int i = 0; i < AgentRun.MAX_SHARED_NOTES; i++) {
            assertThat(run.shareNote("Chatty", "finding number " + i)).isNull();
        }

        assertThat(run.shareNote("Chatty", "one more")).contains("full");
        assertThat(run.sharedNotes).hasSize(AgentRun.MAX_SHARED_NOTES);
    }

    @Test
    void notesArriveInTheOrderTheyWereFound() {
        run.shareNote("A", "first");
        run.shareNote("B", "second");
        run.shareNote("A", "third");

        // Order is information: "the source was wrong" after "I used that source" reads
        // differently from the other way round.
        assertThat(run.sharedNotes).extracting(AgentRun.SharedNote::text)
                .containsExactly("first", "second", "third");
    }
}
