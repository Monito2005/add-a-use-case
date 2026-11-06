package team.projectpulse.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserService;

/** Checks current account state; never trusts login-time enabled status. */
@Component
public class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final UserService userService;

    public ActiveAccountJwtValidator(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Object claim = token.getClaims().get("userId");
            if (claim != null && claim.toString().matches("[1-9][0-9]*")
                    && this.userService.isAccountEnabled(Integer.valueOf(claim.toString()))) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException ex) {
            // Malformed identifiers and lookup failures cannot authenticate a caller.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Account is unavailable or inactive", null));
    }
}
