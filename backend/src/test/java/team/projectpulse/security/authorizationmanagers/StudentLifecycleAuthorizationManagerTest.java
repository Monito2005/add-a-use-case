package team.projectpulse.security.authorizationmanagers;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import team.projectpulse.student.StudentSecurityService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentLifecycleAuthorizationManagerTest {
    private final StudentSecurityService students = mock(StudentSecurityService.class);
    private final StudentLifecycleAuthorizationManager manager = new StudentLifecycleAuthorizationManager(students);

    @Test void missingMalformedAndOverflowIdDenyWithoutLookup() {
        for (String id : new String[]{"none", "2147483648"}) {
            assertThat(manager.authorize(() -> null, context(Map.of("studentId", id))).isGranted()).isFalse();
        }
        assertThat(manager.authorize(() -> null, context(Map.of())).isGranted()).isFalse();
        verifyNoInteractions(students);
    }

    @Test void validIdRequiresLifecycleRelationship() {
        when(students.canManageStudentLifecycle(1)).thenReturn(false, true);
        assertThat(manager.authorize(() -> null, context(Map.of("studentId", "1"))).isGranted()).isFalse();
        assertThat(manager.authorize(() -> null, context(Map.of("studentId", "1"))).isGranted()).isTrue();
    }

    private RequestAuthorizationContext context(Map<String, String> variables) {
        return new RequestAuthorizationContext(new MockHttpServletRequest(), variables);
    }
}
