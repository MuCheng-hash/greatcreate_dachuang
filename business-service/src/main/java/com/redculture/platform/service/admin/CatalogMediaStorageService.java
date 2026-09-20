package com.redculture.platform.service.admin;

import com.redculture.platform.config.AdminMediaProperties;
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
public class CatalogMediaStorageService {

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private final AdminMediaProperties properties;

    public CatalogMediaStorageService(AdminMediaProperties properties) {
        this.properties = properties;
    }

    public StoredMedia store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片文件不能为空");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片文件不能超过 10MB");
        }
        String extension = extension(file.getOriginalFilename());
        if (!EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 JPG、PNG 和 WebP 图片");
        }
        try {
            Path root = properties.storagePath();
            Files.createDirectories(root);
            String filename = UUID.randomUUID() + "." + extension;
            Path target = root.resolve(filename).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("图片文件路径无效");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredMedia("/uploads/resource-media/" + filename, target, originalTitle(file.getOriginalFilename()));
        } catch (IOException exception) {
            throw new IllegalStateException("保存图片文件失败", exception);
        }
    }

    public void deleteIfManaged(String mediaUrl) {
        if (!StringUtils.hasText(mediaUrl) || !mediaUrl.startsWith("/uploads/resource-media/")) {
            return;
        }
        String filename = mediaUrl.substring("/uploads/resource-media/".length());
        if (filename.contains("/") || filename.contains("\\") || filename.isBlank()) {
            return;
        }
        try {
            Path root = properties.storagePath();
            Path target = root.resolve(filename).normalize();
            if (target.startsWith(root)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("删除已存储图片文件失败", exception);
        }
    }

    private String extension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String originalTitle(String filename) {
        return StringUtils.hasText(filename) ? filename.replaceAll("[\\r\\n]", "").trim() : null;
    }

    public record StoredMedia(String publicUrl, Path path, String originalTitle) {
    }
}
