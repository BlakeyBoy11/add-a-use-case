package team.projectpulse.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserRepository;

/**
 * Refuses a verified JWT whose account is no longer active, so that deactivating an account takes effect on its
 * next request rather than when its token expires (FR-SEC-active-account).
 *
 * <p>Runs on every Bearer-authenticated request, after the token's signature and expiry are verified. It reads the
 * account named by the {@code userId} claim and throws {@link DisabledException} when that account is deactivated,
 * no longer exists, or the claim is missing; the bearer-token entry point hands the exception to
 * {@code ExceptionHandlerAdvice}, which answers {@code 401}. Otherwise it delegates to the authority mapping of
 * {@code SecurityConfiguration#jwtAuthenticationConverter()} unchanged.
 *
 * <p>The flag is read from the database on each request and never cached, because a cache would reopen the window
 * this check exists to close.
 */
@Component
public class ActiveAccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    private final UserRepository userRepository;


    public ActiveAccountJwtAuthenticationConverter(JwtAuthenticationConverter jwtAuthenticationConverter, UserRepository userRepository) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Integer userId = readUserId(jwt);
        if (userId == null || !this.userRepository.existsByIdAndEnabledTrue(userId)) {
            throw new DisabledException("The account this token was issued to is deactivated or no longer exists.");
        }
        return this.jwtAuthenticationConverter.convert(jwt);
    }

    /**
     * @return the {@code userId} claim as an id, or {@code null} if the token carries none that reads as one
     */
    private static Integer readUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim instanceof Number number) {
            return number.intValue();
        }
        if (claim instanceof String string) {
            try {
                return Integer.valueOf(string);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

}
