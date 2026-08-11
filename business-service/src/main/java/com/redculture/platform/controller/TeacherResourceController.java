package com.redculture.platform.controller;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TeacherResourceService;
import com.redculture.platform.vo.TeacherResourceVO;
import com.redculture.platform.vo.request.TeacherResourceQueryRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/teacher/resources") public class TeacherResourceController {
 private final TeacherResourceService service; public TeacherResourceController(TeacherResourceService service){this.service=service;}
 @GetMapping("/nearby") public ApiResponse<List<TeacherResourceVO>> nearby(TeacherResourceQueryRequest query,HttpServletRequest request){return run(()->service.nearby(query,AuthContext.requireUser(request)));}
 @GetMapping("/favorites") public ApiResponse<List<TeacherResourceVO>> favorites(HttpServletRequest request){return run(()->service.favorites(AuthContext.requireUser(request)));}
 @GetMapping("/selection") public ApiResponse<List<TeacherResourceVO>> selection(HttpServletRequest request){return run(()->service.selection(AuthContext.requireUser(request)));}
 @GetMapping("/{resourceId}") public ApiResponse<TeacherResourceVO> detail(@PathVariable Long resourceId,HttpServletRequest request){return run(()->service.detail(resourceId,AuthContext.requireUser(request)));}
 @PostMapping("/{resourceId}/favorite") public ApiResponse<Void> favorite(@PathVariable Long resourceId,HttpServletRequest request){return run(()->{service.favorite(resourceId,AuthContext.requireUser(request));return null;});}
 @DeleteMapping("/{resourceId}/favorite") public ApiResponse<Void> unfavorite(@PathVariable Long resourceId,HttpServletRequest request){return run(()->{service.unfavorite(resourceId,AuthContext.requireUser(request));return null;});}
 private <T> ApiResponse<T> run(Action<T> action){try{return ApiResponse.success(action.get());}catch(IllegalArgumentException e){return ApiResponse.fail(e.getMessage());}} @FunctionalInterface private interface Action<T>{T get();}
}
