package com.sjherp.app.gap;

import com.sjherp.domain.gap.GapIssueDisabledException;
import com.sjherp.domain.gap.GapIssueNotFoundException;
import com.sjherp.domain.gap.GapIssueStateException;
import com.sjherp.domain.gap.GapRecordNotFoundException;
import com.sjherp.domain.gap.GitHubIssueGatewayException;
import com.sjherp.domain.gap.DeveloperAgentGatewayException;
import com.sjherp.domain.gap.DeveloperAgentTaskNotFoundException;
import com.sjherp.domain.gap.DeveloperAgentTaskStateException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = GapExceptionHandler.class)
public class GapExceptionHandler {
    @ExceptionHandler({GapRecordNotFoundException.class, GapIssueNotFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) { return error(HttpStatus.NOT_FOUND, e.getMessage()); }
    @ExceptionHandler(GapIssueStateException.class)
    public ResponseEntity<Map<String, String>> conflict(GapIssueStateException e) { return error(HttpStatus.CONFLICT, e.getMessage()); }
    @ExceptionHandler(GapIssueDisabledException.class)
    public ResponseEntity<Map<String, String>> disabled(GapIssueDisabledException e) { return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()); }
    @ExceptionHandler(GitHubIssueGatewayException.class)
    public ResponseEntity<Map<String, String>> gateway(GitHubIssueGatewayException e) { return error(HttpStatus.BAD_GATEWAY, e.getMessage()); }
    @ExceptionHandler(DeveloperAgentGatewayException.class)
    public ResponseEntity<Map<String, String>> developerGateway(DeveloperAgentGatewayException e) { return error(HttpStatus.BAD_GATEWAY, e.getMessage()); }
    @ExceptionHandler(DeveloperAgentTaskNotFoundException.class)
    public ResponseEntity<Map<String, String>> developerNotFound(DeveloperAgentTaskNotFoundException e) { return error(HttpStatus.NOT_FOUND, e.getMessage()); }
    @ExceptionHandler(DeveloperAgentTaskStateException.class)
    public ResponseEntity<Map<String, String>> developerState(DeveloperAgentTaskStateException e) { return error(HttpStatus.CONFLICT, e.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldError();
        return error(HttpStatus.BAD_REQUEST, field == null ? "invalid request" : field.getField() + ": " + field.getDefaultMessage());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) { return error(HttpStatus.BAD_REQUEST, "invalid JSON request"); }
    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) { return ResponseEntity.status(status).body(Map.of("error", message)); }
}
