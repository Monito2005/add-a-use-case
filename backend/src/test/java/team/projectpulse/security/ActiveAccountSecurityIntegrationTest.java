package team.projectpulse.security;

import java.time.Instant;
import java.security.KeyPairGenerator;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.user.UserRepository;
import team.projectpulse.user.UserService;
import team.projectpulse.student.Student;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real signed tokens pass through the decoder; jwt() authentication helpers would bypass this contract. */
class ActiveAccountSecurityIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @MockitoSpyBean UserService accounts;
    private static final String API = "/api/v1";

    private String login(String email) throws Exception {
        var response = mvc.perform(post(API + "/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token");
    }
    private String signed(Object id, Instant expiry) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("test").issuedAt(expiry.minusSeconds(7200))
                .expiresAt(expiry).claim("authorities", "ROLE_student");
        if (id != null) claims.claim("userId", id);
        return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }
    private void denied(String token) throws Exception {
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
    }
    @Test void earlierTokenAndFreshLoginDeniedWhenStudentDisabled() throws Exception {
        String token = login("j.smith@abc.edu");
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        Student john = (Student) users.findByEmail("j.smith@abc.edu").orElseThrow();
        john.setEnabled(false); em.flush(); em.clear();
        denied(token);
        mvc.perform(put(API + "/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/users/login").with(httpBasic("j.smith@abc.edu", "123456"))).andExpect(status().isUnauthorized());
        String control = login("e.hudson@abc.edu");
        mvc.perform(get(API + "/students/5").header(HttpHeaders.AUTHORIZATION, "Bearer " + control)).andExpect(status().isOk());
        assertThat(users.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
    }
    @Test void disabledInstructorTokenDenied() throws Exception {
        String token = login("b.gates@abc.edu");
        users.findByEmail("b.gates@abc.edu").orElseThrow().setEnabled(false); em.flush(); em.clear();
        denied(token);
    }
    @Test void missingMalformedMissingAccountAndExpiredClaimsDenied() throws Exception {
        for (Object id : new Object[]{null, "invalid", "2147483648", -1, 999999}) denied(signed(id, Instant.now().plusSeconds(300)));
        denied(signed(4, Instant.now().minusSeconds(300)));
    }
    @Test void invalidSignatureDenied() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA"); pair.initialize(2048);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().subject("test")
                .claim("userId", 4).expirationTime(java.util.Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(pair.generateKeyPair().getPrivate()));
        denied(jwt.serialize());
    }
    @Test void databaseFailureCannotReachProfileWrite() throws Exception {
        String token = login("j.smith@abc.edu");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated database outage")).when(accounts).isAccountEnabled(4);
        try {
            mvc.perform(put(API + "/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType("application/json").content("{\"username\":\"changed\",\"firstName\":\"Changed\",\"lastName\":\"Smith\",\"email\":\"changed@abc.edu\",\"enabled\":true}"))
                    .andExpect(status().isUnauthorized());
            assertThat(users.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
        } finally { doCallRealMethod().when(accounts).isAccountEnabled(4); }
    }
    @Test void owningAdminWithoutInstructorAssignmentCanReadStudentAndHistoricalReports() throws Exception {
        String token = login("b.wei@abc.edu");
        Student john = (Student) users.findById(4).orElseThrow();
        john.getSection().getInstructors().removeIf(i -> i.getId().equals(1)); em.flush();
        for (String path : new String[]{"/students/4", "/evaluations/students/4/week/2023-W31/details", "/evaluations/sections/2/week/2023-W31", "/evaluations/students/4?startWeek=2023-W31&endWeek=2023-W32"}) {
            mvc.perform(get(API + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        }
    }
    @Test void lifecycleRouteDeniesSelfUnrelatedAndUnknownOrMalformedIds() throws Exception {
        String student = login("j.smith@abc.edu"), unrelated = login("t.cook@abc.edu"), admin = login("b.wei@abc.edu");
        for (String token : new String[]{student, unrelated}) {
            mvc.perform(post(API + "/students/4/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isForbidden());
        }
        for (String id : new String[]{"999999", "invalid", "2147483648"}) {
            mvc.perform(post(API + "/students/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isForbidden());
        }
        mvc.perform(post(API + "/students/4/deactivate")).andExpect(status().isUnauthorized());
        assertThat(users.findById(4).orElseThrow().isEnabled()).isTrue();
    }
}
