package com.example.interviewreader.common;

import com.example.interviewreader.config.UploadProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {
    @Test
    void databaseConstraintViolationsAreLoggedWithTheReturnedTraceId(CapturedOutput output) {
        var request = new MockHttpServletRequest();
        var traceId = RequestTrace.initialize(request);
        try {
            var handler = new ApiExceptionHandler(new ApiProblemFactory(), new UploadProperties(DataSize.ofMegabytes(10)));
            var problem = handler.handleIntegrityViolation(new DataIntegrityViolationException("unique constraint"));

            assertThat(problem.getProperties()).containsEntry("traceId", traceId);
            assertThat(output).contains("Database constraint violation traceId=" + traceId);
        } finally {
            RequestTrace.clear();
        }
    }
}
