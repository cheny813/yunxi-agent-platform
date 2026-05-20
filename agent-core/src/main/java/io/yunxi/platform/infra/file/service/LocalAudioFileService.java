package io.yunxi.platform.infra.file.service;

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

    @Override
    public String uploadAudio(byte[] audioData, String objectName) {
        // 本地模式：生成UUID返回，实际不存储
        String id = UUID.randomUUID().toString().substring(0, 8);
        log.warn("本地模式：音频文件仅生成ID，不实际存储。objectName={}, id={}", objectName, id);
        return objectName + "?localId=" + id;
    }

    @Override
    public String getAudioUrl(String objectName, int... expirySeconds) {
        // 本地模式无法生成URL
        log.warn("本地模式：无法生成音频URL，返回空字符串");
        return "";
    }

    @Override
    public void deleteAudio(String objectName) {
        log.debug("本地模式：删除操作无实际效果 objectName={}", objectName);
    }

    @Override
    public boolean exists(String objectName) {
        // 本地模式假设都存在
        return true;
    }

    @Override
    public String getServiceType() {
        return "local";
    }
}