package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.entity.KnowledgeDocument;
import com.redculture.platform.entity.KnowledgeIngestJob;
import com.redculture.platform.entity.KnowledgeDocumentImage;
import com.redculture.platform.mapper.KnowledgeDocumentImageMapper;
import com.redculture.platform.mapper.KnowledgeIngestJobMapper;
import com.redculture.platform.service.KnowledgeDocumentService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-documents")
//知识库文档管理
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService service;
    private final KnowledgeIngestJobMapper jobs;
    private final KnowledgeDocumentImageMapper images;
    public KnowledgeDocumentController(KnowledgeDocumentService service, KnowledgeIngestJobMapper jobs, KnowledgeDocumentImageMapper images) { this.service = service; this.jobs = jobs; this.images = images; }

    //上传知识库文件。接收可选的 schoolId、可选标题 title 和文件 file。
    //调用服务保存文件并创建异步导入任务，返回新建文档信息。
    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<KnowledgeDocument> upload(@RequestParam(required = false) Long schoolId, @RequestParam(required = false) String title,
                                                  @RequestParam MultipartFile file, HttpServletRequest request) {
        return ApiResponse.success(service.upload(schoolId, title, file, current(request)));
    }
    //查询指定学校的知识库文档列表。
    @GetMapping
    public ApiResponse<List<KnowledgeDocument>> list(@RequestParam(required = false) Long schoolId, HttpServletRequest request) { return ApiResponse.success(service.list(schoolId, current(request))); }
    //查询单个文档详情，同时返回对应的导入任务信息
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id, HttpServletRequest request) {
        KnowledgeDocument document = service.detail(id, current(request));
        KnowledgeIngestJob job = jobs.selectOne(new LambdaQueryWrapper<KnowledgeIngestJob>().eq(KnowledgeIngestJob::getDocumentId, id));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("document", document); result.put("job", job); result.put("images", images.selectList(new LambdaQueryWrapper<KnowledgeDocumentImage>().eq(KnowledgeDocumentImage::getDocumentId, id))); return ApiResponse.success(result);
    }
    //重新执行失败的导入任务。
    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable Long id, @RequestParam(required = false) String restartFrom, HttpServletRequest request) { service.retry(id, restartFrom, current(request)); return ApiResponse.success(null); }
    @GetMapping(value = "/{id}/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public String markdown(@PathVariable Long id, HttpServletRequest request) { return service.markdown(id, current(request)); }
    //删除指定文档。
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) { service.delete(id, current(request)); return ApiResponse.success(null); }
    private AuthCurrentUserVO current(HttpServletRequest request) { return AuthContext.requireUser(request); }
}
