package com.redculture.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.KnowledgeStorageProperties;
import com.redculture.platform.entity.KnowledgeDocument;
import com.redculture.platform.entity.KnowledgeIngestJob;
import com.redculture.platform.mapper.KnowledgeDocumentMapper;
import com.redculture.platform.mapper.KnowledgeIngestJobMapper;
import com.redculture.platform.vo.AuthCurrentUserVO;
import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import io.minio.BucketExistsArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetObjectArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class KnowledgeDocumentService {
    private static final List<String> EXTENSIONS = List.of("pdf", "docx", "md", "markdown");
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeIngestJobMapper jobs;
    private final SchoolAccessService schoolAccessService;
    private final MinioClient minio;
    private final KnowledgeStorageProperties storage;
    private final StringRedisTemplate redis;
    private final KnowledgeVectorCleanupService vectorCleanup;

    public KnowledgeDocumentService(KnowledgeDocumentMapper documents, KnowledgeIngestJobMapper jobs,
                                    SchoolAccessService schoolAccessService, MinioClient minio,
                                    KnowledgeStorageProperties storage, StringRedisTemplate redis, KnowledgeVectorCleanupService vectorCleanup) {
        this.documents = documents; this.jobs = jobs; this.schoolAccessService = schoolAccessService;
        this.minio = minio; this.storage = storage; this.redis = redis; this.vectorCleanup = vectorCleanup;
    }

    public KnowledgeDocument upload(Long schoolId, String title, MultipartFile file, AuthCurrentUserVO user) {
        schoolAccessService.requireSchoolAccess(schoolId, user);
        if (file == null || file.isEmpty() || file.getSize() > storage.getMaxFileSizeBytes()) throw new IllegalArgumentException("invalid file or file exceeds 50 MB");
        String filename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("only PDF, DOCX, and Markdown files are supported");
        String key = "original/" + (schoolId == null ? "public" : schoolId) + "/" + UUID.randomUUID() + "." + extension;
        try (InputStream input = file.getInputStream()) {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key).stream(input, file.getSize(), -1)
                    .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType()).build());
        } catch (Exception exception) { throw new IllegalStateException("failed to save uploaded file", exception); }
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSchoolId(schoolId); document.setTitle(title == null || title.isBlank() ? filename : title.trim());
        document.setOriginalFilename(filename); document.setContentType(file.getContentType()); document.setFileSize(file.getSize());
        document.setObjectKey(key); document.setStatus("PENDING"); document.setCreatedBy(user.getAccountId()); documents.insert(document);
        KnowledgeIngestJob job = new KnowledgeIngestJob(); job.setDocumentId(document.getId()); job.setStatus("PENDING"); job.setCurrentNode("VALIDATE"); job.setRetryCount(0); jobs.insert(job);
        enqueue(job.getId()); return document;
    }

    public KnowledgeDocument detail(Long id, AuthCurrentUserVO user) { KnowledgeDocument document = require(id); schoolAccessService.requireSchoolAccess(document.getSchoolId(), user); return document; }
    public List<KnowledgeDocument> list(Long schoolId, AuthCurrentUserVO user) { schoolAccessService.requireSchoolAccess(schoolId, user); return documents.selectList(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getSchoolId, schoolId).orderByDesc(KnowledgeDocument::getId)); }
    public void retry(Long id, String restartFrom, AuthCurrentUserVO user) { KnowledgeDocument document = detail(id, user); KnowledgeIngestJob job = jobs.selectOne(new LambdaQueryWrapper<KnowledgeIngestJob>().eq(KnowledgeIngestJob::getDocumentId, id)); if (!("FAILED".equals(document.getStatus()) || "DEGRADED".equals(document.getStatus())) || job == null) throw new IllegalStateException("only failed or degraded documents can be retried"); String node = restartFrom == null || restartFrom.isBlank() ? "VALIDATE" : restartFrom.trim().toUpperCase(Locale.ROOT); if (!List.of("VALIDATE", "CONVERT", "IMAGE_VISION", "CHUNK", "METADATA", "INDEX").contains(node)) throw new IllegalArgumentException("unsupported restart node"); document.setStatus("PENDING"); documents.updateById(document); job.setStatus("PENDING"); job.setCurrentNode(node); job.setRestartFrom(node); job.setErrorSummary(null); job.setFinishedAt(null); jobs.updateById(job); enqueue(job.getId()); }
    public String markdown(Long id, AuthCurrentUserVO user) { KnowledgeDocument document = detail(id, user); if (document.getMarkdownObjectKey() == null) throw new IllegalStateException("normalized markdown is not available"); try (InputStream input = minio.getObject(GetObjectArgs.builder().bucket(storage.getBucket()).object(document.getMarkdownObjectKey()).build())) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception exception) { throw new IllegalStateException("failed to read normalized markdown", exception); } }
    public void delete(Long id, AuthCurrentUserVO user) { KnowledgeDocument document = detail(id, user); vectorCleanup.deleteDocument(id); try { minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(document.getObjectKey()).build()); if (document.getMarkdownObjectKey() != null) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(document.getMarkdownObjectKey()).build()); } catch (Exception ignored) { } documents.deleteById(id); }
    private KnowledgeDocument require(Long id) { KnowledgeDocument document = documents.selectById(id); if (document == null) throw new IllegalArgumentException("knowledge document not found"); return document; }
    private void enqueue(Long jobId) { redis.opsForList().rightPush("knowledge:ingest", String.valueOf(jobId)); }
    private void ensureBucket() throws Exception { if (!minio.bucketExists(BucketExistsArgs.builder().bucket(storage.getBucket()).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(storage.getBucket()).build()); }
}
