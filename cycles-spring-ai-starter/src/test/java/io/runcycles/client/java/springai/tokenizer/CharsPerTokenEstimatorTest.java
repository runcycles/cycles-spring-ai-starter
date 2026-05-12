package io.runcycles.client.java.springai.tokenizer;

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
 * Unit tests for {@link CharsPerTokenEstimator}. Pins the v0.2.0 char/4 heuristic
 * behavior and exercises the explicit-ratio constructor variant.
 */
@ExtendWith(MockitoExtension.class)
class CharsPerTokenEstimatorTest {

    @Mock ChatClientRequest request;

    @Test
    void estimatesAsCharsDividedByFourByDefault() {
        // "hello world" is 11 chars; 11/4 = 2 (integer division — pinned behavior).
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("hello world"))));

        assertThat(new CharsPerTokenEstimator().estimateTokens(request)).isEqualTo(2L);
    }

    @Test
    void sumsCharsAcrossAllMessages() {
        when(request.prompt()).thenReturn(new Prompt(List.of(
                new UserMessage("twelve chars"),    // 12 chars
                new UserMessage("eight ch")          // 8 chars
        )));
        // 20 chars total / 4 = 5 tokens.
        assertThat(new CharsPerTokenEstimator().estimateTokens(request)).isEqualTo(5L);
    }

    @Test
    void skipsMessagesWithNullText() {
        // Multimodal messages may have null .getText() (content lives in attachments).
        Message nullTextMessage = mock(Message.class);
        when(nullTextMessage.getText()).thenReturn(null);
        when(request.prompt()).thenReturn(new Prompt(List.of(
                nullTextMessage,
                new UserMessage("twelve chars")
        )));

        // Only the 12-char message counts: 12 / 4 = 3.
        assertThat(new CharsPerTokenEstimator().estimateTokens(request)).isEqualTo(3L);
    }

    @Test
    void returnsZeroForNullRequest() {
        assertThat(new CharsPerTokenEstimator().estimateTokens(null)).isZero();
    }

    @Test
    void returnsZeroWhenPromptIsNull() {
        when(request.prompt()).thenReturn(null);
        assertThat(new CharsPerTokenEstimator().estimateTokens(request)).isZero();
    }

    @Test
    void returnsZeroForEmptyPrompt() {
        when(request.prompt()).thenReturn(new Prompt(List.of()));
        assertThat(new CharsPerTokenEstimator().estimateTokens(request)).isZero();
    }

    @Test
    void honorsExplicitCharsPerTokenRatio() {
        // For dense CJK text or other languages where each char is often one token,
        // users can override the ratio.
        when(request.prompt()).thenReturn(new Prompt(List.of(new UserMessage("eight ch"))));

        // 8 chars / 1 = 8 tokens (CJK-like ratio).
        assertThat(new CharsPerTokenEstimator(1L).estimateTokens(request)).isEqualTo(8L);
        // 8 chars / 8 = 1 token (extreme over-estimate ratio).
        assertThat(new CharsPerTokenEstimator(8L).estimateTokens(request)).isEqualTo(1L);
    }

    @Test
    void rejectsZeroOrNegativeCharsPerToken() {
        assertThatThrownBy(() -> new CharsPerTokenEstimator(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new CharsPerTokenEstimator(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
