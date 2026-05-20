package io.yunxi.platform.framework.pageagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 表单验证服务
 * 提供表单字段验证、数据格式检查、业务规则验证等功能
 */
@Slf4j
@Service
public class FormValidationService {
    
    // 常见验证规则
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$", Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "^1[3-9]\\d{9}$"
    );
    
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "^[1-9]\\d{5}(18|19|20)\\d{2}((0[1-9])|(1[0-2]))(([0-2][1-9])|10|20|30|31)\\d{3}[0-9Xx]$"
    );

    /**
     * 验证表单字段是否必填
     */
    public ValidationResult validateRequired(String fieldName, Object value, String selector) {
        ValidationResult result = new ValidationResult(fieldName, selector);
        
        if (value == null || 
            (value instanceof String && !StringUtils.hasText((String) value)) ||
            (value instanceof Collection && ((Collection<?>) value).isEmpty()) ||
            (value instanceof Map && ((Map<?, ?>) value).isEmpty())) {
            
            result.setValid(false);
            result.setErrorCode("FIELD_REQUIRED");
            result.setErrorMessage(fieldName + " 是必填字段");
            return result;
        }
        
        result.setValid(true);
        return result;
    }

    /**
     * 验证数值范围
     */
    public ValidationResult validateNumberRange(String fieldName, Double value, Double min, Double max, String selector) {
        ValidationResult result = new ValidationResult(fieldName, selector);
        
        if (value == null) {
            result.setValid(false);
            result.setErrorCode("FIELD_NULL");
            result.setErrorMessage(fieldName + " 不能为空");
            return result;
        }
        
        if (min != null && value < min) {
            result.setValid(false);
            result.setErrorCode("VALUE_TOO_SMALL");
            result.setErrorMessage(fieldName + " 不能小于 " + min);
            return result;
        }
        
        if (max != null && value > max) {
            result.setValid(false);
            result.setErrorCode("VALUE_TOO_LARGE");
            result.setErrorMessage(fieldName + " 不能大于 " + max);
            return result;
        }
        
        result.setValid(true);
        return result;
    }

    /**
     * 验证字符串长度
     */
    public ValidationResult validateStringLength(String fieldName, String value, Integer min, Integer max, String selector) {
        ValidationResult result = new ValidationResult(fieldName, selector);
        
        if (value == null) {
            result.setValid(false);
            result.setErrorCode("FIELD_NULL");
            result.setErrorMessage(fieldName + " 不能为空");
            return result;
        }
        
        int length = value.length();
        
        if (min != null && length < min) {
            result.setValid(false);
            result.setErrorCode("LENGTH_TOO_SHORT");
            result.setErrorMessage(fieldName + " 长度不能小于 " + min + " 个字符");
            return result;
        }
        
        if (max != null && length > max) {
            result.setValid(false);
            result.setErrorCode("LENGTH_TOO_LONG");
            result.setErrorMessage(fieldName + " 长度不能超过 " + max + " 个字符");
            return result;
        }
        
        result.setValid(true);
        return result;
    }

    /**
     * 验证邮箱格式
     */
    public ValidationResult validateEmail(String fieldName, String value, String selector) {
        ValidationResult result = new ValidationResult(fieldName, selector);
        
        if (value == null || !StringUtils.hasText(value)) {
            result.setValid(false);
            result.setErrorCode("EMAIL_EMPTY");
            result.setErrorMessage(fieldName + " 不能为空");
            return result;
        }
        
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            result.setValid(false);
            result.setErrorCode("EMAIL_INVALID");
            result.setErrorMessage(fieldName + " 格式不正确");
            return result;
        }
        
        result.setValid(true);
        return result;
    }

    /**
     * 验证手机号格式
     */
    public ValidationResult validatePhone(String fieldName, String value, String selector) {
        ValidationResult result = new ValidationResult(fieldName, selector);
        
        if (value == null || !StringUtils.hasText(value)) {
            result.setValid(false);
            result.setErrorCode("PHONE_EMPTY");
            result.setErrorMessage(fieldName + " 不能为空");
            return result;
        }
        
        if (!PHONE_PATTERN.matcher(value).matches()) {
            result.setValid(false);
            result.setErrorCode("PHONE_INVALID");
            result.setErrorMessage(fieldName + " 格式不正确");
            return result;
        }
        
        result.setValid(true);
        return result;
    }

    /**
     * 批量验证表单字段
     */
    public List<ValidationResult> validateForm(Map<String, FormField> formFields) {
        List<ValidationResult> results = new ArrayList<>();
        
        for (FormField field : formFields.values()) {
            ValidationResult result = validateField(field);
            results.add(result);
        }
        
        return results;
    }

    /**
     * 验证单个表单字段
     */
    private ValidationResult validateField(FormField field) {
        switch (field.getFieldType()) {
            case "email":
                return validateEmail(field.getFieldName(), field.getValue() != null ? field.getValue().toString() : null, field.getSelector());
            case "phone":
                return validatePhone(field.getFieldName(), field.getValue() != null ? field.getValue().toString() : null, field.getSelector());
            case "number":
                return validateNumberRange(field.getFieldName(), 
                    field.getValue() != null ? Double.parseDouble(field.getValue().toString()) : null,
                    field.getMinValue(), field.getMaxValue(), field.getSelector());
            case "text":
                return validateStringLength(field.getFieldName(), 
                    field.getValue() != null ? field.getValue().toString() : null,
                    field.getMinLength(), field.getMaxLength(), field.getSelector());
            default:
                return validateRequired(field.getFieldName(), field.getValue(), field.getSelector());
        }
    }

    /**
     * 检查表单验证结果是否有错误
     */
    public boolean hasErrors(List<ValidationResult> results) {
        return results.stream().anyMatch(result -> !result.isValid());
    }

    /**
     * 获取所有错误信息
     */
    public List<String> getErrorMessages(List<ValidationResult> results) {
        return results.stream()
            .filter(result -> !result.isValid())
            .map(ValidationResult::getErrorMessage)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * 验证结果类
     */
    public static class ValidationResult {
        private String fieldName;
        private String selector;
        private boolean valid;
        private String errorCode;
        private String errorMessage;

        public ValidationResult(String fieldName, String selector) {
            this.fieldName = fieldName;
            this.selector = selector;
            this.valid = true;
        }

        // getter and setter methods
        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        
        public String getSelector() { return selector; }
        public void setSelector(String selector) { this.selector = selector; }
        
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    /**
     * 表单字段定义类
     */
    public static class FormField {
        private String fieldName;
        private String selector;
        private String fieldType;
        private Object value;
        private Integer minLength;
        private Integer maxLength;
        private Double minValue;
        private Double maxValue;
        private boolean required;

        public FormField(String fieldName, String selector, String fieldType) {
            this.fieldName = fieldName;
            this.selector = selector;
            this.fieldType = fieldType;
        }

        // getter and setter methods
        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        
        public String getSelector() { return selector; }
        public void setSelector(String selector) { this.selector = selector; }
        
        public String getFieldType() { return fieldType; }
        public void setFieldType(String fieldType) { this.fieldType = fieldType; }
        
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        
        public Integer getMinLength() { return minLength; }
        public void setMinLength(Integer minLength) { this.minLength = minLength; }
        
        public Integer getMaxLength() { return maxLength; }
        public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
        
        public Double getMinValue() { return minValue; }
        public void setMinValue(Double minValue) { this.minValue = minValue; }
        
        public Double getMaxValue() { return maxValue; }
        public void setMaxValue(Double maxValue) { this.maxValue = maxValue; }
        
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
    }
}