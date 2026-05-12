package io.runcycles.demo.springai;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot app demonstrating the Cycles Spring AI starter wiring.
 *
 * <p>This class is intentionally a stub — it boots the context with the Cycles starter
 * on the classpath so the auto-configuration triggers, but it does not yet make any
 * real ChatClient calls. Real demo flow lands with v0.1.0 once {@code CyclesBudgetAdvisor}
 * has its pre/post hooks implemented.
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
