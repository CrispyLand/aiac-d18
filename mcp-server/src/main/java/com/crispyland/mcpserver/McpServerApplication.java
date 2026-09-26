package com.crispyland.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A standalone MCP server, run separately from the agent and talked to over HTTP.
 * <p>
 * It holds no agent code and no model client of the agent's kind. That separation is the exercise:
 * the agent discovers what this can do by asking it at runtime, not by having been compiled against
 * it, which is the difference between a tool protocol and an ordinary dependency.
 * <p>
 * {@code @EnableScheduling} is here because this server now does something on its own initiative, not
 * only when asked. It is also what makes Boot contribute a {@code TaskScheduler} bean at all — the
 * briefing job holds one and swaps its task, rather than using {@code @Scheduled}, whose cron cannot
 * be changed after startup.
 */
@SpringBootApplication
@EnableScheduling
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
