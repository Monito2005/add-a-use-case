package team.projectpulse.security;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveAccountJwtValidatorTest {
    private final UserService users = mock(UserService.class);
    private final ActiveAccountJwtValidator validator = new ActiveAccountJwtValidator(users);

    private Jwt token(Object id) {
        Jwt.Builder builder = Jwt.withTokenValue("test").header("alg", "RS256").subject("student")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (id != null) builder.claim("userId", id);
        return builder.build();
    }

    @Test void observesCurrentStateOnEveryRequest() {
        when(users.isAccountEnabled(1)).thenReturn(true, false);
        Jwt jwt = token(1L);
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
        verify(users, times(2)).isAccountEnabled(1);
    }

    @Test void malformedOrMissingIdNeverLooksUpAnAccount() {
        assertThat(validator.validate(token(null)).hasErrors()).isTrue();
        for (Object id : List.of("", "abc", "1.0", -1, 0, "2147483648", 1.5, true)) {
            assertThat(validator.validate(token(id)).hasErrors()).isTrue();
        }
        verifyNoInteractions(users);
    }

    @Test void missingDisabledAccountAndDatabaseFailureDenyAccess() {
        assertThat(validator.validate(token(1)).hasErrors()).isTrue();
        when(users.isAccountEnabled(1)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThat(validator.validate(token(1)).hasErrors()).isTrue();
    }
}
