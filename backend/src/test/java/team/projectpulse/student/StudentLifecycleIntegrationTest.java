package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.evaluation.EvaluationService;
import team.projectpulse.activity.ActivityRepository;
import team.projectpulse.user.UserRepository;
import team.projectpulse.user.UserService;
import static org.assertj.core.api.Assertions.*;

/** Trunk persistence oracles for UC-STU-deactivate-student; HTTP wiring follows in a leaf commit. */
class StudentLifecycleIntegrationTest extends AbstractIntegrationTest {
    @Autowired StudentService service;
    @Autowired StudentSecurityService security;
    @Autowired StudentRepository students;
    @Autowired UserRepository users;
    @Autowired UserService accounts;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired EvaluationService reports;
    @Autowired ActivityRepository activities;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    private Student john() { return (Student) users.findByEmail("j.smith@abc.edu").orElseThrow(); }
    private void caller(String email, String role) {
        Integer id = users.findByEmail(email).orElseThrow().getId();
        Jwt jwt = Jwt.withTokenValue("service-test").header("alg", "RS256").subject(email).claim("userId", id).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role))));
    }
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    @Test void deactivateAssignedStudentPreservesEnrollmentAndRecords() {
        Student student = john();
        Integer id = student.getId(), section = student.getSection().getSectionId(), team = student.getTeam().getTeamId();
        var beforeEvaluations = evaluations.findByWeekAndEvaluateeId("2023-W31", id).stream().map(e -> e.getPeerEvaluationId()).toList();
        long activityCount = activities.count(), evaluationCount = evaluations.count();
        caller("b.wei@abc.edu", "ROLE_admin");
        assertThat(security.canManageStudentLifecycle(id)).isTrue();
        assertThat(service.deactivateStudent(id).alreadyDeactivated()).isFalse();
        em.flush(); em.clear();
        Student stored = students.findById(id).orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getTeam()).isNull();
        assertThat(stored.getSection().getSectionId()).isEqualTo(section);
        assertThat(stored.getEmail()).isEqualTo("j.smith@abc.edu");
        assertThat(students.findByTeamTeamId(team)).extracting(Student::getId).doesNotContain(id);
        assertThat(activities.count()).isEqualTo(activityCount);
        assertThat(evaluations.count()).isEqualTo(evaluationCount);
        assertThat(evaluations.findByWeekAndEvaluateeId("2023-W31", id)).extracting(e -> e.getPeerEvaluationId()).containsExactlyElementsOf(beforeEvaluations);
        assertThat(accounts.isAccountEnabled(id)).isFalse();
        assertThat(service.deactivateStudent(id).alreadyDeactivated()).isTrue();
    }

    @Test void assignedInstructorCanDeactivateUnassignedStudent() {
        Student student = john();
        student.getTeam().removeStudent(student);
        caller("b.gates@abc.edu", "ROLE_instructor");
        assertThat(security.canManageStudentLifecycle(student.getId())).isTrue();
        service.deactivateStudent(student.getId());
        em.flush(); em.clear();
        assertThat(students.findById(student.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test void owningAdminNeedsNoSectionInstructorAssignment() {
        Student student = john();
        var admin = student.getSection().getCourse().getCourseAdmin();
        student.getSection().getInstructors().removeIf(i -> i.getId().equals(admin.getId()));
        em.flush();
        caller("b.wei@abc.edu", "ROLE_admin");
        assertThat(security.canManageStudentLifecycle(student.getId())).isTrue();
        assertThat(service.deactivateStudent(student.getId()).alreadyDeactivated()).isFalse();
    }

    @Test void unrelatedAdminAndStudentSelfDeniedWithoutChanges() {
        Integer id = john().getId();
        for (String email : List.of("t.cook@abc.edu", "j.smith@abc.edu")) {
            caller(email, email.startsWith("t.") ? "ROLE_admin" : "ROLE_student");
            assertThat(security.canManageStudentLifecycle(id)).isFalse();
            assertThatThrownBy(() -> service.deactivateStudent(id)).isInstanceOf(AccessDeniedException.class);
            assertThat(john().isEnabled()).isTrue();
            assertThat(john().getTeam()).isNotNull();
        }
    }

    @Test void unknownAccountDeniedAndMissingAccountDisabled() {
        caller("b.wei@abc.edu", "ROLE_admin");
        assertThat(security.canManageStudentLifecycle(Integer.MAX_VALUE)).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(Integer.MAX_VALUE)).isInstanceOf(AccessDeniedException.class);
        assertThat(accounts.isAccountEnabled(Integer.MAX_VALUE)).isFalse();
    }

    @Test void historicalEvaluateesSurviveOnlyInRecordedWeeks() {
        Student student = john();
        Integer id = student.getId(), section = student.getSection().getSectionId();
        var before = reports.getPeerEvaluationAverage("2023-W31", student);
        assertThat(evaluations.findByWeekAndEvaluateeId("2023-W31", id)).isNotEmpty();
        caller("b.wei@abc.edu", "ROLE_admin");
        service.deactivateStudent(id);
        em.flush(); em.clear();
        assertThat(evaluations.findHistoricalEvaluatees(section, "2023-W31")).extracting(Student::getId).contains(id);
        var after = reports.generateWeeklyPeerEvaluationReportForSection(section, "2023-W31").getPeerEvaluationAverages().stream()
                .filter(row -> row.getStudentId().equals(id)).findFirst().orElseThrow();
        assertThat(after.getAverageTotalScore()).isEqualTo(before.getAverageTotalScore());
        assertThat(after.getPublicComments()).isEqualTo(before.getPublicComments());
        assertThat(after.getPrivateComments()).isEqualTo(before.getPrivateComments());
        assertThat(reports.generateWeeklyPeerEvaluationReportForSection(section, "2099-W01").getPeerEvaluationAverages())
                .extracting(row -> row.getStudentId()).doesNotContain(id);
    }

    @Test void deactivationRollbackRestoresBothChanges() {
        Integer id = john().getId(), team = john().getTeam().getTeamId();
        caller("b.wei@abc.edu", "ROLE_admin");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> {
            service.deactivateStudent(id);
            em.flush();
            throw new IllegalStateException("induced failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        TestTransaction.flagForRollback(); TestTransaction.end(); TestTransaction.start();
        Student reloaded = students.findById(id).orElseThrow();
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.getTeam().getTeamId()).isEqualTo(team);
    }

    @Test void unassignedInstructorDenied() {
        Student student = john();
        Integer instructorId = users.findByEmail("b.gates@abc.edu").orElseThrow().getId();
        student.getSection().getInstructors().removeIf(instructor -> instructor.getId().equals(instructorId));
        em.flush();
        caller("b.gates@abc.edu", "ROLE_instructor");
        assertThat(security.canManageStudentLifecycle(student.getId())).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(student.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(student.isEnabled()).isTrue();
        assertThat(student.getTeam()).isNotNull();
    }

    @Test void liveStatusLookupIncludesInstructorAccounts() {
        var instructor = users.findByEmail("b.gates@abc.edu").orElseThrow();
        assertThat(accounts.isAccountEnabled(instructor.getId())).isTrue();
        instructor.setEnabled(false);
        em.flush(); em.clear();
        assertThat(accounts.isAccountEnabled(instructor.getId())).isFalse();
    }

    @Test void previousOpenWeekDoesNotPermitEvaluatingInactiveStudent() {
        Student evaluator = john();
        Student evaluatee = (Student) users.findByEmail("e.hudson@abc.edu").orElseThrow();
        evaluatee.setEnabled(false);
        var submission = new team.projectpulse.evaluation.PeerEvaluation();
        submission.setEvaluator(evaluator);
        submission.setEvaluatee(evaluatee);
        submission.setWeek("2023-W32");
        long count = evaluations.count();
        assertThatThrownBy(() -> reports.addPeerEvaluation(submission))
                .isInstanceOf(team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException.class)
                .hasMessage("Cannot evaluate an inactive student.");
        assertThat(evaluations.count()).isEqualTo(count);
    }

    @Test void concurrentDeactivationSerializesOnStudent() throws Exception {
        Integer sectionId = john().getSection().getSectionId();
        TestTransaction.flagForRollback(); TestTransaction.end();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Integer id = tx.execute(status -> {
            Student student = new Student("concurrency@abc.edu", "Concurrent", "Student", "concurrency@abc.edu", "secret", true, "student");
            student.setSection(em.find(team.projectpulse.section.Section.class, sectionId));
            return students.saveAndFlush(student).getId();
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Boolean> request = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            try {
                return tx.execute(status -> {
                    caller("b.wei@abc.edu", "ROLE_admin");
                    return service.deactivateStudent(id).alreadyDeactivated();
                });
            } finally { SecurityContextHolder.clearContext(); }
        };
        try {
            Future<Boolean> first = pool.submit(request), second = pool.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(false, true);
            Boolean enabled = tx.execute(status -> accounts.isAccountEnabled(id));
            assertThat(enabled).isFalse();
        } finally {
            start.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> students.deleteById(id));
            TestTransaction.start();
        }
    }
}
