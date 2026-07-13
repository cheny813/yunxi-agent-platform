package io.yunxi.platform.file.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 本地内存音频文件服务（占位实现）
 *
 * <p>
 * 当未配置云存储服务时使用，仅用于开发测试。
 * 生产环境请配置阿里云OSS或华为云OBS。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
@ConditionalOnMissingBean(AudioFileService.class)
public class LocalAudioFileService implements AudioFileService {

    /**
     * 上传音频（本地模式占位实现，仅生成 ID 不实际存储）。
     *
     * @param audioData  音频数据
     * @param objectName 对象名称
     * @return 拼接本地 ID 的伪地址
     */
    @Override
    public String uploadAudio(byte[] audioData, String objectName) {
        // 本地模式：生成UUID返回，实际不存储
        String id = UUID.randomUUID().toString().substring(0, 8);
        log.warn("本地模式：音频文件仅生成ID，不实际存储。objectName={}, id={}", objectName, id);
        return objectName + "?localId=" + id;
    }

    /**
     * 获取音频访问 URL（本地模式无法生成，返回空串）。
     *
     * @param objectName     对象名称
     * @param expirySeconds  过期秒数（可变参数，本地模式忽略）
     * @return 空字符串
     */
    @Override
    public String getAudioUrl(String objectName, int... expirySeconds) {
        // 本地模式无法生成URL
        log.warn("本地模式：无法生成音频URL，返回空字符串");
        return "";
    }

    /**
     * 删除音频（本地模式占位，无实际效果）。
     *
     * @param objectName 对象名称
     */
    @Override
    public void deleteAudio(String objectName) {
        log.debug("本地模式：删除操作无实际效果 objectName={}", objectName);
    }

    /**
     * 判断音频是否存在（本地模式恒返回 true）。
     *
     * @param objectName 对象名称
     * @return 恒为 true
     */
    @Override
    public boolean exists(String objectName) {
        // 本地模式假设都存在
        return true;
    }

    /**
     * 返回服务类型标识。
     *
     * @return 固定返回 "local"
     */
    @Override
    public String getServiceType() {
        return "local";
    }
}
