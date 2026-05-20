package io.yunxi.platform.infra.file.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.infra.file.FileType;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.infra.file.storage.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ASR语音内容提取器
 *
 * <p>
 * 使用ASR技术从音频文件中提取文字内容
 * 支持的ASR服务：阿里云语音识别、百度语音识别
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "file-upload.asr.enabled", havingValue = "true", matchIfMissing = false)
public class AsrContentExtractor implements FileContentExtractor {

    /** ASR 服务提供商 */
    @Value("${file-upload.asr.provider:aliyun}")
    private String asrProvider;

    /** 阿里云 Access Key ID */
    @Value("${aliyun.asr.access-key-id:}")
    private String aliyunAccessKeyId;

    /** 阿里云 Access Key Secret */
    @Value("${aliyun.asr.access-key-secret:}")
    private String aliyunAccessKeySecret;

    /** 阿里云 App Key */
    @Value("${aliyun.asr.app-key:}")
    private String aliyunAppKey;

    /** 阿里云 ASR 端点 */
    @Value("${aliyun.asr.endpoint:https://nls-gateway.cn-shanghai.aliyuncs.com}")
    private String aliyunAsrEndpoint;

    /** 百度 ASR API Key */
    @Value("${baidu.asr.api-key:}")
    private String baiduApiKey;

    /** 百度 ASR Secret Key */
    @Value("${baidu.asr.secret-key:}")
    private String baiduSecretKey;

    /** Whisper 端点 */
    @Value("${whisper.endpoint:}")
    private String whisperEndpoint;

    /** Whisper API Key */
    @Value("${whisper.api-key:}")
    private String whisperApiKey;

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

