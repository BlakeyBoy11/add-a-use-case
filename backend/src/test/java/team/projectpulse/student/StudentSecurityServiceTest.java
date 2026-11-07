package team.projectpulse.student;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.course.Course;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.UserUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * BR-student-lifecycle's course-admin half: the course admin of a student's course may act on her even when not
 * assigned to her course section.
 */
@ExtendWith(MockitoExtension.class)
class StudentSecurityServiceTest {

    @Mock
    StudentRepository studentRepository;

    @Mock
    UserUtils userUtils;

    @InjectMocks
    StudentSecurityService studentSecurityService;


    @BeforeEach
    void setUp() {
        Instructor courseAdmin = new Instructor();
        courseAdmin.setId(1);
        Course course = new Course("COSC 40993 Senior Design", "Senior design");
        course.setCourseAdmin(courseAdmin);
        Section section = new Section();
        section.setCourse(course); // No instructor is assigned to the course section, the course admin included
        Student john = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
        john.setId(4);
        john.setSection(section);

        lenient().when(this.studentRepository.findById(4)).thenReturn(Optional.of(john));
    }

    @Test
    @DisplayName("The course admin of the student's course passes, though not assigned to her course section")
    void courseAdminOfStudentsCoursePasses() {
        given(this.userUtils.getUserId()).willReturn(1);

        assertThat(this.studentSecurityService.isCurrentUserAdminOfStudentCourse(4)).isTrue();
        assertThat(this.studentSecurityService.isCurrentUserInstructorOfStudentSection(4)).isFalse();
    }

    @Test
    @DisplayName("Anyone else, a course admin of another course included, fails")
    void anyoneElseFails() {
        given(this.userUtils.getUserId()).willReturn(3);

        assertThat(this.studentSecurityService.isCurrentUserAdminOfStudentCourse(4)).isFalse();
    }

    @Test
    @DisplayName("An id that names no student fails closed rather than throwing")
    void unknownStudentFailsClosed() {
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.findById(99)).willReturn(Optional.empty());

        assertThat(this.studentSecurityService.isCurrentUserAdminOfStudentCourse(99)).isFalse();
    }

}
