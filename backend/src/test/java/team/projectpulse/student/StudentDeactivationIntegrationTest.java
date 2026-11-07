package team.projectpulse.student;

import org.hamcrest.Matchers;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.activity.ActivityRepository;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.instructor.InstructorRepository;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-STU-deactivate-student through {@code POST /students/{studentId}/deactivation}: the main flow for both kinds of
 * caller BR-student-lifecycle admits, extension 3a for every kind it refuses, and extension 6a. The class-level
 * transaction rolls each deactivation back.
 *
 * <p>Seed facts relied on: John (4) is in course section 2 of course 1, on Team1; Bill is assigned to that course section
 * and is no course admin; Bingyang is course 1's admin; Tim is course 3's admin, assigned only to its course section,
 * where Jana is enrolled.
 */
@DisplayName("Integration tests for deactivating a student (UC-STU-deactivate-student)")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    static final int JOHN_ID = 4;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StudentRepository studentRepository;

    @Autowired
    InstructorRepository instructorRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ActivityRepository activityRepository;

    @Autowired
    PeerEvaluationRepository peerEvaluationRepository;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String instructorBillToken;

    String adminTimToken;

    String studentJohnToken;

    String studentEricToken;


    @BeforeEach
    void setUp() throws Exception {
        this.instructorBillToken = login("b.gates@abc.edu");
        this.adminTimToken = login("t.cook@abc.edu");
        this.studentJohnToken = login("j.smith@abc.edu");
        this.studentEricToken = login("e.hudson@abc.edu");
    }

    private String login(String email) throws Exception {
        String body = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456")))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + new JSONObject(body).getJSONObject("data").getString("token");
    }

    private ResultActions deactivate(Object studentId, String token) throws Exception {
        return this.mockMvc.perform(post(this.baseUrl + "/students/" + studentId + "/deactivation").header(HttpHeaders.AUTHORIZATION, token));
    }

    private Integer idOf(String email) {
        return this.userRepository.findByEmail(email).orElseThrow().getId();
    }

    private boolean isEnabled(Integer studentId) {
        return this.studentRepository.findById(studentId).orElseThrow().isEnabled();
    }

    // Main success scenario

    @Test
    @DisplayName("Main: an instructor assigned to the student's course section deactivates her; her account and work are kept")
    void assignedInstructorDeactivatesStudent() throws Exception {
        long activitiesBefore = this.activityRepository.count();
        long evaluationsBefore = this.peerEvaluationRepository.count();

        deactivate(JOHN_ID, this.instructorBillToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.message").value("Deactivate student successfully"))
                .andExpect(jsonPath("$.data.id").value(JOHN_ID))
                .andExpect(jsonPath("$.data.enabled").value(false));

        this.mockMvc.perform(get(this.baseUrl + "/students/" + JOHN_ID).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.teamName").value("Team1"));
        assertThat(this.activityRepository.count()).isEqualTo(activitiesBefore);
        assertThat(this.peerEvaluationRepository.count()).isEqualTo(evaluationsBefore);
    }

    @Test
    @DisplayName("Main: the course admin who owns the course deactivates a student of a course section she is not assigned to")
    void courseAdminNotAssignedToCourseSectionDeactivatesStudent() throws Exception {
        // Unassign Tim from course 3's only course section, so only his course-admin role can admit him
        Instructor tim = this.instructorRepository.findById(idOf("t.cook@abc.edu")).orElseThrow();
        Integer janaId = idOf("j.norton@abc.edu");
        this.studentRepository.findById(janaId).orElseThrow().getSection().removeInstructor(tim);
        this.instructorRepository.saveAndFlush(tim);

        deactivate(janaId, this.adminTimToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(isEnabled(janaId)).isFalse();
    }

    // 3a: a caller who is neither the course admin of the student's course nor an instructor of her course section

    @Test
    @DisplayName("3a: the student herself is refused")
    void studentHerselfIsRefused() throws Exception {
        assertRefused(deactivate(JOHN_ID, this.studentJohnToken));
        assertThat(isEnabled(JOHN_ID)).isTrue();
    }

    @Test
    @DisplayName("3a: another student is refused")
    void anotherStudentIsRefused() throws Exception {
        assertRefused(deactivate(JOHN_ID, this.studentEricToken));
        assertThat(isEnabled(JOHN_ID)).isTrue();
    }

    @Test
    @DisplayName("3a: an instructor not assigned to her course section is refused")
    void unassignedInstructorIsRefused() throws Exception {
        Integer janaId = idOf("j.norton@abc.edu"); // Course 3; Bill teaches only course 1 and administers no course
        assertRefused(deactivate(janaId, this.instructorBillToken));
        assertThat(isEnabled(janaId)).isTrue();
    }

    @Test
    @DisplayName("3a: a course admin of another course is refused")
    void courseAdminOfAnotherCourseIsRefused() throws Exception {
        assertRefused(deactivate(JOHN_ID, this.adminTimToken)); // Tim administers course 3; John is in course 1
        assertThat(isEnabled(JOHN_ID)).isTrue();
    }

    @Test
    @DisplayName("3a: an id that names no student is refused the same way, revealing nothing")
    void idNamingNoStudentIsRefused() throws Exception {
        assertRefused(deactivate(99999, this.instructorBillToken));
        assertRefused(deactivate(idOf("b.gates@abc.edu"), this.instructorBillToken)); // An instructor's id is no student's
        assertRefused(deactivate("not-a-number", this.instructorBillToken));
    }

    private static void assertRefused(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN))
                .andExpect(jsonPath("$.message").value("No permission."));
    }

    // 6a

    @Test
    @DisplayName("6a: deactivating an already-deactivated student returns 409 and changes nothing")
    void alreadyDeactivatedStudentIsAConflict() throws Exception {
        deactivate(JOHN_ID, this.instructorBillToken).andExpect(status().isOk());

        deactivate(JOHN_ID, this.instructorBillToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.CONFLICT))
                .andExpect(jsonPath("$.message").value("This student is already deactivated."));
        assertThat(isEnabled(JOHN_ID)).isFalse();
    }

    // Consequences named in the use case's Associated Information

    @Test
    @DisplayName("Team kept: she stays on her team, shown as deactivated, and no longer owes a weekly activity report")
    void deactivatedStudentStaysOnTeamAndOwesNothing() throws Exception {
        // Seed: Jerry, on Team1, is the one Team1 student who owes the week's weekly activity report
        Integer jerryId = idOf("j.moon@abc.edu");
        this.mockMvc.perform(get(this.baseUrl + "/sections/2/submission-status").param("item", "WEEKLY_ACTIVITY_REPORT").header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(jsonPath("$.data.teams[*].students[*].id", Matchers.hasItem(jerryId)));

        deactivate(jerryId, this.instructorBillToken).andExpect(status().isOk());

        this.mockMvc.perform(get(this.baseUrl + "/students/teams/1").header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data[?(@.id == " + jerryId + ")].enabled", Matchers.contains(false)));
        this.mockMvc.perform(get(this.baseUrl + "/sections/2/submission-status").param("item", "WEEKLY_ACTIVITY_REPORT").header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.teams[*].students[*].id", Matchers.not(Matchers.hasItem(jerryId))));
    }

    @Test
    @DisplayName("Open session: after deactivation through the route, her own unexpired token is refused")
    void deactivatedStudentsTokenIsRefused() throws Exception {
        deactivate(JOHN_ID, this.instructorBillToken).andExpect(status().isOk());

        this.mockMvc.perform(get(this.baseUrl + "/students/" + JOHN_ID).header(HttpHeaders.AUTHORIZATION, this.studentJohnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

}
