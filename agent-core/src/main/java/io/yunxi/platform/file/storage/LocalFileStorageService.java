package io.yunxi.platform.file.storage;

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
 * @version 2.0.0
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

    /**
     * 保存上传文件（MultipartFile 形式）。
     *
     * @param userId 用户 ID
     * @param file   上传的文件
     * @return 相对存储路径
     * @throws IOException 当目录创建或写入失败时抛出
     */
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

    /**
     * 保存文件流（InputStream 形式）。
     *
     * @param userId   用户 ID
     * @param fileName 文件名
     * @param input    文件输入流
     * @return 相对存储路径
     * @throws IOException 当目录创建或写入失败时抛出
     */
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

    /**
     * 读取文件内容为输入流。
     *
     * @param filePath 相对存储路径
     * @return 文件输入流
     * @throws IOException 当文件不存在或读取失败时抛出
     */
    @Override
    public InputStream get(String filePath) throws IOException {
        Path fullPath = Paths.get(basePath, filePath);
        if (!Files.exists(fullPath)) {
            throw new IOException("文件不存在: " + filePath);
        }
        return Files.newInputStream(fullPath);
    }

    /**
     * 删除文件（存在时才删除）。
     *
     * @param filePath 相对存储路径
     * @throws IOException 当删除失败时抛出
     */
    @Override
    public void delete(String filePath) throws IOException {
        Path fullPath = Paths.get(basePath, filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.debug("文件删除成功: path={}", filePath);
        }
    }

    /**
     * 判断文件是否存在。
     *
     * @param filePath 相对存储路径
     * @return 存在返回 true
     */
    @Override
    public boolean exists(String filePath) {
        Path fullPath = Paths.get(basePath, filePath);
        return Files.exists(fullPath);
    }

    /**
     * 获取文件访问 URL（本地模式返回 HTTP 伪路径）。
     *
     * @param filePath 相对存储路径
     * @return 文件访问 URL
     */
    @Override
    public String getUrl(String filePath) {
        // 本地存储返回文件路径，实际场景中应该返回HTTP访问URL
        return "/api/files/" + filePath;
    }

    /**
     * 获取文件大小（字节）。
     *
     * @param filePath 相对存储路径
     * @return 文件大小（字节）
     * @throws IOException 当文件不存在或读取失败时抛出
     */
    @Override
    public long getSize(String filePath) throws IOException {
        Path fullPath = Paths.get(basePath, filePath);
        if (!Files.exists(fullPath)) {
            throw new IOException("文件不存在: " + filePath);
        }
        return Files.size(fullPath);
    }
}
