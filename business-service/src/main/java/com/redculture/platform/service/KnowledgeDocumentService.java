package com.redculture.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.KnowledgeStorageProperties;
import com.redculture.platform.entity.KnowledgeDocument;
import com.redculture.platform.entity.KnowledgeIngestJob;
import com.redculture.platform.mapper.KnowledgeDocumentMapper;
import com.redculture.platform.mapper.KnowledgeIngestJobMapper;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.KnowledgeDocumentListItem;
import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import io.minio.BucketExistsArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeDocumentService {
    private static final List<String> EXTENSIONS = List.of("pdf", "docx", "md", "markdown");
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeIngestJobMapper jobs;
    private final SchoolAccessService schoolAccessService;
    private final MinioClient minio;
    private final KnowledgeStorageProperties storage;
    private final KnowledgeIngestTransactions transactions;
    private final KnowledgeVectorCleanupService vectorCleanup;

    public KnowledgeDocumentService(KnowledgeDocumentMapper documents, KnowledgeIngestJobMapper jobs,
                                    SchoolAccessService schoolAccessService, MinioClient minio,
                                    KnowledgeStorageProperties storage, KnowledgeIngestTransactions transactions, KnowledgeVectorCleanupService vectorCleanup) {
        this.documents = documents; this.jobs = jobs; this.schoolAccessService = schoolAccessService;
        this.minio = minio; this.storage = storage; this.transactions = transactions; this.vectorCleanup = vectorCleanup;
    }

    public KnowledgeDocument upload(Long schoolId, String title, MultipartFile file, AuthCurrentUserVO user) {
        schoolAccessService.requireSchoolAccess(schoolId, user);
        if (file == null || file.isEmpty() || file.getSize() > storage.getMaxFileSizeBytes()) throw new IllegalArgumentException("文件无效或超过 50 MB");
        String filename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("仅支持 PDF、DOCX 和 Markdown 文件");
        String key = "original/" + (schoolId == null ? "public" : schoolId) + "/" + UUID.randomUUID() + "." + extension;
        try (InputStream input = file.getInputStream()) {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucket()).object(key).stream(input, file.getSize(), -1)
                    .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType()).build());
        } catch (Exception exception) { throw new IllegalStateException("保存上传文件失败", exception); }
        try {
            return transactions.run(() -> {
                KnowledgeDocument document = new KnowledgeDocument();
                document.setSchoolId(schoolId); document.setTitle(title == null || title.isBlank() ? filename : title.trim());
                document.setOriginalFilename(filename); document.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType()); document.setFileSize(file.getSize());
                document.setObjectKey(key); document.setStatus("PENDING"); document.setCreatedBy(user.getAccountId()); documents.insert(document);
                KnowledgeIngestJob job = new KnowledgeIngestJob(); job.setDocumentId(document.getId()); job.setStatus("PENDING"); job.setCurrentNode("VALIDATE"); job.setRetryCount(0); jobs.insert(job);
                transactions.enqueue(job.getId(), document.getId()); return document;
            });
        } catch (RuntimeException error) {
            try { minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(key).build()); }
            catch (Exception cleanupError) { error.addSuppressed(cleanupError); }
            throw error;
        }
    }

    public KnowledgeDocument detail(Long id, AuthCurrentUserVO user) { KnowledgeDocument document = require(id); schoolAccessService.requireSchoolAccess(document.getSchoolId(), user); return document; }
    public List<KnowledgeDocument> list(Long schoolId, AuthCurrentUserVO user) { schoolAccessService.requireSchoolAccess(schoolId, user); return documents.selectList(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getSchoolId, schoolId).orderByDesc(KnowledgeDocument::getId)); }

    /** 查询平台管理员管理文档所需的文档与入库任务摘要。 */
    public List<KnowledgeDocumentListItem> listForAdmin(String scope, Long schoolId) {
        LambdaQueryWrapper<KnowledgeDocument> query = new LambdaQueryWrapper<>();
        String normalizedScope = scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        switch (normalizedScope) {
            case "all" -> { }
            case "public" -> query.isNull(KnowledgeDocument::getSchoolId);
            case "school" -> {
                if (schoolId == null || schoolId <= 0) throw new IllegalArgumentException("请选择学校");
                query.eq(KnowledgeDocument::getSchoolId, schoolId);
            }
            default -> throw new IllegalArgumentException("不支持的文档范围");
        }
        List<KnowledgeDocument> documentList = documents.selectList(query.orderByDesc(KnowledgeDocument::getId));
        if (documentList.isEmpty()) return List.of();
        List<Long> documentIds = documentList.stream().map(KnowledgeDocument::getId).toList();
        Map<Long, KnowledgeIngestJob> jobsByDocumentId = jobs.selectList(
                        new LambdaQueryWrapper<KnowledgeIngestJob>().in(KnowledgeIngestJob::getDocumentId, documentIds))
                .stream().collect(Collectors.toMap(KnowledgeIngestJob::getDocumentId, Function.identity()));
        return documentList.stream().map(document -> toListItem(document, jobsByDocumentId.get(document.getId()))).toList();
    }

    public void retry(Long id, String restartFrom, AuthCurrentUserVO user) {
        detail(id, user);
        String node = restartFrom == null || restartFrom.isBlank() ? "VALIDATE" : restartFrom.trim().toUpperCase(Locale.ROOT);
        if (!List.of("VALIDATE", "CONVERT", "IMAGE_VISION", "CHUNK", "METADATA", "INDEX").contains(node)) throw new IllegalArgumentException("不支持的重试节点");
        transactions.locked(id, () -> {
            KnowledgeDocument document = detail(id, user);
            KnowledgeIngestJob job = jobs.selectOne(new LambdaQueryWrapper<KnowledgeIngestJob>().eq(KnowledgeIngestJob::getDocumentId, id));
            if (job == null || !("FAILED".equals(document.getStatus()) || "DEGRADED".equals(document.getStatus()))) throw new IllegalStateException("只有失败或降级的文档可以重试");
            document.setStatus("PENDING"); documents.updateById(document);
            transactions.reset(job.getId());
            transactions.enqueue(job.getId(), id);
            return null;
        });
    }

    private KnowledgeDocumentListItem toListItem(KnowledgeDocument document, KnowledgeIngestJob job) {
        KnowledgeDocumentListItem item = new KnowledgeDocumentListItem();
        item.setDocumentId(document.getId());
        item.setSchoolId(document.getSchoolId());
        item.setTitle(document.getTitle());
        item.setOriginalFilename(document.getOriginalFilename());
        item.setContentType(document.getContentType());
        item.setFileSize(document.getFileSize());
        item.setDocumentStatus(document.getStatus());
        item.setCreatedAt(document.getCreatedAt());
        item.setIndexedAt(document.getIndexedAt());
        if (job != null) {
            item.setJobStatus(job.getStatus());
            item.setCurrentNode(job.getCurrentNode());
            item.setRetryCount(job.getRetryCount());
            item.setErrorSummary(job.getErrorSummary());
            item.setFinishedAt(job.getFinishedAt());
        }
        return item;
    }

    public String markdown(Long id, AuthCurrentUserVO user) { KnowledgeDocument document = detail(id, user); if (document.getMarkdownObjectKey() == null) throw new IllegalStateException("规范化 Markdown 不可用"); try (InputStream input = minio.getObject(GetObjectArgs.builder().bucket(storage.getBucket()).object(document.getMarkdownObjectKey()).build())) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception exception) { throw new IllegalStateException("读取规范化 Markdown 失败", exception); } }
    public void delete(Long id, AuthCurrentUserVO user) {
        detail(id, user);
        transactions.locked(id, () -> {
            KnowledgeDocument document = detail(id, user); vectorCleanup.deleteDocument(id);
            try { minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(document.getObjectKey()).build()); if (document.getMarkdownObjectKey() != null) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucket()).object(document.getMarkdownObjectKey()).build()); } catch (Exception ignored) { }
            documents.deleteById(id); return null;
        });
    }
    private KnowledgeDocument require(Long id) { KnowledgeDocument document = documents.selectById(id); if (document == null) throw new IllegalArgumentException("知识文档不存在"); return document; }
    private void ensureBucket() throws Exception { if (!minio.bucketExists(BucketExistsArgs.builder().bucket(storage.getBucket()).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(storage.getBucket()).build()); }
}
