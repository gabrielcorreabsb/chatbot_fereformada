package br.com.fereformada.api.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice // Intercepta exceções de todos os controllers
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Manipula nossas exceções específicas (404, 403)
    @ExceptionHandler({ResourceNotFoundException.class, ForbiddenException.class})
    public ResponseEntity<Object> handleCustomExceptions(RuntimeException ex, WebRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR; // Default
        if (ex instanceof ResourceNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (ex instanceof ForbiddenException) {
            status = HttpStatus.FORBIDDEN;
        }

        logger.warn("Erro {} tratado: {}", status, ex.getMessage()); // Loga como aviso

        return buildErrorResponse(ex, status, request);
    }

    // Manipula QUALQUER outra exceção não tratada (retorna 500)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllUncaughtException(Exception ex, WebRequest request) {
        logger.error("Erro inesperado:", ex); // Loga o stack trace completo aqui (mas não envia ao cliente)

        return buildErrorResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // Método auxiliar para construir a resposta JSON padronizada
    private ResponseEntity<Object> buildErrorResponse(Exception ex, HttpStatus status, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", ex.getMessage()); // Mensagem da nossa exceção
        body.put("path", request.getDescription(false).replace("uri=", "")); // Caminho da URL

        return new ResponseEntity<>(body, status);
    }
}