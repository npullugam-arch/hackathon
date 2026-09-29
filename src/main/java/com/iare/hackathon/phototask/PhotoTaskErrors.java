package com.iare.hackathon.phototask;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice(assignableTypes={PhotoTaskController.class,AdminPhotoTaskController.class,MachineRewardController.class})
public class PhotoTaskErrors {
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Unable to process this task.":e.getReason()));}
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestPartException.class,MissingServletRequestParameterException.class})
 @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> invalid(){return Map.of("message","Check the photo, request ID and review details, then try again.");}
 @ExceptionHandler(MaxUploadSizeExceededException.class) @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE) public Map<String,String> tooLarge(){return Map.of("message","Choose a JPEG or PNG photo up to 5 MB.");}
 @ExceptionHandler({DataAccessException.class,TransactionException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
 public Map<String,String> unavailable(){return Map.of("message","We could not confirm this task operation. Retry the same request safely or refresh to check its status.");}
}
