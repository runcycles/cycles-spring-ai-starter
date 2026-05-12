package io.runcycles.client.java.springai.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link PromptTokenEstimator} that uses jtokkit's real BPE encoding for OpenAI-family
 * tokenizers. Significantly more accurate than the {@link CharsPerTokenEstimator}
 * heuristic, especially for non-English text, code, and JSON.
 *
 * <p>Supported encodings (see jtokkit's {@code EncodingType}):
 * <ul>
 *   <li>{@code cl100k_base} — gpt-3.5-turbo, gpt-4, text-embedding-3-*.</li>
 *   <li>{@code o200k_base} — gpt-4o, gpt-4o-mini, o1 family.</li>
 *   <li>{@code p50k_base} / {@code r50k_base} — older models (gpt-3, code-davinci).</li>
 * </ul>
 *
 * <p>Opt in by registering this class as your {@link PromptTokenEstimator} bean (the
 * auto-configuration only registers it when {@code cycles.spring-ai.token-estimator-encoding}
 * is set AND jtokkit is on the classpath; otherwise the default
 * {@link CharsPerTokenEstimator} stays in place). Example:
 *
 * <pre>{@code
 * # application.yml
 * cycles:
 *   spring-ai:
 *     token-estimator-encoding: o200k_base   # gpt-4o family
 * }</pre>
 *
 * <p>jtokkit is declared as an {@code optional=true} dependency on this starter —
 * users who opt in must add it explicitly:
 *
 * <pre>{@code
 * <dependency>
 *     <groupId>com.knuddels</groupId>
 *     <artifactId>jtokkit</artifactId>
 *     <version>1.1.0</version>
 * </dependency>
 * }</pre>
 *
 * <p>The encoding registry is created once at construction time and cached for the
 * lifetime of the bean. jtokkit's encodings are thread-safe.
 */
public class JtokkitPromptTokenEstimator implements PromptTokenEstimator {

    private final Encoding encoding;

    /**
     * Constructs an estimator using the supplied jtokkit encoding type name.
     *
     * @param encodingName one of jtokkit's {@code EncodingType} names — case-insensitive
     *                     (e.g. {@code "cl100k_base"}, {@code "o200k_base"}).
     * @throws IllegalArgumentException when the name doesn't map to a known
     *                                  {@code EncodingType}.
     */
    public JtokkitPromptTokenEstimator(String encodingName) {
        EncodingType type = parseEncodingType(encodingName);
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(type);
    }

    /**
     * Constructs an estimator using a pre-resolved {@link EncodingType}. Useful for
     * tests and for callers who already have the enum in hand.
     *
     * @param encodingType the BPE encoding to use.
     */
    public JtokkitPromptTokenEstimator(EncodingType encodingType) {
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(encodingType);
    }

    @Override
    public long estimateTokens(ChatClientRequest request) {
        if (request == null) {
            return 0L;
        }
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return 0L;
        }
        long total = 0L;
        for (Message message : prompt.getInstructions()) {
            String text = message.getText();
            if (text != null && !text.isEmpty()) {
                total += encoding.countTokens(text);
            }
        }
        return total;
    }

    private static EncodingType parseEncodingType(String name) {
        if (name == null) {
            throw new IllegalArgumentException("encoding name must not be null");
        }
        for (EncodingType type : EncodingType.values()) {
            // type.getName() returns "cl100k_base"-style identifiers. We compare
            // case-insensitively so users can write either "cl100k_base" or
            // "CL100K_BASE" in application.yml without surprise.
            if (type.getName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Unknown jtokkit encoding: '" + name + "'. Supported: cl100k_base, "
                        + "o200k_base, p50k_base, p50k_edit, r50k_base.");
    }
}
