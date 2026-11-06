package team.projectpulse.student;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.team.Team;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentLifecycleServiceTest {
    @Mock StudentRepository studentRepository;
    @Mock SectionRepository sectionRepository;
    @Mock UserInvitationService invitations;
    @Mock PasswordEncoder passwords;
    @Mock UserUtils userUtils;
    @InjectMocks StudentService service;

    private Student active() {
        Student student = new Student("student", "First", "Last", "s@abc.edu", "secret", true, "student");
        when(userUtils.getUserId()).thenReturn(10);
        when(userUtils.hasRole("ROLE_instructor")).thenReturn(true);
        when(studentRepository.findLifecycleStudentForUpdate(1, 10)).thenReturn(Optional.of(student));
        return student;
    }

    @Test void deactivateAssignedStudentAndRepeat() {
        Student student = active();
        Team team = new Team();
        team.addStudent(student);
        assertThat(service.deactivateStudent(1).alreadyDeactivated()).isFalse();
        assertThat(student.isEnabled()).isFalse();
        assertThat(student.getTeam()).isNull();
        assertThat(team.getStudents()).doesNotContain(student);
        assertThat(service.deactivateStudent(1).alreadyDeactivated()).isTrue();
        verify(studentRepository, never()).save(any());
    }

    @Test void alreadyInactiveStudentIsEntirelyUnchanged() {
        Student student = active();
        student.setEnabled(false);
        Team team = new Team();
        team.addStudent(student);
        assertThat(service.deactivateStudent(1).alreadyDeactivated()).isTrue();
        assertThat(student.getTeam()).isSameAs(team);
        assertThat(team.getStudents()).containsExactly(student);
        assertThat(student.getUsername()).isEqualTo("student");
        verify(studentRepository, never()).save(any());
    }

    @Test void deactivateUnassignedStudent() {
        Student student = active();
        assertThat(service.deactivateStudent(1).student()).isSameAs(student);
        assertThat(student.isEnabled()).isFalse();
    }

    @Test void unauthorizedRoleDoesNotLookUpOrChangeStudent() {
        when(userUtils.getUserId()).thenReturn(10);
        assertThatThrownBy(() -> service.deactivateStudent(1)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(studentRepository);
    }

    @Test void outOfScopeOrUnknownStudentDenied() {
        when(userUtils.getUserId()).thenReturn(10);
        when(userUtils.hasRole("ROLE_instructor")).thenReturn(true);
        assertThatThrownBy(() -> service.deactivateStudent(1)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void profileCannotChangeStatusInEitherDirection() {
        for (boolean enabled : new boolean[]{true, false}) {
            Student student = new Student("old", "Old", "Name", "old@abc.edu", "secret", enabled, "student");
            Student update = new Student("new", "New", "Changed", "new@abc.edu", "secret", !enabled, "student");
            when(studentRepository.findById(1)).thenReturn(Optional.of(student));
            assertThatThrownBy(() -> service.updateStudent(1, update)).isInstanceOf(IllegalArgumentException.class);
            assertThat(student.getUsername()).isEqualTo("old");
            assertThat(student.getFirstName()).isEqualTo("Old");
            assertThat(student.getLastName()).isEqualTo("Name");
            assertThat(student.getEmail()).isEqualTo("old@abc.edu");
            assertThat(student.isEnabled()).isEqualTo(enabled);
        }
        verify(studentRepository, never()).save(any());
    }
}
