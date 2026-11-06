package team.projectpulse.security.authorizationmanagers;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import team.projectpulse.student.StudentSecurityService;

@Component
public class StudentLifecycleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final StudentSecurityService studentSecurityService;

    public StudentLifecycleAuthorizationManager(StudentSecurityService studentSecurityService) {
        this.studentSecurityService = studentSecurityService;
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Integer studentId = PathVariables.readId(context, "studentId");
        return new AuthorizationDecision(studentId != null && this.studentSecurityService.canManageStudentLifecycle(studentId));
    }
}
