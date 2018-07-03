package org.apereo.portal.fbms.api.v1;

import org.apereo.portal.fbms.data.FilterChainBuilder;
import org.apereo.portal.fbms.data.FormEntity;
import org.apereo.portal.fbms.data.FormRepository;
import org.apereo.portal.fbms.data.SubmissionEntity;
import org.apereo.portal.fbms.data.SubmissionRepository;
import org.apereo.portal.fbms.util.FnameValidator;
import org.apereo.portal.fbms.util.UserServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * REST endpoints for accessing and manipulating {@link RestV1Submission} objects.
 */
@RestController
@CrossOrigin(origins = "${org.apereo.portal.fbms.api.cors.origins:http://localhost:8080}")
@RequestMapping(SubmissionsRestController.API_ROOT)
public class SubmissionsRestController {

    public static final String API_ROOT = "/api/v1/submissions";

    @Autowired
    private FormRepository formRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private FilterChainBuilder filterChainBuilder;

    @Autowired
    private FnameValidator fnameValidator;

    @Autowired
    private UserServices userServices;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Obtains the user's most recent {@link RestV1Submission} to the {@link RestV1Form} with the
     * specified fname.
     */
    @RequestMapping(value = "/{fname}", method = RequestMethod.GET)
    public ResponseEntity getUserSubmission(@PathVariable("fname") String fname,
            HttpServletRequest request, HttpServletResponse response) {

        if (!fnameValidator.isValid(fname)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("The specified fname is invalid");
        }

        final String username = userServices.getUsername(request);

        final SubmissionEntity entity =
                filterChainBuilder.fromSupplier(request, response,
                        () -> submissionRepository.findMostRecentByUsernameAndFname(username, fname)
                ).get();

        if (entity != null) {
            return ResponseEntity
                    .status(HttpStatus.OK)
                    .body(RestV1Submission.fromEntity(entity));
        } else {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body("A submission from the specified user for the form with the specified " +
                            "fname does not exist:  " + fname);
        }

    }

    /**
     * Creates a new {@link RestV1Submission} for this user to the {@link RestV1Form} with the
     * specified fname.  NOTE:  there is no update (HTTP PUT) enpoint available for submissions;  each
     * interaction creates a new {@link RestV1Submission} object.  It's up to clients whether they want
     * to deal with multiple submissions by the same user, or only the most recent.
     */
    @RequestMapping(value = "/{fname}", method = RequestMethod.POST)
    public ResponseEntity respond(@PathVariable("fname") String fname,
            @RequestBody RestV1Submission submission, HttpServletRequest request,
            HttpServletResponse response) {

        logger.debug("Received the following RestV1Submission at {}/{} {}:  {}",
                API_ROOT, fname, RequestMethod.POST, submission);

        final String username = userServices.getUsername(request);

        if (!Objects.equals(username, submission.getUsername())) {
            /*
             * The username in the submission must match the Bearer token
             */
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Incorrect username");
        }

        final FormEntity formEntity = formRepository.findMostRecentByFname(fname);
        if (formEntity == null) {
            /*
             * There must be a Form with the specified fname
             */
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("The specified form does not exist:  " + fname);
        } else if (!Objects.equals(submission.getFormVersion(), formEntity.getId().getVersion())) {
            /*
             * The submission must be for the most recent version of the form
             */
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Incorrect Form version;  expected " +
                            formEntity.getId().getVersion() + ", was " +
                            submission.getFormVersion());
        } else if (!Objects.equals(submission.getFormFname(), fname)) {
            /*
             * The specified form fname (in the submission) must match the fname in the URI
             */
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("The specified form fname ('"
                            + submission.getFormFname() + "') does not match the fname in the URI ('"
                            + fname + "')");
        } else if (submission.getTimestamp() == null) {
            /*
             * The submission must contain a timestamp
             */
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Submission timestamp not set");
        }

        final SubmissionEntity mostRecent =
                submissionRepository.findMostRecentByUsernameAndFname(username, fname);
        if (mostRecent != null && submission.getTimestamp() < mostRecent.getId().getTimestamp().getTime()) {
            /*
             * The submission must be newer than the most recent one we already have
             */
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Submission timestamp is not sufficiently new");
        }

        // TODO:  validate JSON Schema

        final SubmissionEntity entity = filterChainBuilder.fromUnaryOperator(
                RestV1Submission.toEntity(submission),
                request,
                response,
                (e) -> submissionRepository.save(e)
        ).get();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(RestV1Submission.fromEntity(entity));

    }

}