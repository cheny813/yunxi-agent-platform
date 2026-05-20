package io.yunxi.platform.infra.file.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.infra.file.FileType;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.infra.file.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.*;

/**
 * OCR图片内容提取器
 *
 * <p>
 * 使用OCR技术从图片中提取文字内容
 * 支持的OCR服务：阿里云OCR、百度OCR
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(name = "file-upload.ocr.enabled", havingValue = "true", matchIfMissing = false)
public class OcrContentExtractor implements FileContentExtractor {
    
    private static final Logger log = LoggerFactory.getLogger(OcrContentExtractor.class);

    /** OCR 服务提供商 */
    @Value("${file-upload.ocr.provider:aliyun}")
    private String ocrProvider;

    /** 阿里云 Access Key ID */
    @Value("${aliyun.ocr.access-key-id:}")
    private String aliyunAccessKeyId;

    /** 阿里云 Access Key Secret */
    @Value("${aliyun.ocr.access-key-secret:}")
    private String aliyunAccessKeySecret;

    /** 阿里云 OCR 端点 */
    @Value("${aliyun.ocr.endpoint:https://ocr.cn-shanghai.aliyuncs.com}")
    private String aliyunOcrEndpoint;

    /** 百度 OCR API Key */
    @Value("${baidu.ocr.api-key:}")
    private String baiduApiKey;

    /** 百度 OCR Secret Key */
    @Value("${baidu.ocr.secret-key:}")
    private String baiduSecretKey;

    /** 百度 Access Token 缓存 */
    private String baiduAccessToken;
    /** 百度 Token 过期时间 */
    private long baiduTokenExpireTime;

    /** 文件存储服务 */
    private final FileStorageService fileStorageService;
    /** REST 模板 */
    private final RestTemplate restTemplate;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     *
     * @param fileStorageService 文件存储服务
     */
    public OcrContentExtractor(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String extract(UserFileEntity file) throws Exception {
        log.info("开始OCR识别: fileId={}, fileName={}", file.getId(), file.getFileName());

        try {
            String content;

            switch (ocrProvider.toLowerCase()) {
                case "aliyun":
                    content = extractWithAliyunOcr(file);
                    break;
                case "baidu":
                    content = extractWithBaiduOcr(file);
                    break;
                default:
                    throw new UnsupportedOperationException("不支持的OCR提供商: " + ocrProvider);
            }

            log.info("OCR识别成功: fileId={}, contentLength={}", file.getId(), content != null ? content.length() : 0);
            return content;

        } catch (Exception e) {
            log.error("OCR识别失败: fileId={}", file.getId(), e);
            throw e;
        }
    }

    /**
     * 使用阿里云OCR提取文字
     * 
     * API文档: https://help.aliyun.com/document_detail/442274.html
     */
    private String extractWithAliyunOcr(UserFileEntity file) throws Exception {
        if (aliyunAccessKeyId == null || aliyunAccessKeyId.isEmpty()) {
            throw new IllegalStateException("阿里云OCR未配置，请设置 aliyun.ocr.access-key-id 和 aliyun.ocr.access-key-secret");
        }

        try {
            // 1. 读取图片文件
            byte[] imageBytes;
            try (InputStream input = fileStorageService.get(file.getFilePath())) {
                imageBytes = input.readAllBytes();
            }

            // 2. 将图片转为Base64
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

            // 3. 构建请求体
            Map<String, Object> requestBody = new HashMap<>();

            // 使用通用文字识别API
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("imageBase64", imageBase64);
            requestBody.put("input", inputData);

            // 4. 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + aliyunAccessKeySecret);
            headers.set("x-acs-action", "RecognizeGeneral");

            // 5. 发送请求
            String url = aliyunOcrEndpoint + "/?Action=RecognizeGeneral";

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            // 6. 解析结果
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseAliyunOcrResult(response.getBody());
            } else {
                throw new RuntimeException("OCR识别请求失败: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("阿里云OCR识别失败: fileId={}", file.getId(), e);
            throw new RuntimeException("OCR识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析阿里云OCR结果
     */
    private String parseAliyunOcrResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析返回的文字块
            StringBuilder content = new StringBuilder();

            // 阿里云OCR返回格式示例:
            // {"Data":{"Content":"识别的文字内容"}}
            JsonNode dataNode = root.path("Data");
            if (dataNode.has("Content")) {
                content.append(dataNode.get("Content").asText());
            } else if (dataNode.has("BlockList")) {
                // 备选：解析文字块列表
                JsonNode blockList = dataNode.get("BlockList");
                if (blockList.isArray()) {
                    for (JsonNode block : blockList) {
                        if (block.has("Content")) {
                            content.append(block.get("Content").asText()).append("\n");
                        }
                    }
                }
            }

            String result = content.toString().trim();
            if (result.isEmpty()) {
                log.warn("OCR识别结果为空");
                return "[OCR识别完成，但未识别到文字内容]";
            }

            return result;

        } catch (Exception e) {
            log.error("解析OCR结果失败: {}", responseBody, e);
            return "[OCR识别失败，无法解析结果]";
        }
    }

    /**
     * 使用百度OCR提取文字
     *
     * API文档: https://cloud.baidu.com/doc/OCR/s/1k3h7y3db
     */
    private String extractWithBaiduOcr(UserFileEntity file) throws Exception {
        if (baiduApiKey == null || baiduApiKey.isEmpty() || baiduSecretKey == null || baiduSecretKey.isEmpty()) {
            throw new IllegalStateException("百度OCR未配置，请设置 baidu.ocr.api-key 和 baidu.ocr.secret-key");
        }

        try {
            // 1. 获取百度OCR Access Token
            String accessToken = getBaiduAccessToken();

            // 2. 读取图片文件并转为Base64
            byte[] imageBytes;
            try (InputStream input = fileStorageService.get(file.getFilePath())) {
                imageBytes = input.readAllBytes();
            }
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

            // 3. 调用百度OCR API (通用文字识别)
            String url = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic"
                    + "?access_token=" + accessToken;

            // 4. 构建请求
            Map<String, String> params = new HashMap<>();
            params.put("image", imageBase64);
            params.put("language_type", "CHN_ENG"); // 中英文混合

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            // 5. 解析返回结果
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseBaiduOcrResult(response.getBody());
            } else {
                throw new RuntimeException("百度OCR请求失败: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("百度OCR识别失败: fileId={}", file.getId(), e);
            throw new RuntimeException("百度OCR识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取百度OCR Access Token
     * 使用缓存机制避免频繁请求
     */
    private synchronized String getBaiduAccessToken() throws Exception {
        // 检查缓存是否有效
        if (baiduAccessToken != null && System.currentTimeMillis() < baiduTokenExpireTime) {
            return baiduAccessToken;
        }

        // 重新获取Token
        String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token"
                + "?grant_type=client_credentials"
                + "&client_id=" + baiduApiKey
                + "&client_secret=" + baiduSecretKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, entity, String.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            baiduAccessToken = root.get("access_token").asText();
            // Token有效期默认30天，提前5天刷新
            baiduTokenExpireTime = System.currentTimeMillis()
                    + (root.get("expires_in").asLong() - 5 * 24 * 3600) * 1000;
            return baiduAccessToken;
        } else {
            throw new RuntimeException("获取百度Access Token失败: " + response.getStatusCode());
        }
    }

    /**
     * 解析百度OCR结果
     */
    private String parseBaiduOcrResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查错误
            if (root.has("error_code")) {
                log.error("百度OCR返回错误: code={}, msg={}",
                        root.get("error_code").asText(), root.get("error_msg").asText());
                return "[百度OCR识别失败: " + root.get("error_msg").asText() + "]";
            }

            StringBuilder content = new StringBuilder();
            JsonNode wordsResult = root.get("words_result");

            if (wordsResult != null && wordsResult.isArray()) {
                for (JsonNode word : wordsResult) {
                    if (word.has("words")) {
                        content.append(word.get("words").asText()).append("\n");
                    }
                }
            }

            String result = content.toString().trim();
            if (result.isEmpty()) {
                log.warn("百度OCR识别结果为空");
                return "[OCR识别完成，但未识别到文字内容]";
            }

            return result;

        } catch (Exception e) {
            log.error("解析百度OCR结果失败: {}", responseBody, e);
            return "[OCR识别失败，无法解析结果]";
        }
    }

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.IMAGE;
    }

    @Override
    public String getName() {
        return "OcrExtractor-" + ocrProvider;
    }
}
