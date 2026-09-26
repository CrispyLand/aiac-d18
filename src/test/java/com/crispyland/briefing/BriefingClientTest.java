package com.crispyland.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client's contract is not "reads the admin API" — it is "never throws".
 * <p>
 * That is what these tests are about. It is called from a {@code @ModelAttribute}, so it runs before
 * every rendered view including the POST that answers a chat turn; an exception escaping it would turn
 * "the briefing server is restarting" into "the chat page is broken". The happy path is tested too, but
 * only to prove that the unreachable panel is a decision rather than the only thing the parser can
 * produce.
 */
class BriefingClientTest {

    private static final String BASE = "http://localhost:8081";

    /**
     * Both clients point at the same mock server, because the difference between them is a read
     * timeout and nothing this test can observe. The interesting behaviour is which one each method
     * reaches for, and that is checked by asserting the method and path of the recorded request.
     */
    private record Fixture(BriefingClient client, MockRestServiceServer server) {
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        return new Fixture(new BriefingClient(client, client, JsonMapper.builder().build(), BASE),
                server);
    }

    @Test
    void aServerThatIsNotRunningYieldsAnUnreachablePanelRatherThanAnException() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing"))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        BriefingPanel panel = fixture.client().panel();

        assertThat(panel.reachable()).isFalse();
        // The reason is carried, not swallowed: "unreachable" with no cause sends the reader to the
        // logs of the wrong process about half the time.
        assertThat(panel.error()).contains("Connection refused");
        // And the panel is still a usable one — the template reads latest() and runs() unguarded.
        assertThat(panel.latest()).isEqualTo(BriefingPanel.Day.NONE);
        assertThat(panel.runs()).isEmpty();
        assertThat(panel.options()).isEmpty();
        assertThat(panel.hasBriefing()).isFalse();
    }

    @Test
    void aFiveHundredYieldsAnUnreachablePanelRatherThanAnException() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing")).andRespond(withServerError());

        BriefingPanel panel = fixture.client().panel();

        assertThat(panel.reachable()).isFalse();
        assertThat(panel.error()).isNotEmpty();
    }

    /**
     * A 200 whose body is not the shape this expects is the failure nobody plans for — a field
     * renamed on the server, a proxy answering with an HTML error page. It has to land in the same
     * place as a refused connection.
     */
    @Test
    void abodyThatIsNotJsonYieldsAnUnreachablePanelRatherThanAnException() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing"))
                .andRespond(withSuccess("<html>502 Bad Gateway</html>", MediaType.TEXT_HTML));

        assertThat(fixture.client().panel().reachable()).isFalse();
    }

    @Test
    void aFieldTheServerStopsSendingCostsThatFieldAndNotTheCard() {
        Fixture fixture = fixture();
        // No "runs", no "lastRun", no "zone", and a "latest" missing half its numbers.
        fixture.server().expect(requestTo(BASE + "/admin/briefing"))
                .andRespond(withSuccess("""
                        {"schedule":"daily","scheduleLabel":"daily at 07:00",
                         "latest":{"present":true,"date":"2026-09-26"}}
                        """, MediaType.APPLICATION_JSON));

        BriefingPanel panel = fixture.client().panel();

        assertThat(panel.reachable()).isTrue();
        assertThat(panel.schedule()).isEqualTo("daily");
        assertThat(panel.latest().present()).isTrue();
        assertThat(panel.latest().date()).isEqualTo("2026-09-26");
        assertThat(panel.latest().events()).isZero();
        assertThat(panel.lastRun()).isNull();
        assertThat(panel.runs()).isEmpty();
    }

    @Test
    void aHealthyAnswerIsParsedIntoTheCardTheTemplateExpects() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing"))
                .andRespond(withSuccess("""
                        {"schedule":"daily","scheduleLabel":"daily at 07:00",
                         "options":[{"id":"off","label":"off"},{"id":"daily","label":"daily at 07:00"}],
                         "nextRunAtLocal":"2026-09-27 07:00","zone":"Asia/Shanghai",
                         "latest":{"present":true,"date":"2026-09-26","collectedAtLocal":"07:00",
                                   "events":3,"allDayEvents":1,"bookedTime":"2h 30m","tasksDue":4,
                                   "tasksOverdue":1,"figures":"3 events, 2h 30m booked",
                                   "highlights":["09:30 Standup","14:00 Review"],
                                   "narrative":"Three meetings today.","narrated":true},
                         "lastRun":{"present":true,"atLocal":"07:00","ok":true,"narrated":true,
                                    "detail":"3 events","durationMillis":1840},
                         "runs":[{"present":true,"atLocal":"07:00","ok":true,"narrated":true,
                                  "detail":"3 events","durationMillis":1840},
                                 {"present":true,"atLocal":"07:01","ok":true,"narrated":false,
                                  "detail":"unchanged — 3 events","durationMillis":310}]}
                        """, MediaType.APPLICATION_JSON));

        BriefingPanel panel = fixture.client().panel();

        assertThat(panel.reachable()).isTrue();
        assertThat(panel.off()).isFalse();
        assertThat(panel.hasBriefing()).isTrue();
        assertThat(panel.missingNarrative()).isFalse();
        assertThat(panel.lastRunFailed()).isFalse();
        // nextRunAt reads the server's *local* rendering, never an instant: the agent's own JVM is
        // UTC on the VPS, and formatting here would print the 07:00 run as 23:00 the day before.
        assertThat(panel.nextRunAt()).isEqualTo("2026-09-27 07:00");
        assertThat(panel.zone()).isEqualTo("Asia/Shanghai");
        assertThat(panel.options()).hasSize(2);
        assertThat(panel.latest().highlights()).containsExactly("09:30 Standup", "14:00 Review");
        assertThat(panel.latest().narrative()).isEqualTo("Three meetings today.");
        assertThat(panel.runs()).hasSize(2);
        assertThat(panel.runs().get(1).unchanged()).isTrue();
        assertThat(panel.runs().get(0).unchanged()).isFalse();
    }

    /** The state the card must not let look healthy: figures filed, no sentence. */
    @Test
    void figuresWithoutASentenceAreReportedAsMissingNarrativeAndNotAsAQuietDay() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing"))
                .andRespond(withSuccess("""
                        {"schedule":"daily",
                         "latest":{"present":true,"date":"2026-09-26","events":3,"narrated":false,
                                   "figures":"3 events"},
                         "lastRun":{"present":true,"atLocal":"07:00","ok":false,
                                    "detail":"credentials file not found","durationMillis":40}}
                        """, MediaType.APPLICATION_JSON));

        BriefingPanel panel = fixture.client().panel();

        assertThat(panel.missingNarrative()).isTrue();
        assertThat(panel.lastRunFailed()).isTrue();
    }

    @Test
    void changingTheSchedulePostsTheChoiceAndReturnsTheStateThatResultedFromIt() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing/schedule?schedule=every-minute"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"schedule\":\"every-minute\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(fixture.client().changeSchedule("every-minute").schedule())
                .isEqualTo("every-minute");
        fixture.server().verify();
    }

    /**
     * A timeout on "collect now" is the one failure this client cannot distinguish from a server that
     * died, and the card is what has to admit it — the work may well still be running.
     */
    @Test
    void collectNowThatTimesOutIsAnUnreachablePanelAndNotAnException() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/admin/briefing/collect"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new java.net.SocketTimeoutException("Read timed out");
                });

        BriefingPanel panel = fixture.client().collectNow();

        assertThat(panel.reachable()).isFalse();
        assertThat(panel.error()).contains("Read timed out");
    }

    /** A base URL with a trailing slash must not produce {@code //admin/briefing}. */
    @Test
    void aTrailingSlashOnTheConfiguredUrlIsNotTurnedIntoADoubleSlash() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        BriefingClient client =
                new BriefingClient(rest, rest, JsonMapper.builder().build(), BASE + "/");
        server.expect(requestTo(BASE + "/admin/briefing"))
                .andRespond(withSuccess("{\"schedule\":\"off\"}", MediaType.APPLICATION_JSON));

        assertThat(client.panel().reachable()).isTrue();
        server.verify();
    }
}
