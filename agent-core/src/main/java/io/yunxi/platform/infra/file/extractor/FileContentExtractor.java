package io.yunxi.platform.infra.file.extractor;

import io.yunxi.platform.infra.file.FileType;
import io.yunxi.platform.shared.entity.UserFileEntity;

/**
 * 文件内容提取服务接口
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface FileContentExtractor {

    /**
     * 提取文件内容
     *
     * @param file 文件信息
     * @return 提取的文本内容
     */
    String extract(UserFileEntity file) throws Exception;

    /**
     * 检查是否支持该文件类型
     *
     * @param fileType 文件类型
     * @return 是否支持
     */
    boolean supports(FileType fileType);

    /**
     * 获取提取器名称
     */
    String getName();
}
