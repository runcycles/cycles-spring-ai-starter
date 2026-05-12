package io.runcycles.client.java.springai.tokenizer;

import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JtokkitPromptTokenEstimator}. Verifies real BPE token counts
 * (significantly different from the chars/4 heuristic on certain inputs), encoding
 * resolution by name (case-insensitive), error cases on unknown encodings, and
 * null-handling parity with {@link CharsPerTokenEstimator}.
 */
@ExtendWith(MockitoExtension.class)
class JtokkitPromptTokenEstimatorTest {

    @Mock ChatClientRequest request;

    @Test
    void countsTokensViaCl100kBaseEncoding() {
        // "Hello, world!" on cl100k_base tokenizes to specific known token count.
        // Pinning the exact count guards against silent jtokkit-version regressions.
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("Hello, world!"))));

        long tokens = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request);

        // jtokkit cl100k_base encodes "Hello, world!" as 4 tokens. Heuristic chars/4
        // would say 13/4 = 3. Real BPE is materially different from the heuristic on
        // even simple inputs.
        assertThat(tokens).isEqualTo(4L);
    }

    @Test
    void differsMeaningfullyFromCharsHeuristicOnEnglishText() {
        // The whole point of jtokkit is more-accurate token counts. Use a longer
        // English string and assert the BPE estimate is closer to typical
        // English-tokenization ratios than chars/4 is.
        String text = "The quick brown fox jumps over the lazy dog.";
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage(text))));

        long chars = text.length();
        long bpeTokens = new JtokkitPromptTokenEstimator(EncodingType.CL100K_BASE).estimateTokens(request);
        long charsHeuristic = chars / 4;

        // Both estimates land in a sane range for this sentence (no crazy outliers).
        assertThat(bpeTokens).isGreaterThan(0).isLessThan(chars);
        assertThat(charsHeuristic).isGreaterThan(0).isLessThan(chars);

        // The BPE count should NOT equal the chars/4 heuristic — that's the whole
        // reason for using a real tokenizer.
        assertThat(bpeTokens).isNotEqualTo(charsHeuristic);
    }

    @Test
    void sumsTokensAcrossAllMessages() {
        when(request.prompt()).thenReturn(new Prompt(List.of(
                new UserMessage("Hello"),
                new UserMessage("world")
        )));

        long combined = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request);
        long individual = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(stubRequest("Hello"))
                        + new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(stubRequest("world"));

        assertThat(combined).isEqualTo(individual);
    }

    @Test
    void skipsMessagesWithNullText() {
        Message nullText = mock(Message.class);
        when(nullText.getText()).thenReturn(null);
        when(request.prompt()).thenReturn(new Prompt(List.of(
                nullText,
                new UserMessage("Hello")
        )));

        // Should count only "Hello" — same as a single-message prompt with just that.
        long actual = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request);
        long expected = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(stubRequest("Hello"));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void skipsEmptyTextMessages() {
        // Tests the .isEmpty() short-circuit in the encoding loop.
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage(""))));

        assertThat(new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request)).isZero();
    }

    @Test
    void returnsZeroForNullRequest() {
        assertThat(new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(null)).isZero();
    }

    @Test
    void returnsZeroForNullPrompt() {
        when(request.prompt()).thenReturn(null);
        assertThat(new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request)).isZero();
    }

    @Test
    void supportsO200kBaseEncoding() {
        // Sanity check that gpt-4o family encoding works.
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("Hello, world!"))));

        long tokens = new JtokkitPromptTokenEstimator("o200k_base").estimateTokens(request);
        assertThat(tokens).isPositive();
    }

    @Test
    void supportsEncodingTypeConstructor() {
        // Direct EncodingType enum constructor — useful for callers that already have
        // the enum value (tests, programmatic configuration).
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("Hello"))));

        long viaString = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request);
        long viaEnum = new JtokkitPromptTokenEstimator(EncodingType.CL100K_BASE).estimateTokens(request);
        assertThat(viaString).isEqualTo(viaEnum);
    }

    @Test
    void encodingNameIsCaseInsensitive() {
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("Hello"))));

        long lower = new JtokkitPromptTokenEstimator("cl100k_base").estimateTokens(request);
        long upper = new JtokkitPromptTokenEstimator("CL100K_BASE").estimateTokens(request);
        long mixed = new JtokkitPromptTokenEstimator("Cl100k_Base").estimateTokens(request);

        assertThat(lower).isEqualTo(upper).isEqualTo(mixed);
    }

    @Test
    void rejectsUnknownEncodingName() {
        assertThatThrownBy(() -> new JtokkitPromptTokenEstimator("not_a_real_encoding"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown jtokkit encoding")
                .hasMessageContaining("not_a_real_encoding");
    }

    @Test
    void rejectsNullEncodingName() {
        assertThatThrownBy(() -> new JtokkitPromptTokenEstimator((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    private static ChatClientRequest stubRequest(String text) {
        ChatClientRequest stub = mock(ChatClientRequest.class);
        when(stub.prompt()).thenReturn(new Prompt(List.of(new UserMessage(text))));
        return stub;
    }
}
