package io.yunxi.platform.infra.file.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.yunxi.platform.infra.file.FileType;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.infra.file.storage.FileStorageService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 文本文档内容提取器
 *
 * <p>
 * 支持纯文本文件的内容提取
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class TextContentExtractor implements FileContentExtractor {

    /** 文件存储服务 */
    private final FileStorageService fileStorageService;

    /**
     * 构造函数
     *
     * @param fileStorageService 文件存储服务
     */
    public TextContentExtractor(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 提取文本文件内容
     *
     * @param file 文件信息
     * @return 提取的文本内容
     * @throws Exception 提取失败时抛出异常
     */
    @Override
    public String extract(UserFileEntity file) throws Exception {
        try (InputStream input = fileStorageService.get(file.getFilePath());
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            log.debug("文本文件内容提取成功: fileId={}, contentLength={}", file.getId(), content.length());
            return content.toString();

        } catch (IOException e) {
            log.error("文本文件内容提取失败: fileId={}", file.getId(), e);
            throw e;
        }
    }

    /**
     * 检查是否支持该文件类型
     *
     * @param fileType 文件类型
     * @return 是否支持文档类型
     */
    @Override
    public boolean supports(FileType fileType) {
        // 支持文档类型，但实际只处理文本文件
        return fileType == FileType.DOCUMENT;
    }

    /**
     * 获取提取器名称
     *
     * @return 提取器名称
     */
    @Override
    public String getName() {
        return "TextExtractor";
    }
}
