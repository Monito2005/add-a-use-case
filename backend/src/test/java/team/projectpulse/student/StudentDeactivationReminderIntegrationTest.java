package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.system.EmailService;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T11 / BR-submission-owed: real eligibility query and reminder path, with only SMTP replaced. */
class StudentDeactivationReminderIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @MockitoSpyBean EmailService emails;

    @Test void inactiveStudentIsNotEmailedAfterDeactivationAndActiveControlIs() throws Exception {
        // Both named active students owe a WAR for the previous active week.
        em.createQuery("delete from Activity a where a.week = '2023-W32' and a.student.id in (4, 5)").executeUpdate();
        em.flush(); em.clear();
        String login = mvc.perform(post("/api/v1/users/login").with(httpBasic("b.gates@abc.edu", "123456")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = "Bearer " + new JSONObject(login).getJSONObject("data").getString("token");
        mvc.perform(get("/api/v1/sections/2/submission-status").param("item", "WEEKLY_ACTIVITY_REPORT")
                .param("week", "2023-W32").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.teams[0].students[*].id", hasItems(4, 5)));
        mvc.perform(post("/api/v1/students/4/deactivate").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        em.flush(); em.clear();
        // Never contact an external mail server; eligibility and reminder composition remain real.
        doNothing().when(emails).sendReminderEmail(anyString(), anyString(), anyString());
        mvc.perform(post("/api/v1/sections/2/reminders").header("Authorization", token).contentType("application/json")
                .content("{\"item\":\"WEEKLY_ACTIVITY_REPORT\",\"week\":\"2023-W32\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true));
        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        verify(emails, atLeastOnce()).sendReminderEmail(recipients.capture(), anyString(), anyString());
        assertThat(recipients.getAllValues()).doesNotContain("j.smith@abc.edu").contains("e.hudson@abc.edu");
    }
}
