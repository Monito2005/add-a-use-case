package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import org.json.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.notification.SubmissionStatusService;
import team.projectpulse.notification.SubmittableItem;
import team.projectpulse.user.UserRepository;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student: HTTP/persistence oracles T02–T08, T10–T14. */
class StudentDeactivationApiIntegrationTest extends AbstractIntegrationTest {
    private static final String API = "/api/v1";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StudentRepository students;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired SubmissionStatusService submissionStatus;
    @Autowired EntityManager em;
    @Autowired JsonMapper json;

    private String login(String email) throws Exception {
        return "Bearer " + body(mvc.perform(post(API + "/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn()).getJSONObject("data").getString("token");
    }
    private JSONObject body(MvcResult result) throws Exception { return new JSONObject(result.getResponse().getContentAsString()); }
    private JSONObject deactivate(int id, String token) throws Exception {
        return body(mvc.perform(post(API + "/students/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true)).andReturn());
    }
    private JSONObject read(String path, String token) throws Exception {
        return body(mvc.perform(get(API + path).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk()).andReturn());
    }
    private JsonNode reportRow(String week, String token) throws Exception {
        var rows = read("/evaluations/sections/2/week/" + week, token).getJSONObject("data").getJSONArray("peerEvaluationAverages");
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.getInt("studentId") == 4) { row.remove("teamName"); return json.readTree(row.toString()); }
        }
        return json.createObjectNode();
    }
    @Test void deactivateAssignedStudentAndRepeat() throws Exception {
        String admin = login("b.wei@abc.edu");
        Student john = students.findById(4).orElseThrow();
        String username = john.getUsername(), email = john.getEmail();
        Integer team = john.getTeam().getTeamId(), section = john.getSection().getSectionId();
        JSONObject first = deactivate(4, admin);
        assertThat(first.getString("message")).isEqualTo("Student deactivated successfully");
        assertThat(first.getJSONObject("data").getBoolean("enabled")).isFalse();
        assertThat(first.getJSONObject("data").isNull("teamId")).isTrue();
        em.flush(); em.clear();
        john = students.findById(4).orElseThrow();
        assertThat(john.isEnabled()).isFalse(); assertThat(john.getTeam()).isNull();
        assertThat(john.getSection().getSectionId()).isEqualTo(section);
        assertThat(john.getUsername()).isEqualTo(username); assertThat(john.getEmail()).isEqualTo(email);
        assertThat(students.findByTeamTeamId(team)).extracting(Student::getId).doesNotContain(4);
        JSONObject repeat = deactivate(4, admin);
        assertThat(repeat.getString("message")).isEqualTo("Student is already deactivated");
        assertThat(json.readTree(repeat.getJSONObject("data").toString())).isEqualTo(json.readTree(first.getJSONObject("data").toString()));
    }
    @Test void assignedInstructorCanDeactivateUnassignedStudent() throws Exception {
        Student john = students.findById(4).orElseThrow(); john.getTeam().removeStudent(john); em.flush();
        assertThat(deactivate(4, login("b.gates@abc.edu")).getJSONObject("data").getBoolean("enabled")).isFalse();
    }
    @Test void owningAdminWithoutInstructorAssignmentCanDeactivate() throws Exception {
        Student john = students.findById(4).orElseThrow();
        john.getSection().getInstructors().removeIf(i -> i.getId().equals(1)); em.flush();
        assertThat(deactivate(4, login("b.wei@abc.edu")).getJSONObject("data").getBoolean("enabled")).isFalse();
    }
    @Test void unauthorizedCallersAndMalformedIdsLeaveStateUnchanged() throws Exception {
        Student john = students.findById(4).orElseThrow();
        john.getSection().getInstructors().removeIf(i -> i.getId().equals(2)); em.flush();
        for (String email : List.of("b.gates@abc.edu", "t.cook@abc.edu", "j.smith@abc.edu", "e.hudson@abc.edu")) {
            mvc.perform(post(API + "/students/4/deactivate").header(HttpHeaders.AUTHORIZATION, login(email)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.flag").value(false));
        }
        String admin = login("b.wei@abc.edu");
        for (String id : List.of("999999", "invalid", "2147483648")) {
            mvc.perform(post(API + "/students/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isForbidden());
        }
        mvc.perform(post(API + "/students/4/deactivate")).andExpect(status().isUnauthorized());
        em.clear(); assertThat(students.findById(4).orElseThrow().isEnabled()).isTrue();
        assertThat(students.findById(4).orElseThrow().getTeam()).isNotNull();
    }
    @Test void historicalReportAfterDeactivation() throws Exception {
        String admin = login("b.wei@abc.edu"), instructor = login("b.gates@abc.edu"), unrelated = login("t.cook@abc.edu");
        Student john = students.findById(4).orElseThrow();
        john.getSection().getInstructors().removeIf(i -> i.getId().equals(1)); em.flush();
        Map<String,JsonNode> details = new HashMap<>(); Map<String,JsonNode> averages = new HashMap<>();
        for (String week : List.of("2023-W31", "2023-W32")) {
            details.put(week, json.readTree(read("/evaluations/students/4/week/" + week + "/details", instructor).getJSONArray("data").toString()));
            assertThat(details.get(week).size()).isPositive();
            averages.put(week, reportRow(week, instructor));
            assertThat(averages.get(week).size()).isPositive();
        }
        deactivate(4, admin); em.flush(); em.clear();
        for (String token : List.of(admin, instructor)) {
            for (String week : details.keySet()) {
                assertThat(json.readTree(read("/evaluations/students/4/week/"+week+"/details", token).getJSONArray("data").toString())).isEqualTo(details.get(week));
                assertThat(reportRow(week, token)).isEqualTo(averages.get(week));
                assertThat(read("/evaluations/students/4?startWeek="+week+"&endWeek="+week, token).getJSONArray("data").length()).isEqualTo(1);
            }
            assertThat(reportRow("2023-W40", token).size()).isZero();
        }
        for (String path : List.of("/evaluations/students/4/week/2023-W31/details", "/evaluations/sections/2/week/2023-W31", "/evaluations/students/4?startWeek=2023-W31&endWeek=2023-W32")) {
            mvc.perform(get(API+path).header(HttpHeaders.AUTHORIZATION, unrelated)).andExpect(status().isForbidden());
        }
    }
    @Test void studentPrivacyStillHolds() throws Exception {
        String self = login("j.smith@abc.edu"), teammate = login("e.hudson@abc.edu");
        mvc.perform(get(API + "/evaluations/students/4/week/2023-W31").header(HttpHeaders.AUTHORIZATION, self))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateComments").doesNotExist());
        mvc.perform(get(API + "/evaluations/students/4?startWeek=2023-W31&endWeek=2023-W32").header(HttpHeaders.AUTHORIZATION, self))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].privateComments").doesNotExist());
        mvc.perform(get(API + "/evaluations/students/4?startWeek=2023-W31&endWeek=2023-W32").header(HttpHeaders.AUTHORIZATION, teammate))
                .andExpect(status().isForbidden());
        for (String token : List.of(self, teammate)) {
            mvc.perform(get(API + "/evaluations/students/4/week/2023-W31/details").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
        }
        mvc.perform(get(API + "/evaluations/students/4/week/2023-W31").header(HttpHeaders.AUTHORIZATION, teammate)).andExpect(status().isForbidden());
    }
    @Test void existingTokenDeniedAfterDeactivation() throws Exception {
        String student = login("j.smith@abc.edu"), admin = login("b.wei@abc.edu"), control = login("e.hudson@abc.edu");
        read("/students/4", student); deactivate(4, admin); em.flush(); em.clear();
        mvc.perform(get(API+"/students/4").header(HttpHeaders.AUTHORIZATION, student)).andExpect(status().isUnauthorized());
        mvc.perform(put(API+"/students/4").header(HttpHeaders.AUTHORIZATION, student).contentType("application/json")
                .content(profile(true, "Changed"))).andExpect(status().isUnauthorized());
        mvc.perform(post(API+"/users/login").with(httpBasic("j.smith@abc.edu", "123456"))).andExpect(status().isUnauthorized());
        read("/students/5", control);
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
    }
    private String profile(boolean enabled, String firstName) throws Exception {
        return new JSONObject().put("username","j.smith@abc.edu").put("firstName",firstName).put("lastName","Smith")
                .put("email","j.smith@abc.edu").put("enabled",enabled).toString();
    }
    @Test void profileUpdateCannotChangeStatus() throws Exception {
        String admin = login("b.wei@abc.edu");
        mvc.perform(put(API+"/students/4").header(HttpHeaders.AUTHORIZATION,admin).contentType("application/json").content(profile(false,"Changed")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Use the dedicated lifecycle action to change student status."));
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
        deactivate(4,admin);
        mvc.perform(put(API+"/students/4").header(HttpHeaders.AUTHORIZATION,admin).contentType("application/json").content(profile(true,"Changed"))).andExpect(status().isBadRequest());
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
        mvc.perform(put(API+"/students/4").header(HttpHeaders.AUTHORIZATION,admin).contentType("application/json").content(profile(false,"Updated")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.firstName").value("Updated")).andExpect(jsonPath("$.data.enabled").value(false));
    }
    private String evaluation(int evaluateeId) throws Exception {
        JSONArray ratings = new JSONArray();
        for (int id=1; id<=6; id++) ratings.put(new JSONObject().put("criterionId",id).put("actualScore",5.0));
        return new JSONObject().put("week","2023-W32").put("evaluatorId",5).put("evaluateeId",evaluateeId).put("ratings",ratings)
                .put("publicComment","Preserve work").put("privateComment","Private").toString();
    }
    @Test void cannotEvaluateInactiveStudentForPreviousOpenWeek() throws Exception {
        String eric = login("e.hudson@abc.edu");
        evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32",5,6).ifPresent(evaluations::delete); em.flush();
        deactivate(4,login("b.wei@abc.edu")); long count = evaluations.count();
        mvc.perform(post(API+"/evaluations").header(HttpHeaders.AUTHORIZATION,eric).contentType("application/json").content(evaluation(4)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Cannot evaluate an inactive student."));
        assertThat(evaluations.count()).isEqualTo(count);
        mvc.perform(post(API+"/evaluations").header(HttpHeaders.AUTHORIZATION,eric).contentType("application/json").content(evaluation(6)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true));
        assertThat(evaluations.count()).isEqualTo(count+1);
    }
    private Map<Integer, JsonNode> activitySnapshot(String token, String query) throws Exception {
        String content = body(mvc.perform(post(API+"/activities/search").header(HttpHeaders.AUTHORIZATION,token)
                .contentType("application/json").content(query)).andExpect(status().isOk()).andReturn())
                .getJSONObject("data").getJSONArray("content").toString();
        Map<Integer, JsonNode> records = new HashMap<>();
        for (JsonNode record : json.readTree(content)) records.put(record.get("activityId").asInt(), record);
        return records;
    }
    @Test void retainedActivitiesRemainVisibleThroughHistoricalQuery() throws Exception {
        String admin = login("b.wei@abc.edu"), instructor = login("b.gates@abc.edu");
        String query = "{\"studentId\":\"4\",\"week\":\"2023-W31\",\"teamId\":\"1\"}";
        Map<Integer, JsonNode> before = activitySnapshot(instructor, query);
        assertThat(before).isNotEmpty();
        deactivate(4,admin); em.flush(); em.clear();
        for (String token : List.of(admin,instructor)) {
            assertThat(activitySnapshot(token, query)).isEqualTo(before);
        }
    }
    @Test void deactivationRemovesOwedItemsAndInactiveEvaluateeObligation() throws Exception {
        evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W31",5,4).ifPresent(evaluations::delete); em.flush();
        assertThat(submissionStatus.findOwing(2,SubmittableItem.PEER_EVALUATION,"2023-W31")).extracting(Student::getId).contains(5);
        deactivate(4,login("b.wei@abc.edu")); em.flush(); em.clear();
        assertThat(submissionStatus.findOwing(2,SubmittableItem.PEER_EVALUATION,"2023-W31")).extracting(Student::getId).doesNotContain(4,5);
        assertThat(submissionStatus.findOwing(2,SubmittableItem.WEEKLY_ACTIVITY_REPORT,"2023-W32")).extracting(Student::getId).doesNotContain(4);
    }
}
