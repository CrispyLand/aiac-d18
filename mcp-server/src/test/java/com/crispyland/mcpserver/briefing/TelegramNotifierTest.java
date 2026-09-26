package com.crispyland.mcpserver.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class TelegramNotifierTest {

    private static final String TOKEN = "123:abc";
    private static final String CHAT_ID = "456789";
    private static final String EXPECTED_URL =
            "https://api.telegram.org/bot" + TOKEN + "/sendMessage";

    private record Fixture(TelegramNotifier notifier, MockRestServiceServer server) {
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramProperties props = new TelegramProperties(TOKEN, CHAT_ID);
        return new Fixture(
                new TelegramNotifier(builder.build(), JsonMapper.builder().build(), props),
                server);
    }

    private static Briefing briefingWith(String narrative) {
        return new Briefing(LocalDate.of(2026, 9, 26), Instant.now(),
                3, 0, 150, 4, 1,
                List.of("09:30 Standup", "14:00 Review"),
                narrative);
    }

    @Test
    void noneIsASilentNoOp() {
        // No mock server — any HTTP call would throw. Proves NONE makes no call.
        assertThatNoException().isThrownBy(
                () -> TelegramNotifier.NONE.send(briefingWith("Three meetings today.")));
    }

    @Test
    void aBriefingWithNoNarrativeIsNotSent() {
        Fixture f = fixture();
        // Server expects no call — any call would fail the test via verify.
        f.notifier().send(briefingWith(""));
        f.server().verify();
    }

    @Test
    void aSuccessfulDeliveryPostsTheCorrectUrl() {
        Fixture f = fixture();
        f.server().expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        f.notifier().send(briefingWith("Three meetings today."));

        f.server().verify();
    }

    @Test
    void theMessageBodyContainsTheDateFiguresNarrativeAndHighlights() {
        Fixture f = fixture();
        f.server().expect(requestTo(EXPECTED_URL))
                .andExpect(content().string(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("2026"),
                                org.hamcrest.Matchers.containsString("3 events"),
                                org.hamcrest.Matchers.containsString("Three meetings today."),
                                org.hamcrest.Matchers.containsString("09:30 Standup"),
                                org.hamcrest.Matchers.containsString("14:00 Review"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        f.notifier().send(briefingWith("Three meetings today."));

        f.server().verify();
    }

    @Test
    void aFiveHundredFromTelegramDoesNotThrow() {
        Fixture f = fixture();
        f.server().expect(requestTo(EXPECTED_URL)).andRespond(withServerError());

        assertThatNoException().isThrownBy(
                () -> f.notifier().send(briefingWith("Three meetings today.")));
    }

    @Test
    void aNetworkErrorDoesNotThrow() {
        Fixture f = fixture();
        f.server().expect(requestTo(EXPECTED_URL))
                .andRespond(request -> { throw new ConnectException("Connection refused"); });

        assertThatNoException().isThrownBy(
                () -> f.notifier().send(briefingWith("Three meetings today.")));
    }

    @Test
    void notConfiguredWhenBotTokenIsBlank() {
        assertThat(new TelegramProperties("", CHAT_ID).configured()).isFalse();
    }

    @Test
    void notConfiguredWhenChatIdIsBlank() {
        assertThat(new TelegramProperties(TOKEN, "").configured()).isFalse();
    }

    @Test
    void configuredWhenBothArePresent() {
        assertThat(new TelegramProperties(TOKEN, CHAT_ID).configured()).isTrue();
    }
}
