package io.yunxi.platform.infra.file.service;

import java.util.Optional;

/**
 * 音频文件服务接口
 *
 * <p>支持多种对象存储服务：阿里云OSS、华为云OBS、MinIO等</p>
 *
 * @author yunxi-agent-platform
 */
public interface AudioFileService {

    /**
     * 上传音频文件
     *
     * @param audioData 音频数据
     * @param objectName 对象名称（建议格式：asr/{fileId}_{timestamp}.{ext}）
     * @return 生成的对象名称
     */
    String uploadAudio(byte[] audioData, String objectName);

    /**
     * 获取音频文件的访问URL
     *
     * @param objectName 对象名称
     * @param expirySeconds URL有效期（秒），默认3600秒
     * @return 访问URL
     */
    String getAudioUrl(String objectName, int... expirySeconds);

    /**
     * 删除音频文件
     *
     * @param objectName 对象名称
     */
    void deleteAudio(String objectName);

    /**
     * 检查对象是否存在
     *
     * @param objectName 对象名称
     * @return 是否存在
     */
    boolean exists(String objectName);

    /**
     * 获取服务类型
     *
     * @return 服务类型：aliyun-oss | huawei-obs | minio
     */
    String getServiceType();
}