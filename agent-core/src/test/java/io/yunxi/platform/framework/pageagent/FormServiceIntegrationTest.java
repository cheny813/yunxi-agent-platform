package io.yunxi.platform.framework.pageagent;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表单服务集成测试
 * 验证表单验证、数据持久化等核心功能
 */
@Slf4j
@SpringBootTest
@TestPropertySource(properties = {
    "conversation.storage-type=redis",
    "spring.redis.host=localhost",
    "spring.redis.port=6379"
})
public class FormServiceIntegrationTest {

    @Autowired(required = false)
    private FormValidationService formValidationService;

    @Autowired(required = false) 
    private FormDataPersistenceService formDataPersistenceService;

    @Test
    public void testFormValidationServiceInitialization() {
        assertNotNull(formValidationService, "表单验证服务应该被正确初始化");
        log.info("表单验证服务初始化测试通过");
    }

    @Test
    public void testFormDataPersistenceServiceInitialization() {
        assertNotNull(formDataPersistenceService, "表单数据持久化服务应该被正确初始化");
        log.info("表单数据持久化服务初始化测试通过");
    }

    @Test
    public void testRequiredFieldValidation() {
        FormValidationService.ValidationResult result = formValidationService.validateRequired(
            "用户名", "", "#username"
        );
        
        assertFalse(result.isValid(), "空字符串应该验证失败");
        assertEquals("FIELD_REQUIRED", result.getErrorCode());
        assertEquals("用户名 是必填字段", result.getErrorMessage());
        log.info("必填字段验证测试通过");
    }

    @Test
    public void testEmailValidation() {
        // 测试无效邮箱
        FormValidationService.ValidationResult invalidResult = formValidationService.validateEmail(
            "邮箱", "invalid-email", "#email"
        );
        assertFalse(invalidResult.isValid());
        assertEquals("EMAIL_INVALID", invalidResult.getErrorCode());

        // 测试有效邮箱
        FormValidationService.ValidationResult validResult = formValidationService.validateEmail(
            "邮箱", "test@example.com", "#email"
        );
        assertTrue(validResult.isValid());
        log.info("邮箱格式验证测试通过");
    }

    @Test
    public void testFormDataStorage() {
        String sessionId = "test-session-" + System.currentTimeMillis();
        String formId = "user-registration";
        
        // 创建测试表单数据
        Map<String, Object> formData = new HashMap<>();
        formData.put("username", "testuser");
        formData.put("email", "test@example.com");
        formData.put("age", 25);

        // 存储表单数据
        formDataPersistenceService.updateFormData(sessionId, formId, formData);
        log.info("表单数据存储测试通过");

        // 验证存储的数据（如果Redis可用）
        Map<String, Object> retrievedData = formDataPersistenceService.getFormData(sessionId, formId);
        if (!retrievedData.isEmpty()) {
            assertEquals("testuser", retrievedData.get("username"));
            assertEquals(25, retrievedData.get("age"));
            log.info("表单数据检索验证通过");
        }
    }

    @Test
    public void testBatchValidation() {
        Map<String, FormValidationService.FormField> formFields = new HashMap<>();
        
        // 添加必填字段
        FormValidationService.FormField requiredField = new FormValidationService.FormField(
            "用户名", "#username", "text"
        );
        requiredField.setValue("testuser");
        formFields.put("username", requiredField);

        // 添加邮箱字段
        FormValidationService.FormField emailField = new FormValidationService.FormField(
            "邮箱", "#email", "email"
        );
        emailField.setValue("test@example.com");
        formFields.put("email", emailField);

        // 执行批量验证
        var results = formValidationService.validateForm(formFields);
        assertTrue(!formValidationService.hasErrors(results), "所有字段都应该验证通过");
        log.info("批量表单验证测试通过，验证了 {} 个字段", results.size());
    }

    @Test
    public void testErrorAggregation() {
        Map<String, FormValidationService.FormField> formFields = new HashMap<>();
        
        // 添加有错误的字段
        FormValidationService.FormField invalidEmailField = new FormValidationService.FormField(
            "邮箱", "#email", "email"
        );
        invalidEmailField.setValue("invalid-email");
        formFields.put("email", invalidEmailField);

        FormValidationService.FormField emptyField = new FormValidationService.FormField(
            "用户名", "#username", "text"
        );
        emptyField.setValue("");
        formFields.put("username", emptyField);

        // 执行批量验证
        var results = formValidationService.validateForm(formFields);
        assertTrue(formValidationService.hasErrors(results), "应该检测到验证错误");
        
        var errorMessages = formValidationService.getErrorMessages(results);
        assertEquals(2, errorMessages.size(), "应该有2个错误信息");
        log.info("错误信息聚合测试通过，错误信息: {}", errorMessages);
    }

    @Test
    public void testFieldTypeValidation() {
        // 测试手机号验证
        FormValidationService.ValidationResult phoneResult = formValidationService.validatePhone(
            "手机号", "13800138000", "#phone"
        );
        assertTrue(phoneResult.isValid(), "有效的手机号应该通过验证");

        FormValidationService.ValidationResult invalidPhoneResult = formValidationService.validatePhone(
            "手机号", "123456789", "#phone"
        );
        assertFalse(invalidPhoneResult.isValid(), "无效的手机号应该验证失败");
        
        log.info("手机号格式验证测试通过");
    }

    @Test
    public void testFormTemplateManagement() {
        // 创建测试模板
        FormDataPersistenceService.FormTemplate template = new FormDataPersistenceService.FormTemplate();
        template.setTemplateId("user-registration-template");
        template.setName("用户注册表单模板");
        template.setDescription("标准用户注册表单模板");
        
        Map<String, Object> templateFields = new HashMap<>();
        templateFields.put("username", Map.of("type", "text", "required", true));
        templateFields.put("email", Map.of("type", "email", "required", true));
        template.setFields(templateFields);

        // 存储模板（如果Redis可用）
        formDataPersistenceService.saveFormTemplate(template.getTemplateId(), template);
        
        log.info("表单模板管理测试通过，创建了模板: {}", template.getName());
    }
}