package com.reliefgrid;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(NotFound.class)
  ResponseEntity<Map<String,String>> notFound(NotFound error) { return ResponseEntity.status(404).body(Map.of("detail", error.getMessage())); }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String,Object>> validation(MethodArgumentNotValidException error) {
    List<Map<String,Object>> details = error.getBindingResult().getFieldErrors().stream().map(field -> {
      Map<String,Object> detail = new LinkedHashMap<>();
      detail.put("loc", List.of("body", field.getField()));
      detail.put("msg", field.getDefaultMessage());
      detail.put("type", "validation_error");
      return detail;
    }).toList();
    return ResponseEntity.unprocessableEntity().body(Map.of("detail", details));
  }
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String,String>> badRequest(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("detail", error.getMessage())); }
}
class NotFound extends RuntimeException { NotFound(String message) { super(message); } }
