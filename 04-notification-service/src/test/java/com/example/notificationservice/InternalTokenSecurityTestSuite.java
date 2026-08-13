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

// Covers InternalTokenFilter (inbound) and the Feign RequestInterceptor (outbound) - the two halves
// of the shared X-Internal-Token contract every service in this project implements identically.
//
// Until this filter existed, /api/v1/internal/** was protected by exactly one thing: the k8s ingress
// choosing not to route that prefix. For this service that gap was worse than elsewhere, because the
// endpoint sitting behind it triggers real email sends.
//
// The token is overridden to a value that is NOT the dev default on purpose. With the default in
// place, a typo in the property name would still pass every test here - both sides would quietly
// fall back to their @Value default and agree with each other, and the mistake would only surface in
// a deployed environment where the override is real.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "application.security.internal-token=suite-internal-token",
        // Every @SpringBootTest here whose properties differ gets its own cached context, and each
        // cached context holds a Hikari pool open for the rest of the run - Hikari's minimumIdle
        // defaults to maximumPoolSize, so that is 10 real connections parked per context. Adding this
        // suite pushed the shared local Postgres past max_connections, and the symptom was the LAST
        // suite to start failing with "sorry, too many clients already" - a suite that has nothing to
        // do with this change. Nothing in here touches the database (the repository is mocked); the
        // pool exists only so Flyway and Hibernate can start.
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

    // Mocked so a rejected request can be proven to have done no work at all, rather than only
    // proven to have returned 401 - a 401 returned after the sweep already ran would still have sent
    // the email.
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
    void testInternalTrigger_noToken_rejectedAndDoesNoWork() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH).param("timezone", "America/New_York"))
                .andExpect(status().isUnauthorized());

        // The whole point of the filter: the request must die before the job, and therefore before
        // any provider call, is ever reached.
        verify(dailyBalanceSummaryJob, never()).processUsersForTimezone(anyString());
        verify(dailyBalanceSummaryJob, never()).processDailySummaries();
        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
        verify(notificationRecordRepository, never()).save(any(NotificationRecord.class));
    }

    @Test
    @DisplayName("Internal daily-summary trigger with a wrong X-Internal-Token is rejected - [MEANT TO PASS]")
    void testInternalTrigger_wrongToken_rejected() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .param("timezone", "America/New_York")
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "not-the-right-token"))
                .andExpect(status().isUnauthorized());

        verify(dailyBalanceSummaryJob, never()).processUsersForTimezone(anyString());
        verify(notificationProviderService, never()).dispatchEmail(any(), any(), any());
    }

    // A token that shares a prefix with the real one must fail exactly like one that shares nothing.
    // MessageDigest.isEqual compares the full byte array either way; a String.equals here would
    // return faster on the second case than the first and leak how much was guessed right.
    @Test
    @DisplayName("A token matching only a prefix of the real one is rejected - [MEANT TO PASS]")
    void testInternalTrigger_prefixOfTheRealToken_rejected() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "suite-internal"))
                .andExpect(status().isUnauthorized());

        verify(dailyBalanceSummaryJob, never()).processDailySummaries();
    }

    // The rejection body is part of the cross-service contract: both keys, same text. It also must
    // not tell a caller which header or property would satisfy it - a 401 that names the header is a
    // free hint for anyone probing the prefix.
    @Test
    @DisplayName("The 401 body carries error and message with the same text and names no header or property - [MEANT TO PASS]")
    void testRejectionBody_shapeAndDiscretion() throws Exception {
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
    void testInternalTrigger_correctToken_runsTheJob() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .param("timezone", "America/New_York")
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("Daily summary triggered for timezone America/New_York."));

        verify(dailyBalanceSummaryJob).processUsersForTimezone("America/New_York");
    }

    // The no-timezone form runs the full sweep. Covered separately because it is a different branch
    // of the controller, and a filter that only let one of the two through would be worse than one
    // that let neither.
    @Test
    @DisplayName("Internal trigger with the correct token and no timezone still runs the full sweep - [MEANT TO PASS]")
    void testInternalTrigger_correctToken_noTimezone_runsFullSweep() throws Exception {
        mockMvc.perform(post(INTERNAL_RUN_PATH)
                        .header(InternalTokenFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN))
                .andExpect(status().isOk());

        verify(dailyBalanceSummaryJob).processDailySummaries();
    }

    // The filter is scoped to /api/v1/internal/ and nothing else. A customer hitting the
    // Notifications page carries a JWT and has never heard of the internal token; if the filter
    // leaked outside its prefix, the whole customer-facing endpoint would 401 for everyone.
    @Test
    @DisplayName("GET /api/v1/notifications still works with a JWT and no X-Internal-Token - [MEANT TO PASS]")
    void testCustomerEndpoint_unaffectedByTheFilter() throws Exception {
        given(notificationRecordRepository.findByUserId(eq(42L), any()))
                .willReturn(new PageImpl<>(List.of(buildRecord(42L))));

        mockMvc.perform(get("/api/v1/notifications").with(fullAuthUser(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    // The outbound half. Both endpoints this service calls - profile-service's preferences lookup and
    // account-service's batch balance fetch - live under /api/v1/internal/ there, so a missing header
    // here means the daily summary starts getting 401s from both downstreams the moment they enforce.
    // Nothing in this service would fail to compile or start; the summaries would simply stop.
    @Test
    @DisplayName("The Feign interceptor attaches X-Internal-Token to outbound requests - [MEANT TO PASS]")
    void testFeignInterceptor_attachesTheInternalToken() {
        RequestTemplate template = new RequestTemplate();

        internalTokenRequestInterceptor.apply(template);

        assertThat(template.headers().get(InternalTokenFilter.INTERNAL_TOKEN_HEADER))
                .as("every outbound Feign call must carry the internal token")
                .containsExactly(CONFIGURED_TOKEN);
    }

    // Inbound and outbound read the same property. If they ever drifted onto two different keys, this
    // service could call out perfectly well while rejecting every service calling in - a failure that
    // looks like a problem in the other service rather than this one.
    @Test
    @DisplayName("The outbound token matches the one the inbound filter accepts - [MEANT TO PASS]")
    void testInboundAndOutboundTokensAgree() throws Exception {
        RequestTemplate template = new RequestTemplate();
        internalTokenRequestInterceptor.apply(template);
        String outboundToken = template.headers().get(InternalTokenFilter.INTERNAL_TOKEN_HEADER).iterator().next();

        // Feeding this service's own outbound header straight back into its own inbound filter.
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
