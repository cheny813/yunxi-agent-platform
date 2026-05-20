package io.yunxi.platform.framework.pageagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 表单数据持久化服务
 * 提供表单数据的存储、检索和会话管理功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormDataPersistenceService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis键前缀
    private static final String FORM_SESSION_PREFIX = "form:session:";
    private static final String FORM_TEMPLATE_PREFIX = "form:template:";
    private static final String FORM_HISTORY_PREFIX = "form:history:";

    /**
     * 保存表单会话数据
     */
    public void saveFormSession(String sessionId, FormSessionData sessionData) {
        try {
            String key = FORM_SESSION_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, sessionData, 24, TimeUnit.HOURS); // 保存24小时
            log.info("表单会话数据已保存，会话ID: {}, 表单数: {}", sessionId, sessionData.getFormDataList().size());
        } catch (Exception e) {
            log.error("保存表单会话数据失败，会话ID: {}", sessionId, e);
            throw new RuntimeException("表单数据保存失败", e);
        }
    }

    /**
     * 获取表单会话数据
     */
    public FormSessionData getFormSession(String sessionId) {
        try {
            String key = FORM_SESSION_PREFIX + sessionId;
            FormSessionData sessionData = (FormSessionData) redisTemplate.opsForValue().get(key);
            if (sessionData != null) {
                // 更新过期时间
                redisTemplate.expire(key, 24, TimeUnit.HOURS);
            }
            return sessionData;
        } catch (Exception e) {
            log.error("获取表单会话数据失败，会话ID: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 更新表单数据
     */
    public void updateFormData(String sessionId, String formId, Map<String, Object> formData) {
        FormSessionData sessionData = getFormSession(sessionId);
        if (sessionData == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        List<FormData> formDataList = sessionData.getFormDataList();
        Optional<FormData> existingForm = formDataList.stream()
            .filter(data -> formId.equals(data.getFormId()))
            .findFirst();

        if (existingForm.isPresent()) {
            // 更新现有表单数据
            FormData form = existingForm.get();
            form.setData(formData);
            form.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        } else {
            // 创建新的表单数据
            FormData newForm = FormData.builder()
                .formId(formId)
                .data(formData)
                .createdAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .updatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .build();
            formDataList.add(newForm);
        }

        saveFormSession(sessionId, sessionData);
    }

    /**
     * 获取特定表单的数据
     */
    public Map<String, Object> getFormData(String sessionId, String formId) {
        FormSessionData sessionData = getFormSession(sessionId);
        if (sessionData == null) {
            return new HashMap<>();
        }

        return sessionData.getFormDataList().stream()
            .filter(data -> formId.equals(data.getFormId()))
            .findFirst()
            .map(FormData::getData)
            .orElse(new HashMap<>());
    }

    /**
     * 保存表单模板
     */
    public void saveFormTemplate(String templateId, FormTemplate template) {
        try {
            String key = FORM_TEMPLATE_PREFIX + templateId;
            redisTemplate.opsForValue().set(key, template); // 模板不过期
            log.info("表单模板已保存，模板ID: {}", templateId);
        } catch (Exception e) {
            log.error("保存表单模板失败，模板ID: {}", templateId, e);
            throw new RuntimeException("表单模板保存失败", e);
        }
    }

    /**
     * 获取表单模板
     */
    public FormTemplate getFormTemplate(String templateId) {
        try {
            String key = FORM_TEMPLATE_PREFIX + templateId;
            return (FormTemplate) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("获取表单模板失败，模板ID: {}", templateId, e);
            return null;
        }
    }

    /**
     * 保存表单提交历史
     */
    public void saveFormHistory(String userId, FormHistory history) {
        try {
            String key = FORM_HISTORY_PREFIX + userId + ":" + history.getSessionId();
            redisTemplate.opsForValue().set(key, history, 30, TimeUnit.DAYS); // 保存30天历史
        } catch (Exception e) {
            log.error("保存表单历史失败，用户ID: {}", userId, e);
        }
    }

    /**
     * 获取用户表单历史
     */
    public List<FormHistory> getFormHistory(String userId, int limit) {
        try {
            String pattern = FORM_HISTORY_PREFIX + userId + ":*";
            Set<String> keys = redisTemplate.keys(pattern);
            
            if (keys == null || keys.isEmpty()) {
                return new ArrayList<>();
            }

            List<FormHistory> histories = new ArrayList<>();
            for (String key : keys) {
                FormHistory history = (FormHistory) redisTemplate.opsForValue().get(key);
                if (history != null) {
                    histories.add(history);
                }
            }

            // 按时间倒序排序，取最新的记录
            histories.sort((h1, h2) -> h2.getSubmittedAt().compareTo(h1.getSubmittedAt()));
            
            return histories.stream()
                .limit(limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        } catch (Exception e) {
            log.error("获取表单历史失败，用户ID: {}", userId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 清理过期会话数据
     */
    public void cleanupExpiredSessions() {
        try {
            Set<String> sessionKeys = redisTemplate.keys(FORM_SESSION_PREFIX + "*");
            if (sessionKeys != null) {
                long deleted = sessionKeys.stream()
                    .filter(key -> redisTemplate.getExpire(key) <= 0)
                    .peek(redisTemplate::delete)
                    .count();
                log.info("已清理 {} 个过期表单会话", deleted);
            }
        } catch (Exception e) {
            log.error("清理过期会话数据失败", e);
        }
    }

    /**
     * 表单会话数据类
     */
    public static class FormSessionData {
        private String sessionId;
        private String userId;
        private LocalDateTime createdAt;
        private LocalDateTime lastAccessed;
        private List<FormData> formDataList = new ArrayList<>();

        // builder and getter methods
        public static FormSessionDataBuilder builder() {
            return new FormSessionDataBuilder();
        }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        
        public LocalDateTime getLastAccessed() { return lastAccessed; }
        public void setLastAccessed(LocalDateTime lastAccessed) { this.lastAccessed = lastAccessed; }
        
        public List<FormData> getFormDataList() { return formDataList; }
        public void setFormDataList(List<FormData> formDataList) { this.formDataList = formDataList; }
    }

    public static class FormSessionDataBuilder {
        private String sessionId;
        private String userId;
        private LocalDateTime createdAt;
        private LocalDateTime lastAccessed;
        private List<FormData> formDataList;

        public FormSessionDataBuilder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public FormSessionDataBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public FormSessionDataBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public FormSessionDataBuilder lastAccessed(LocalDateTime lastAccessed) {
            this.lastAccessed = lastAccessed;
            return this;
        }

        public FormSessionDataBuilder formDataList(List<FormData> formDataList) {
            this.formDataList = formDataList;
            return this;
        }

        public FormSessionData build() {
            FormSessionData data = new FormSessionData();
            data.setSessionId(sessionId);
            data.setUserId(userId);
            data.setCreatedAt(createdAt != null ? createdAt : LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            data.setLastAccessed(lastAccessed != null ? lastAccessed : LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            data.setFormDataList(formDataList != null ? formDataList : new ArrayList<>());
            return data;
        }
    }

    /**
     * 表单数据类
     */
    public static class FormData {
        private String formId;
        private Map<String, Object> data = new HashMap<>();
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static FormDataBuilder builder() {
            return new FormDataBuilder();
        }

        public String getFormId() { return formId; }
        public void setFormId(String formId) { this.formId = formId; }
        
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
        
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    public static class FormDataBuilder {
        private String formId;
        private Map<String, Object> data;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public FormDataBuilder formId(String formId) {
            this.formId = formId;
            return this;
        }

        public FormDataBuilder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        public FormDataBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public FormDataBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public FormData build() {
            FormData data = new FormData();
            data.setFormId(formId);
            data.setData(this.data != null ? this.data : new HashMap<>());
            data.setCreatedAt(createdAt != null ? createdAt : LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            data.setUpdatedAt(updatedAt != null ? updatedAt : LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            return data;
        }
    }

    /**
     * 表单模板类
     */
    public static class FormTemplate {
        private String templateId;
        private String name;
        private String description;
        private Map<String, Object> fields = new HashMap<>();
        private List<FormValidationService.ValidationResult> validationRules = new ArrayList<>();

        // getter and setter methods
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public Map<String, Object> getFields() { return fields; }
        public void setFields(Map<String, Object> fields) { this.fields = fields; }
        
        public List<FormValidationService.ValidationResult> getValidationRules() { return validationRules; }
        public void setValidationRules(List<FormValidationService.ValidationResult> validationRules) { this.validationRules = validationRules; }
    }

    /**
     * 表单历史类
     */
    public static class FormHistory {
        private String sessionId;
        private String formId;
        private String userId;
        private LocalDateTime submittedAt;
        private Map<String, Object> submittedData = new HashMap<>();

        // getter and setter methods
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public String getFormId() { return formId; }
        public void setFormId(String formId) { this.formId = formId; }
        
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        
        public LocalDateTime getSubmittedAt() { return submittedAt; }
        public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
        
        public Map<String, Object> getSubmittedData() { return submittedData; }
        public void setSubmittedData(Map<String, Object> submittedData) { this.submittedData = submittedData; }
    }
}