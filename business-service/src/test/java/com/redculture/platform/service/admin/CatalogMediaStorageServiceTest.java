package com.redculture.platform.service.admin;

import com.redculture.platform.config.AdminMediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogMediaStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesManagedImageWithPublicUrlAndDeletesIt() {
        CatalogMediaStorageService service = service();

        CatalogMediaStorageService.StoredMedia stored = service.store(
                new MockMultipartFile("file", "cover.png", "image/png", new byte[]{1, 2, 3}));

        assertTrue(stored.publicUrl().startsWith("/uploads/resource-media/"));
        assertTrue(Files.exists(stored.path()));
        service.deleteIfManaged(stored.publicUrl());
        assertFalse(Files.exists(stored.path()));
    }

    @Test
    void rejectsUnsupportedOrOversizedFiles() {
        CatalogMediaStorageService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.store(
                new MockMultipartFile("file", "cover.gif", "image/gif", new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> service.store(
                new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1])));
    }

    private CatalogMediaStorageService service() {
        AdminMediaProperties properties = new AdminMediaProperties();
        properties.setStorageDir(tempDir.toString());
        return new CatalogMediaStorageService(properties);
    }
}
