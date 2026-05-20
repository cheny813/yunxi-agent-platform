package io.yunxi.platform.infra.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件存储服务
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "file-upload.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    /** 文件存储基础路径 */
    @Value("${file-storage.local.base-path:./uploads}")
    private String basePath;

    /**
     * 初始化：创建基础目录
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            Path baseDir = Paths.get(basePath);
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
                log.info("创建文件存储基础目录: {}", basePath);
            }
        } catch (IOException e) {
            log.error("创建文件存储目录失败", e);
        }
    }

    @Override
    public String save(String userId, MultipartFile file) throws IOException {
        // 生成文件路径: {basePath}/{userId}/{timestamp}_{originalFilename}
        String fileName = file.getOriginalFilename();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String relativePath = userId + "/" + timestamp + "_" + fileName;
        Path fullPath = Paths.get(basePath, relativePath);

        // 创建目录
        Files.createDirectories(fullPath.getParent());

        // 保存文件
        file.transferTo(fullPath.toFile());

        log.debug("文件保存成功: userId={}, path={}, size={}", userId, relativePath, file.getSize());
        return relativePath;
    }

    @Override
    public String save(String userId, String fileName, InputStream input) throws IOException {
        // 生成文件路径
        String timestamp = String.valueOf(System.currentTimeMillis());
        String relativePath = userId + "/" + timestamp + "_" + fileName;
        Path fullPath = Paths.get(basePath, relativePath);

        // 创建目录
        Files.createDirectories(fullPath.getParent());

        // 保存文件
        Files.copy(input, fullPath, StandardCopyOption.REPLACE_EXISTING);

        log.debug("文件流保存成功: userId={}, path={}", userId, relativePath);
        return relativePath;
    }

    @Override
    public InputStream get(String filePath) throws IOException {
        Path fullPath = Paths.get(basePath, filePath);
        if (!Files.exists(fullPath)) {
            throw new IOException("文件不存在: " + filePath);
        }
        return Files.newInputStream(fullPath);
    }

    @Override
    public void delete(String filePath) throws IOException {
        Path fullPath = Paths.get(basePath, filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.debug("文件删除成功: path={}", filePath);
        }
    }

    @Override
    public boolean exists(String filePath) {
        Path fullPath = Paths.get(basePath, filePath);
        return Files.exists(fullPath);
    }

    @Override
    public String getUrl(String filePath) {
        // 本地存储返回文件路径，实际场景中应该返回HTTP访问URL
        return "/api/files/" + filePath;
    }

    @Override
    public long getSize(String filePath) throws IOException {
        Path fullPath = Paths.get(basePath, filePath);
        if (!Files.exists(fullPath)) {
            throw new IOException("文件不存在: " + filePath);
        }
        return Files.size(fullPath);
    }
}
