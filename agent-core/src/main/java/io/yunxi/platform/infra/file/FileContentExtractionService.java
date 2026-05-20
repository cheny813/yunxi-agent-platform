package io.yunxi.platform.infra.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.yunxi.platform.infra.file.extractor.FileContentExtractor;
import io.yunxi.platform.shared.entity.UserFileEntity;

import java.util.List;

/**
 * 文件内容提取服务
 *
 * <p>
 * 根据文件类型选择合适的提取器进行内容提取
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class FileContentExtractionService {

    /** 文件内容提取器列表 */
    private final List<FileContentExtractor> extractors;

    /**
     * 构造函数
     *
     * @param extractors 文件内容提取器列表
     */
    public FileContentExtractionService(List<FileContentExtractor> extractors) {
        this.extractors = extractors;
        log.info("文件内容提取服务初始化，加载 {} 个提取器", extractors.size());
    }

    /**
     * 提取文件内容
     *
     * @param file 文件信息
     * @return 提取的文本内容
     */
    public String extractContent(UserFileEntity file) throws Exception {
        FileType fileType = file.getFileType();

        // 查找支持该文件类型的提取器
        FileContentExtractor extractor = extractors.stream()
                .filter(e -> e.supports(fileType))
                .findFirst()
                .orElse(null);

        if (extractor == null) {
            log.warn("没有找到支持文件类型 {} 的提取器", fileType);
            throw new UnsupportedOperationException("不支持的文件类型: " + fileType);
        }

        log.info("使用 {} 提取文件内容: fileId={}, fileType={}",
                extractor.getName(), file.getId(), fileType);

        return extractor.extract(file);
    }

    /**
     * 检查是否支持该文件类型
     *
     * @param fileType 文件类型
     * @return 是否支持
     */
    public boolean supports(FileType fileType) {
        return extractors.stream()
                .anyMatch(e -> e.supports(fileType));
    }

    /**
     * 获取可用的提取器列表
     *
     * @return 提取器名称列表
     */
    public List<String> getAvailableExtractors() {
        return extractors.stream()
                .map(FileContentExtractor::getName)
                .toList();
    }
}
