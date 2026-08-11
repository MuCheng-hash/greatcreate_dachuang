package com.redculture.platform.service;

import com.redculture.platform.config.TaskSubmissionStorageProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskSubmissionStorageService {
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "pdf", "docx");
    private final TaskSubmissionStorageProperties properties;
    public TaskSubmissionStorageService(TaskSubmissionStorageProperties properties) { this.properties = properties; }

    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("attachment is required");
        if (file.getSize() > MAX_SIZE) throw new IllegalArgumentException("attachment must not exceed 10MB");
        String extension = extension(file.getOriginalFilename());
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("only images, PDF and DOCX attachments are supported");
        try {
            Path root = properties.storagePath(); Files.createDirectories(root);
            String key = UUID.randomUUID() + "." + extension;
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("invalid attachment path");
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(key, safeName(file.getOriginalFilename()), contentType(file.getContentType(), extension), file.getSize());
        } catch (IOException exception) { throw new IllegalStateException("failed to store attachment", exception); }
    }

    public Resource load(String key) {
        if (!StringUtils.hasText(key) || key.contains("/") || key.contains("\\")) throw new IllegalArgumentException("invalid attachment key");
        Path file = properties.storagePath().resolve(key).normalize();
        if (!file.startsWith(properties.storagePath()) || !Files.isRegularFile(file)) throw new IllegalArgumentException("attachment not found");
        return new FileSystemResource(file);
    }

    public void delete(String key) { try { Files.deleteIfExists(properties.storagePath().resolve(key).normalize()); } catch (IOException ignored) { } }
    private String extension(String filename) { if (!StringUtils.hasText(filename) || !filename.contains(".")) return ""; return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    private String safeName(String filename) { return StringUtils.hasText(filename) ? filename.replaceAll("[\\r\\n]", "").trim() : "attachment"; }
    private String contentType(String supplied, String extension) { return StringUtils.hasText(supplied) ? supplied : switch (extension) { case "pdf" -> "application/pdf"; case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; default -> "image/*"; }; }
    public record StoredFile(String key, String filename, String contentType, long size) { }
}
