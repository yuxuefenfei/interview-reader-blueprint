package cn.wangwenzhu.ireader.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class ApiProblemFactory {
    public ProblemDetail create(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://interview-reader.local/problems/" + code.toLowerCase()));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("error", detail);
        problem.setProperty("traceId", RequestTrace.currentId());
        return problem;
    }
}
