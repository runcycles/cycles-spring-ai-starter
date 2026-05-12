package io.runcycles.demo.springai;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot app demonstrating the Cycles Spring AI starter wiring.
 *
 * <p>The Cycles Spring AI advisor is fully implemented as of v0.1.0 — any
 * {@code ChatClient.prompt(...).call()} invocation from this app goes through
 * {@code CyclesBudgetAdvisor}'s reserve / call / commit-or-release lifecycle.
 *
 * <p>This main class itself is intentionally minimal: it boots the context (which
 * is enough to verify wiring — see {@code DemoApplicationContextLoadsTest}) but
 * does not invoke a real provider, so the demo can run end-to-end without an
 * OpenAI API key. A {@code CommandLineRunner} that exercises an actual chat call
 * lives in the README example; to try it locally, set {@code OPENAI_API_KEY} and
 * add a runner bean that calls {@code chatClient.prompt(...)}.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    public CommandLineRunner banner() {
        return args -> System.out.println(
            "[cycles-spring-ai-demo] context booted — Cycles Spring AI starter is on the classpath.");
    }
}
