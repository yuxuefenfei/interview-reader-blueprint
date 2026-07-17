package com.example.interviewreader.common;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;

@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblemFactory problemFactory;


    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        return problemFactory.create(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        var fields = new LinkedHashMap<String, String>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        var detail = fields.isEmpty()
                ? "请求参数不合法"
                : fields.entrySet().iterator().next().getKey() + " " + fields.entrySet().iterator().next().getValue();
        var problem = problemFactory.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
        problem.setProperty("fieldErrors", fields);
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return problemFactory.create(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "上传文件不能超过 10MB。");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrityViolation(DataIntegrityViolationException exception) {
        return problemFactory.create(HttpStatus.CONFLICT, "DATA_CONFLICT", "当前操作与已有数据关联冲突，请刷新后重试。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return problemFactory.create(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请求参数不合法。");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        var problem = problemFactory.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器暂时无法处理该请求。");
        LOGGER.error("Unhandled request failure traceId={}", problem.getProperties().get("traceId"), exception);
        return problem;
    }
}