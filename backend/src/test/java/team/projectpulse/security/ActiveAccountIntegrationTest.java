package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.activity.ActivityRepository;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEC-active-account: a deactivated account is refused on its next request, including one carrying a token
 * issued before the deactivation, and cannot log in again. Accounts are deactivated through the repository here;
 * the deactivation route is UC-STU-deactivate-student's and is tested with it. The class-level transaction rolls
 * each change back.
 */
@DisplayName("Integration tests for the per-request active-account check (FR-SEC-active-account)")
class ActiveAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ActivityRepository activityRepository;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String studentJohnToken;

    String instructorBillToken;


    @BeforeEach
    void setUp() throws Exception {
        this.studentJohnToken = login("j.smith@abc.edu");
        this.instructorBillToken = login("b.gates@abc.edu");
    }

    private String login(String email) throws Exception {
        String body = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456")))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + new JSONObject(body).getJSONObject("data").getString("token");
    }

    private void deactivate(String email) {
        PeerEvaluationUser user = this.userRepository.findByEmail(email).orElseThrow();
        user.setEnabled(false);
        this.userRepository.saveAndFlush(user);
    }

    private String anActivityPayload() throws Exception {
        return this.jsonMapper.writeValueAsString(Map.of(
                "week", "2023-W31",
                "category", "DEVELOPMENT",
                "activity", "Integrate Payment Gateway",
                "description", "Integrate Stripe payment gateway into the application",
                "plannedHours", 8.0,
                "actualHours", 7.5,
                "status", "COMPLETED"));
    }

    @Test
    @DisplayName("An active student's token is honored")
    void activeStudentTokenIsHonored() throws Exception {
        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, this.studentJohnToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true));
    }

    @Test
    @DisplayName("A token the student got before she was deactivated is refused on her next read")
    void deactivatedStudentsOpenSessionIsRefusedOnRead() throws Exception {
        deactivate("j.smith@abc.edu");

        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, this.studentJohnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));
    }

    @Test
    @DisplayName("A token the student got before she was deactivated cannot submit a weekly activity report")
    void deactivatedStudentsOpenSessionCannotSubmitActivity() throws Exception {
        long activitiesBefore = this.activityRepository.count();
        deactivate("j.smith@abc.edu");

        this.mockMvc.perform(post(this.baseUrl + "/activities").contentType(MediaType.APPLICATION_JSON).content(anActivityPayload()).header(HttpHeaders.AUTHORIZATION, this.studentJohnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));

        assertThat(this.activityRepository.count()).isEqualTo(activitiesBefore);
    }

    @Test
    @DisplayName("A deactivated student cannot log in")
    void deactivatedStudentCannotLogIn() throws Exception {
        deactivate("j.smith@abc.edu");

        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("A deactivated instructor's unexpired token is refused too")
    void deactivatedInstructorsOpenSessionIsRefused() throws Exception {
        deactivate("b.gates@abc.edu");

        this.mockMvc.perform(get(this.baseUrl + "/students/4").header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));
    }

    @Test
    @DisplayName("A token whose account no longer exists is refused")
    void deletedAccountsTokenIsRefused() throws Exception {
        PeerEvaluationUser tracy = this.userRepository.findByEmail("t.nicholson@abc.edu").orElseThrow();
        String tracyToken = login("t.nicholson@abc.edu");
        this.userRepository.delete(tracy);
        this.userRepository.flush();

        this.mockMvc.perform(get(this.baseUrl + "/students/" + tracy.getId()).header(HttpHeaders.AUTHORIZATION, tracyToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

}
