package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    @Query("""
            select s from Student s where s.id = :studentId and
            (s.section.course.courseAdmin.id = :callerId or
             exists (select i.id from Section sec join sec.instructors i
                     where sec = s.section and i.id = :callerId))
            """)
    Optional<Student> findLifecycleStudent(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Student s where s.id = :studentId and
            (s.section.course.courseAdmin.id = :callerId or
             exists (select i.id from Section sec join sec.instructors i
                     where sec = s.section and i.id = :callerId))
            """)
    Optional<Student> findLifecycleStudentForUpdate(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
