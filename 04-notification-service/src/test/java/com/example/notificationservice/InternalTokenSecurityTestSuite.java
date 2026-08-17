package com.example.notificationservice;

import com.example.notificationservice.job.DailyBalanceSummaryJob;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.security.InternalTokenFilter;
import com.example.notificationservice.service.NotificationProviderService;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers both halves of the shared {@code X-Internal-Token} contract in notification-service:
 * {@link InternalTokenFilter} on the way in, and the Feign {@link RequestInterceptor} declared by
 * {@code FeignInternalTokenConfig} on the way out. Every service in this project implements the
 * same contract identically, so the two halves are tested together — the failure mode worth
 * catching is the two sides drifting apart, which neither half can show on its own.
 *
 * <p>Until this filter existed, {@code /api/v1/internal/**} was protected by exactly one thing: the
 * k8s ingress choosing not to route that prefix. For this service that gap was worse than
 * elsewhere, because the endpoint sitting behind it triggers real email sends.
 *
 * <h2>What is real and what is mocked</h2>
 *
 * <p>Everything that decides the outcome is real: the servlet filter chain, the Spring Security
 * configuration, the internal controller, and the Feign interceptor bean pulled from the live
 * context. Only the collaborators <em>behind</em> the endpoint are replaced:
 *
 * <ul>
 *   <li>{@code DailyBalanceSummaryJob} — the sweep itself. Mocked so a rejected request can be
 *       proven to have done no work at all, rather than only proven to have returned 401; a 401
 *       returned after the sweep already ran would still have sent the email.</li>
 *   <li>{@code NotificationProviderService} — the SendGrid/Twilio dispatch layer. Mocked so no test
 *       in this suite can reach a real provider, and so "no email left the building" is directly
 *       verifiable.</li>
 *   <li>{@code NotificationRecordRepository} — persistence. Mocked both to keep the rejection tests
 *       free of database state and to stub the one row the customer-facing feed test reads back.</li>
 * </ul>
 *
 * <p>There is no {@code @BeforeEach}: the only shared state is the two constants and the
 * {@code buildRecord} helper, and Spring Boot resets the {@code @MockBean}s between tests, so each
 * test stubs and verifies from a clean mock. The suite is not transactional and writes nothing.
 *
 * <h2>Test configuration</h2>
 *
 * <p>{@code @SpringBootTest} + {@code @AutoConfigureMockMvc} rather than {@code @WebMvcTest}:
 * {@code InternalTokenFilter} is a plain {@code @Component} servlet filter registered by Boot's
 * filter auto-configuration, and the outbound {@code internalTokenRequestInterceptor} is not a web
 * bean at all. A sliced web test would load neither, and would therefore prove nothing about the
 * mechanism this suite exists to protect. The cost is a full context: because this module has no
 * {@code src/test/resources}, the suite boots the real dev configuration and needs the local Docker
 * Postgres up, even though the repository is mocked and no test touches a table.
 *
 * <p>{@code @TestPropertySource} pins {@code application.security.internal-token} to a value that
 * is deliberately <strong>not</strong> the dev default baked into the filter's {@code @Value}.
 * That is the point of the override, not incidental setup: with the default in place, a typo in the
 * property name would still pass every test here, because both sides would quietly fall back to
 * their own default and agree with each other. The mistake would then surface only in a deployed
 * environment where the override is real. (The sibling suite in account-service pins the default
 * value and is weaker for exactly this reason.)
 *
 * <p>{@code spring.datasource.hikari.maximum-pool-size=2} is not a performance tweak. Every
 * {@code @SpringBootTest} whose properties differ gets its own cached context, and each cached
 * context holds a Hikari pool open for the rest of the run — Hikari's {@code minimumIdle} defaults
 * to {@code maximumPoolSize}, so that is ten real connections parked per context. Adding this suite
 * pushed the shared local Postgres past {@code max_connections}, and the symptom was the
 * <em>last</em> suite to start failing with "sorry, too many clients already", a suite with nothing
 * to do with this change. The pool exists here only so Flyway and Hibernate can start.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "application.security.internal-token=suite-internal-token",
        "spring.datasource.hikari.maximum-pool-size=2"
})
class InternalTokenSecurityTestSuite {

    private static final String INTERNAL_RUN_PATH = "/api/v1/internal/notifications/daily-summary/run";
    private static final String CONFIGURED_TOKEN = "suite-internal-token";

    @Autowired
    private MockMvc mockMvc;

    // Fetched by name rather than by type so this asserts the bean FeignInternalTokenConfig declares,
    // not merely that some RequestInterceptor happens to be in the context.
    @Autowired
    @Qualifier("internalTokenRequestInterceptor")
    private RequestInterceptor internalTokenRequestInterceptor;

    @MockBean
    private DailyBalanceSummaryJob dailyBalanceSummaryJob;

    @MockBean
    private NotificationProviderService notificationProviderService;

    @MockBean
    private NotificationRecordRepository notificationRecordRepository;

    private static RequestPostProcessor fullAuthUser(long userId) {
        return jwt().jwt(j -> j.claim("scope", "FULL_AUTH").claim("userId", userId));
    }

    @Test
    @DisplayName("Internal daily-summary trigger with no X-Internal-Token is rejected and runs nothing - [MEANT TO PASS]")
    void triggerDailySummary_missingInternalToken_returns401AndRunsNoJob() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH).param("timezone", "America/New_York"))
                .andExpect(status().isUnauthorized());

        // These four verifies are the whole point of the filter: the request must die before the job,
        // and therefore before any provider call or record write, is ever reached.
        verify(dailyBalanceSummaryJob, never()).processUsersForTimezone(anyString());
        verify(dailyBalanceSummaryJob, never()).processDailySummaries();
        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationRecordRepository, never()).save(any(NotificationRecord.class));
    }

    @Test
    @DisplayName("Internal daily-summary trigger with a wrong X-Internal-Token is rejected - [MEANT TO PASS]")
    void triggerDailySummary_wrongInternalToken_returns401AndRunsNoJob() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .param("timezone", "America/New_York")
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "not-the-right-token"))
                .andExpect(status().isUnauthorized());

        verify(dailyBalanceSummaryJob, never()).processUsersForTimezone(anyString());
        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    /**
     * A token sharing a prefix with the real one must fail exactly like one sharing nothing.
     * {@code MessageDigest.isEqual} compares the full byte array either way; a {@code String.equals}
     * here would return faster on a token that matches nothing than on this one, leaking how much of
     * the secret a caller has already guessed.
     */
    @Test
    @DisplayName("A token matching only a prefix of the real one is rejected - [MEANT TO PASS]")
    void triggerDailySummary_tokenMatchingOnlyAPrefixOfTheRealOne_returns401() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "suite-internal"))
                .andExpect(status().isUnauthorized());

        verify(dailyBalanceSummaryJob, never()).processDailySummaries();
    }

    /**
     * The rejection body is part of the cross-service contract: JSON carrying both an {@code error}
     * and a {@code message} key. It must also stay uninformative — a 401 naming the header, the
     * property or the configured value is a free hint for anyone probing the prefix, which is why
     * the second block asserts on absence rather than presence.
     */
    @Test
    @DisplayName("The 401 body carries error and message and names no header, property or token value - [MEANT TO PASS]")
    void triggerDailySummary_missingInternalToken_returns401BodyWithErrorAndMessageAndNoHints() throws Exception {
        String body = mockMvc.perform(post(INTERNAL_RUN_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"error\"").contains("\"message\"");
        assertThat(body)
                .as("the 401 must not hand back the header name or the property that would satisfy it")
                .doesNotContain(InternalTokenFilter.INTERNAL_TOKEN_HEADER)
                .doesNotContain("X-Internal-Token")
                .doesNotContain("internal-token")
                .doesNotContain(CONFIGURED_TOKEN);
    }

    @Test
    @DisplayName("Internal daily-summary trigger with the correct X-Internal-Token behaves as before - [MEANT TO PASS]")
    void triggerDailySummary_correctTokenWithTimezone_returns200AndSweepsThatTimezone() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .param("timezone", "America/New_York")
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("Daily summary triggered for timezone America/New_York."));

        verify(dailyBalanceSummaryJob).processUsersForTimezone("America/New_York");
    }

    /**
     * Covered separately from the timezone form because the no-timezone form is a different branch
     * of the controller, and a filter that let only one of the two through would be worse than one
     * that let neither: the scheduled sweep would keep half working and half silently failing.
     */
    @Test
    @DisplayName("Internal trigger with the correct token and no timezone still runs the full sweep - [MEANT TO PASS]")
    void triggerDailySummary_correctTokenWithoutTimezone_returns200AndRunsFullSweep() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN))
                .andExpect(status().isOk());

        verify(dailyBalanceSummaryJob).processDailySummaries();
    }

    /**
     * The filter is scoped to {@code /api/v1/internal/} and nothing else. A customer opening the
     * Notifications page carries a JWT and has never heard of the internal token; if the filter
     * leaked past its prefix, the customer-facing endpoint would 401 for every user at once.
     */
    @Test
    @DisplayName("GET /api/v1/notifications still works with a JWT and no X-Internal-Token - [MEANT TO PASS]")
    void getNotifications_jwtCallerWithNoInternalToken_returns200WithTheFeed() throws Exception {
        given(notificationRecordRepository.findByUserId(eq(42L), any()))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    /**
     * The outbound half. Both endpoints this service calls — profile-service's preferences lookup
     * and account-service's batch balance fetch — live under {@code /api/v1/internal/} there, so a
     * missing header means the daily summary starts getting 401s from both downstreams the moment
     * they enforce. Nothing here would fail to compile or start; the summaries would simply stop.
     */
    @Test
    @DisplayName("The Feign interceptor attaches X-Internal-Token to outbound requests - [MEANT TO PASS]")
    void applyInterceptor_outboundFeignRequest_attachesTheConfiguredInternalToken() {
        RequestTemplate template = new RequestTemplate();

        internalTokenRequestInterceptor.apply(template);

        assertThat(template.headers().get(InternalTokenFilter.INTERNAL_TOKEN_HEADER))
                .as("every outbound Feign call must carry the internal token")
                .containsExactly(CONFIGURED_TOKEN);
    }

    /**
     * Inbound and outbound read the same property, so this feeds the service's own outbound header
     * straight back into its own inbound filter. If the two ever drifted onto different keys, this
     * service could call out perfectly well while rejecting every service calling in — a failure
     * that looks like a bug in the other service rather than this one.
     */
    @Test
    @DisplayName("The outbound token matches the one the inbound filter accepts - [MEANT TO PASS]")
    void triggerDailySummary_tokenTakenFromTheOutboundInterceptor_returns200() throws Exception {
        RequestTemplate template = new RequestTemplate();
        internalTokenRequestInterceptor.apply(template);
        String outboundToken = template.headers().get(InternalTokenFilter.INTERNAL_TOKEN_HEADER).iterator().next();

        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, outboundToken))
                .andExpect(status().isOk());
    }

    private NotificationRecord buildRecord(Long userId) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.DAILY_SUMMARY);
        record.setChannel(NotificationChannel.EMAIL);
        record.setMessage("Total Aggregate Balance: $10.00");
        record.setStatus(NotificationStatus.SENT);
        return record;
    }
}
