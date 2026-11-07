package team.projectpulse.system.exception;

/**
 * UC-STU-deactivate-student 6a: the student named is already deactivated, so nothing is changed. Mapped to
 * {@code 409} / {@code StatusCode.CONFLICT}.
 */
public class StudentAlreadyDeactivatedException extends RuntimeException {

    public StudentAlreadyDeactivatedException() {
        super("This student is already deactivated.");
    }

}