    /** 音频文件服务 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.yunxi.platform.infra.file.service.AudioFileService audioFileService;

    /**
     * 构造函数
     *
     * @param fileStorageService 文件存储服务
     */
    public AsrContentExtractor(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String extract(UserFileEntity file) throws Exception {
        log.info("开始ASR语音识别: fileId={}, fileName={}", file.getId(), file.getFileName());

        try {
            String content;

            switch (asrProvider.toLowerCase()) {
                case "aliyun":
                    content = extractWithAliyunAsr(file);
                    break;
                case "baidu":
                    content = extractWithBaiduAsr(file);
                    break;
                case "whisper":
                    content = extractWithWhisper(file);
                    break;
                default:
                    throw new UnsupportedOperationException("不支持的ASR提供商: " + asrProvider);
            }

            log.info("ASR语音识别成功: fileId={}, contentLength={}", file.getId(), content != null ? content.length() : 0);
            return content;

        } catch (Exception e) {
            log.error("ASR语音识别失败: fileId={}", file.getId(), e);
            throw e;
        }
    }

    /**
     * 使用阿里云ASR提取文字
     * 
     * API文档: https://help.aliyun.com/document_detail/90727.html
     * 录音文件识别服务
     */
    private String extractWithAliyunAsr(UserFileEntity file) throws Exception {
        if (aliyunAccessKeyId == null || aliyunAccessKeyId.isEmpty()) {
            throw new IllegalStateException("阿里云ASR未配置，请设置 aliyun.asr.access-key-id、access-key-secret 和 app-key");
        }

        try {
            // 1. 读取音频文件
            byte[] audioBytes;
            try (InputStream input = fileStorageService.get(file.getFilePath())) {
                audioBytes = input.readAllBytes();
            }

            log.debug("音频文件大小: {} bytes", audioBytes.length);

            // 2. 方案A: 使用一句话识别API（适合短音频<60秒）
            if (audioBytes.length < 1024 * 1024 * 2) { // < 2MB
                return extractWithAliyunSentenceRecognition(audioBytes, file);
            }

            // 3. 方案B: 使用录音文件识别API（适合长音频）
            return extractWithAliyunFileRecognition(audioBytes, file);

        } catch (Exception e) {
            log.error("阿里云ASR识别失败: fileId={}", file.getId(), e);
            throw new RuntimeException("ASR识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用阿里云一句话识别API
     * 适合短音频（<60秒）
     */
    private String extractWithAliyunSentenceRecognition(byte[] audioBytes, UserFileEntity file) throws Exception {
        // 构建请求URL
        String url = aliyunAsrEndpoint + "/stream/v1/asr";

        // 构建请求参数
        String params = String.format(
                "?appkey=%s&format=%s&sample_rate=%d&enable_punctuation_prediction=%b&enable_inverse_text_normalization=%b",
                aliyunAppKey,
                getAudioFormat(file.getFileName()),
                16000,
                true,
                true);

        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/octet-stream"));
        headers.set("X-NLS-Token", generateToken());

        // 发送请求
        HttpEntity<byte[]> entity = new HttpEntity<>(audioBytes, headers);
        ResponseEntity<String> response = restTemplate.exchange(url + params, HttpMethod.POST, entity, String.class);

        // 解析结果
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return parseAliyunAsrResult(response.getBody());
        } else {
            throw new RuntimeException("ASR识别请求失败: " + response.getStatusCode());
        }
    }

    /**
     * 使用阿里云录音文件识别API
     * 适合长音频
     * API文档: https://help.aliyun.com/document_detail/90727.html
     */
    private String extractWithAliyunFileRecognition(byte[] audioBytes, UserFileEntity file) throws Exception {
        // 长音频识别需要先将音频上传到OSS，然后使用录音文件识别API
        // 这里提供两种方案：
        // 方案1: 使用阿里云OSS + 录音文件识别API（需要配置OSS）
        // 方案2: 分片处理（将长音频分段处理）

        // 检查是否配置了OSS
        String ossEndpoint = System.getenv("ALIYUN_OSS_ENDPOINT");
        String ossBucket = System.getenv("ALIYUN_OSS_BUCKET");

        if (ossEndpoint != null && ossBucket != null) {
            return extractWithAliyunFileRecognitionViaOSS(audioBytes, file, ossEndpoint, ossBucket);
        } else {
            // 降级为分片处理方案
            log.warn("未配置OSS，长音频采用分片处理方案");
            return extractWithAliyunFileRecognitionByChunks(audioBytes, file);
        }
    }

    /**
     * 通过OSS进行长音频识别
     */
    private String extractWithAliyunFileRecognitionViaOSS(byte[] audioBytes, UserFileEntity file,
            String ossEndpoint, String ossBucket) throws Exception {
        if (audioFileService == null) {
            log.warn("未配置音频文件服务，降级为分片处理方案");
            return extractWithAliyunFileRecognitionByChunks(audioBytes, file);
        }

        // 1. 生成唯一的OSS对象名称
        String ossObjectName = "asr/" + file.getId() + "_" + System.currentTimeMillis() + "."
                + getAudioFormat(file.getFileName());

        // 2. 上传到OSS
        log.info("上传音频到OSS: objectName={}, size={}", ossObjectName, audioBytes.length);
        audioFileService.uploadAudio(audioBytes, ossObjectName);

        // 3. 获取音频URL
        String audioUrl = audioFileService.getAudioUrl(ossObjectName, 3600);
        log.debug("音频URL: {}", audioUrl);

        // 4. 调用阿里云录音文件识别API
        String taskId = submitFileRecognitionTask(audioUrl);

        // 5. 轮询获取识别结果
        return waitForRecognitionResult(taskId);
    }

    /**
     * 提交录音文件识别任务
     */
    private String submitFileRecognitionTask(String audioUrl) throws Exception {
        String token = generateToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("appkey", aliyunAppKey);
        requestBody.put("file_url", audioUrl);
        requestBody.put("format", "mp3");
        requestBody.put("language", "zh-CN");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                aliyunAsrEndpoint + "/stream/v1/asr/file_recognize",
                entity,
                String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        String taskId = root.path("task_id").asText();

        log.info("提交识别任务成功: taskId={}", taskId);
        return taskId;
    }

    /**
     * 轮询获取识别结果
     */
    private String waitForRecognitionResult(String taskId) throws Exception {
        String token = generateToken();
        int maxRetries = 30;
        int retryInterval = 2000; // 2秒

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(retryInterval);

            String url = aliyunAsrEndpoint + "/stream/v1/asr/recognize_status?task_id=" + taskId;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String status = root.path("status").asText();

            log.debug("识别状态: taskId={}, status={}", taskId, status);

            if ("success".equals(status)) {
                String result = root.path("result").asText();
                log.info("识别完成: taskId={}", taskId);
                return parseAliyunAsrResult(result);
            } else if ("failed".equals(status)) {
                throw new RuntimeException("识别失败: " + root.path("error_message").asText());
            }
        }

        throw new RuntimeException("识别超时: taskId=" + taskId);
    }

    /**
     * 通过分片处理进行长音频识别
     * 将长音频分割为多个短片段，分别识别后合并结果
     */
    private String extractWithAliyunFileRecognitionByChunks(byte[] audioBytes, UserFileEntity file) throws Exception {
        // 简单实现：按固定大小分片
        // 实际生产环境应按时间戳分片，保证语义完整性
        int maxChunkSize = 1024 * 1024; // 1MB 每个片段
        int numChunks = (int) Math.ceil((double) audioBytes.length / maxChunkSize);

        log.info("长音频分片处理: totalSize={}, chunks={}", audioBytes.length, numChunks);

        StringBuilder fullText = new StringBuilder();

        for (int i = 0; i < numChunks; i++) {
            int start = i * maxChunkSize;
            int end = Math.min(start + maxChunkSize, audioBytes.length);
            byte[] chunk = Arrays.copyOfRange(audioBytes, start, end);

            try {
                String chunkText = extractWithAliyunSentenceRecognition(chunk, file);
                if (chunkText != null && !chunkText.startsWith("[ASR")) {
                    fullText.append(chunkText).append(" ");
                }
            } catch (Exception e) {
                log.warn("分片识别失败: chunk={}, error={}", i, e.getMessage());
            }

            // 添加小延迟避免请求过快
            if (i < numChunks - 1) {
                Thread.sleep(100);
            }
        }

        String result = fullText.toString().trim();
        if (result.isEmpty()) {
            return "[长音频识别失败，所有分片均未成功识别]";
        }

        return result;
    }

    /**
     * 解析阿里云ASR结果
     */
    private String parseAliyunAsrResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 一句话识别返回格式示例:
            // {"status":20000000,"message":"SUCCESS","result":{"sentence":"识别的文字内容"}}
            if (root.has("result")) {
                JsonNode resultNode = root.get("result");
                if (resultNode.has("sentence")) {
                    return resultNode.get("sentence").asText();
                }
            }

            // 检查错误
            if (root.has("status") && root.get("status").asInt() != 20000000) {
                String message = root.has("message") ? root.get("message").asText() : "未知错误";
                throw new RuntimeException("ASR识别失败: " + message);
            }

            log.warn("ASR识别结果为空: {}", responseBody);
            return "[ASR识别完成，但未识别到语音内容]";

        } catch (Exception e) {
            log.error("解析ASR结果失败: {}", responseBody, e);
            return "[ASR识别失败，无法解析结果]";
        }
    }

