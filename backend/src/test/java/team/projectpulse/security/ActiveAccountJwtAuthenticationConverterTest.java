package team.projectpulse.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import team.projectpulse.user.UserRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FR-SEC-active-account: a verified token is honored only while the account it names is active.
 */
@ExtendWith(MockitoExtension.class)
class ActiveAccountJwtAuthenticationConverterTest {

    @Mock
    UserRepository userRepository;

    ActiveAccountJwtAuthenticationConverter converter;


    @BeforeEach
    void setUp() {
        // The same authority mapping SecurityConfiguration#jwtAuthenticationConverter() configures
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("authorities");
        authoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        this.converter = new ActiveAccountJwtAuthenticationConverter(delegate, this.userRepository);
    }

    private static Jwt.Builder aToken() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("j.smith@abc.edu")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("authorities", "ROLE_student");
    }

    @Test
    @DisplayName("An active account yields an authentication carrying the token's authorities")
    void activeAccountIsAuthenticated() {
        given(this.userRepository.existsByIdAndEnabledTrue(4)).willReturn(true);

        AbstractAuthenticationToken authentication = this.converter.convert(aToken().claim("userId", 4L).build());

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("j.smith@abc.edu");
        // Spring Security also adds its own FACTOR_BEARER authority; the token's roles must come through unchanged
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_student");
    }

    @Test
    @DisplayName("A token with no usable userId claim is refused without a lookup")
    void tokenWithoutUserIdIsRefused() {
        assertThatThrownBy(() -> this.converter.convert(aToken().build()))
                .isInstanceOf(DisabledException.class);
        assertThatThrownBy(() -> this.converter.convert(aToken().claim("userId", "not-a-number").build()))
                .isInstanceOf(DisabledException.class);
        verifyNoInteractions(this.userRepository);
    }

}
