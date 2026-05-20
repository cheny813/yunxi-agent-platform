package io.yunxi.platform.infra.file.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.infra.file.dto.ImageFeatureResult;
import io.yunxi.platform.infra.file.storage.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.*;

/**
 * 人脸特征提取器
 *
 * <p>
 * 专门用于人脸识别场景，使用人脸特征提取模型（如FaceNet、ArcFace）
 * 提取人脸特征向量，用于人脸搜索、人脸比对等场景
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "file-upload.face-recognition.enabled", havingValue = "true", matchIfMissing = false)
public class FaceFeatureExtractor implements ImageFeatureExtractor {

    /** 人脸识别服务提供商 */
    @Value("${file-upload.face-recognition.provider:local}")
    private String faceProvider;

    /** 人脸识别服务端点 */
    @Value("${file-upload.face-recognition.endpoint:http://localhost:8001/face/embed}")
    private String faceEndpoint;

    /** API Key */
    @Value("${file-upload.face-recognition.api-key:}")
    private String apiKey;

    /** 人脸识别模型名称 */
    @Value("${file-upload.face-recognition.model:arcface-r50}")
    private String modelName;

    /** 特征向量维度 */
    @Value("${file-upload.face-recognition.dimension:512}")
    private int featureDimension;

    /** 最小置信度阈值 */
    @Value("${file-upload.face-recognition.min-confidence:0.8}")
    private float minConfidence;

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
    @Autowired
    public FaceFeatureExtractor(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ImageFeatureResult extract(UserFileEntity file) throws Exception {
        log.info("开始提取人脸特征: fileId={}, fileName={}", file.getId(), file.getFileName());

        try {
            // 1. 读取图像文件
            byte[] imageBytes;
            try (InputStream input = fileStorageService.get(file.getFilePath())) {
                imageBytes = input.readAllBytes();
            }

            // 2. 转换为Base64
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

            // 3. 调用人脸特征提取服务
            FaceExtractionResult extractionResult = callFaceService(imageBase64);

            // 4. 检查人脸检测置信度
            if (extractionResult.confidence < minConfidence) {
                log.warn("人脸检测置信度过低: fileId={}, confidence={}, minConfidence={}",
                        file.getId(), extractionResult.confidence, minConfidence);
                throw new Exception("人脸检测置信度过低，可能不是有效的人脸图片");
            }

            // 5. 构建结果
            ImageFeatureResult result = ImageFeatureResult.builder()
                    .featureVector(extractionResult.featureVector)
                    .dimension(extractionResult.featureVector.size())
                    .modelName(modelName)
                    .imageType("face")
                    .confidence(extractionResult.confidence)
                    .metadata(extractionResult.metadata)
                    .build();

            log.info("人脸特征提取成功: fileId={}, dimension={}, confidence={}",
                    file.getId(), extractionResult.featureVector.size(), extractionResult.confidence);

            return result;

        } catch (Exception e) {
            log.error("人脸特征提取失败: fileId={}", file.getId(), e);
            throw e;
        }
    }

    /**
     * 调用人脸特征提取服务
     */
    private FaceExtractionResult callFaceService(String imageBase64) throws Exception {
        try {
            // 构建请求
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("image", imageBase64);
            requestBody.put("model", modelName);
            requestBody.put("detect_face", true); // 启用人脸检测

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.set("Authorization", "Bearer " + apiKey);
            }

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                    faceEndpoint, HttpMethod.POST, entity, String.class);

            // 解析结果
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseFaceResponse(response.getBody());
            } else {
                throw new RuntimeException("人脸特征提取请求失败: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("调用人脸特征提取服务失败: endpoint={}", faceEndpoint, e);
            // 如果服务不可用，抛出异常
            throw new Exception("人脸特征提取服务不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 解析人脸特征提取响应
     */
    private FaceExtractionResult parseFaceResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        FaceExtractionResult result = new FaceExtractionResult();

        // 解析特征向量
        if (root.has("embedding")) {
            result.featureVector = parseJsonArrayToFloatList(root.get("embedding"));
        } else if (root.has("feature_vector")) {
            result.featureVector = parseJsonArrayToFloatList(root.get("feature_vector"));
        } else {
            throw new RuntimeException("响应中未找到特征向量");
        }

        // 解析置信度
        if (root.has("confidence")) {
            result.confidence = (float) root.get("confidence").asDouble();
        } else if (root.has("face_confidence")) {
            result.confidence = (float) root.get("face_confidence").asDouble();
        } else {
            result.confidence = 1.0f; // 默认置信度
        }

        // 解析元数据
        result.metadata = new HashMap<>();
        if (root.has("face_location")) {
            result.metadata.put("faceLocation", root.get("face_location").toString());
        }
        if (root.has("face_count")) {
            result.metadata.put("faceCount", root.get("face_count").asInt());
        }
        if (root.has("landmarks")) {
            result.metadata.put("landmarks", root.get("landmarks").toString());
        }

        return result;
    }

    /**
     * 解析JSON数组为Float列表
     */
    private List<Float> parseJsonArrayToFloatList(JsonNode array) {
        List<Float> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                result.add((float) node.asDouble());
            }
        }
        return result;
    }

    @Override
    public boolean supports(String imageType) {
        return "face".equalsIgnoreCase(imageType);
    }

    @Override
    public String getName() {
        return "FaceFeatureExtractor-" + faceProvider;
    }

    @Override
    public int getFeatureDimension() {
        return featureDimension;
    }

    /**
     * 人脸提取结果内部类
     */
    private static class FaceExtractionResult {
        List<Float> featureVector;
        float confidence;
        Map<String, Object> metadata;
    }
}