    /**
     * 生成阿里云API Token
     * 使用AccessKey签名生成Token
     */
    private String generateToken() {
        try {
            // 使用HMAC-SHA1签名
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signStr = aliyunAccessKeyId + "\n" + timestamp;

            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secretKeySpec = new SecretKeySpec(aliyunAccessKeySecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA1");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(signStr.getBytes(StandardCharsets.UTF_8));

            String signature = Base64.getEncoder().encodeToString(hash);
            return URLEncoder.encode(signature, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("生成Token失败", e);
            return aliyunAccessKeySecret; // 降级处理
        }
    }

    /**
     * 获取音频格式
     */
    private String getAudioFormat(String fileName) {
        if (fileName == null)
            return "pcm";
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".mp3"))
            return "mp3";
        if (lowerName.endsWith(".wav"))
            return "wav";
        if (lowerName.endsWith(".m4a"))
            return "m4a";
        if (lowerName.endsWith(".aac"))
            return "aac";
        return "pcm";
    }

    /**
     * 使用百度ASR提取文字
     * API文档: https://ai.baidu.com/tech/speech/asr
     */
    private String extractWithBaiduAsr(UserFileEntity file) throws Exception {
        if (baiduApiKey == null || baiduApiKey.isEmpty() || baiduSecretKey == null || baiduSecretKey.isEmpty()) {
            throw new IllegalStateException("百度ASR未配置，请设置 baidu.asr.api-key 和 baidu.asr.secret-key");
        }

        try {
            // 1. 获取百度语音识别 Access Token
            String accessToken = getBaiduAccessToken();

            // 2. 读取音频文件并转为Base64
            byte[] audioBytes;
            try (InputStream input = fileStorageService.get(file.getFilePath())) {
                audioBytes = input.readAllBytes();
            }
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);

            // 3. 调用百度语音识别API
            String url = "https://vop.baidu.com/server_api"
                    + "?dev_pid=1537" // 普通话输入法模型
                    + "&cuid=yunxi_agent"
                    + "&token=" + accessToken;

            // 构建请求体（支持pcm, wav, amr, ogg等多种格式）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("format", getAudioFormat(file.getFileName()));
            requestBody.put("rate", 16000); // 采样率
            requestBody.put("channel", 1); // 声道数
            requestBody.put("speech", audioBase64);
            requestBody.put("len", audioBytes.length);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            // 4. 解析返回结果
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseBaiduAsrResult(response.getBody());
            } else {
                throw new RuntimeException("百度ASR请求失败: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("百度ASR识别失败: fileId={}", file.getId(), e);
            throw new RuntimeException("百度ASR识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取百度ASR Access Token
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
     * 解析百度ASR结果
     */
    private String parseBaiduAsrResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查错误
            if (root.has("err_no")) {
                int errNo = root.get("err_no").asInt();
                if (errNo != 0) {
                    String errMsg = root.has("err_msg") ? root.get("err_msg").asText() : "未知错误";
                    log.error("百度ASR返回错误: err_no={}, err_msg={}", errNo, errMsg);
                    return "[百度ASR识别失败: " + errMsg + "]";
                }
            }

            // 解析识别结果
            StringBuilder content = new StringBuilder();
            JsonNode result = root.get("result");

            if (result != null && result.isArray()) {
                for (JsonNode item : result) {
                    content.append(item.asText());
                }
            }

            String resultText = content.toString().trim();
            if (resultText.isEmpty()) {
                log.warn("百度ASR识别结果为空");
                return "[ASR识别完成，但未识别到语音内容]";
            }

            return resultText;

        } catch (Exception e) {
            log.error("解析百度ASR结果失败: {}", responseBody, e);
            return "[ASR识别失败，无法解析结果]";
        }
    }

    /**
     * 使用Whisper提取文字
     * Whisper是OpenAI开源的语音识别模型
     * 支持自部署Whisper服务或调用第三方Whisper API
     */
    private String extractWithWhisper(UserFileEntity file) throws Exception {
        if (whisperEndpoint == null || whisperEndpoint.isEmpty()) {
            throw new IllegalStateException("Whisper服务未配置，请设置 whisper.endpoint");
        }

        try {
            // 1. 读取音频文件
            byte[] audioBytes;
            try (InputStream input = fileStorageService.get(file.getFilePath())) {
                audioBytes = input.readAllBytes();
            }

            // 2. 调用Whisper API (使用OpenAI兼容格式)
            String url = whisperEndpoint + "/v1/audio/transcriptions";

            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            // 注意：这里使用RestTemplate的multipart方式上传文件
            // 实际实现需要根据具体的Whisper服务调整
            org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return file.getFileName();
                }
            });
            body.add("model", "whisper-1");
            body.add("language", "zh"); // 中文

            if (whisperApiKey != null && !whisperApiKey.isEmpty()) {
                headers.set("Authorization", "Bearer " + whisperApiKey);
            }

            HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            // 3. 解析返回结果
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseWhisperResult(response.getBody());
            } else {
                throw new RuntimeException("Whisper请求失败: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Whisper识别失败: fileId={}", file.getId(), e);
            throw new RuntimeException("Whisper识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析Whisper结果（OpenAI格式）
     */
    private String parseWhisperResult(String responseBody) {
        try {
            // Whisper返回格式: {"text": "识别的文字内容"}
            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("text")) {
                String text = root.get("text").asText().trim();
                if (text.isEmpty()) {
                    return "[ASR识别完成，但未识别到语音内容]";
                }
                return text;
            }

            log.warn("Whisper识别结果格式异常: {}", responseBody);
            return "[ASR识别失败，无法解析结果]";

        } catch (Exception e) {
            log.error("解析Whisper结果失败: {}", responseBody, e);
            return "[ASR识别失败，无法解析结果]";
        }
    }

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.AUDIO || fileType == FileType.VIDEO;
    }

    @Override
    public String getName() {
        return "AsrExtractor-" + asrProvider;
    }
}
