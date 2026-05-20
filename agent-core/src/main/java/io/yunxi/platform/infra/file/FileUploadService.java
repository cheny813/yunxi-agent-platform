package io.yunxi.platform.infra.file;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.yunxi.platform.infra.config.ImageProcessingProperties;
import io.yunxi.platform.infra.file.dto.FileSearchRequest;
import io.yunxi.platform.infra.file.dto.FileSearchResult;
import io.yunxi.platform.infra.file.dto.FileUploadRequest;
import io.yunxi.platform.infra.file.dto.FileUploadResponse;
import io.yunxi.platform.infra.file.storage.FileStorageService;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.shared.mapper.UserFileMapper;

/**
 * 文件上传服务
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
public class FileUploadService {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    /** 文件存储服务 */
    @Autowired
    private FileStorageService fileStorageService;

    /** 文件内容提取服务 */
    @Autowired
    private FileContentExtractionService contentExtractionService;

    /** 文件向量化服务 */
    @Autowired
    private FileVectorService vectorService;

    /** 用户文件 Mapper */
    @Autowired
    private UserFileMapper userFileMapper;

    /** 图像特征处理服务 */
    @Autowired
    private ObjectProvider<ImageFeatureProcessingService> imageFeatureProcessingServiceProvider;

    /** 图像处理配置属性 */
    @Autowired
    private ObjectProvider<ImageProcessingProperties> imageProcessingPropertiesProvider;

    /** 是否启用图像特征提取 */
    @Value("${file-upload.image-feature.enabled:false}")
    private boolean imageFeatureEnabled;

    /** 是否启用人脸识别 */
    @Value("${file-upload.face-recognition.enabled:false}")
    private boolean faceRecognitionEnabled;

    /**
     * 上传文件
     *
     * @param userId  用户ID
     * @param file    文件
     * @param request 上传请求
     * @return 上传响应
     */
    public FileUploadResponse uploadFile(String userId, MultipartFile file, FileUploadRequest request) {
        try {
            // 1. 验证文件
            validateFile(file);

            // 2. 检测文件类型
            FileType fileType = detectFileType(file.getOriginalFilename(), request.getType());

            // 3. 保存文件
            String filePath = fileStorageService.save(userId, file);

            // 3.5 确定图像处理模式
            String processingMode = null;
            String imageType = null;
            if (fileType == FileType.IMAGE) {
                // 优先使用前端传递的处理模式
                processingMode = determineProcessingMode(
                        file.getOriginalFilename(),
                        request.getProcessingMode());

                // 优先使用前端传递的图像类型
                imageType = determineImageType(
                        file.getOriginalFilename(),
                        processingMode,
                        request.getImageType());

                log.info("图像处理模式: fileName={}, mode={}, imageType={}, source={}",
                        file.getOriginalFilename(), processingMode, imageType,
                        (request.getProcessingMode() != null) ? "frontend" : "auto");
            }

            // 4. 创建文件记录
            UserFileEntity fileEntity = new UserFileEntity();
            fileEntity.setId(java.util.UUID.randomUUID().toString());
            fileEntity.setUserId(userId);
            fileEntity.setFileName(file.getOriginalFilename());
            fileEntity.setFileType(fileType);
            fileEntity.setFilePath(filePath);
            fileEntity.setFileSize(file.getSize());
            fileEntity.setMimeType(file.getContentType());
            fileEntity.setContentExtracted(false);
            fileEntity.setVectorized(false);
            fileEntity.setProcessingMode(processingMode);
            fileEntity.setImageType(imageType);
            fileEntity.setCreatedAt(LocalDateTime.now());
            fileEntity.setUpdatedAt(LocalDateTime.now());

            // 5. 保存到数据库
            userFileMapper.save(fileEntity);

            log.info("文件上传成功: userId={}, fileId={}, fileName={}, type={}",
                    userId, fileEntity.getId(), file.getOriginalFilename(), fileType);

            // 6. 异步处理：内容提取和向量化
            if (request.getExtractContent()) {
                extractContentAsync(fileEntity);
            }

            // 7. 构建响应
            FileUploadResponse response = new FileUploadResponse();
            response.setId(fileEntity.getId());
            response.setFileName(fileEntity.getFileName());
            response.setFileType(fileType);
            response.setFileSize(fileEntity.getFileSize());
            response.setFilePath(fileEntity.getFilePath());
            response.setContentExtracted(false);
            response.setVectorized(false);
            response.setUploadTime(fileEntity.getCreatedAt());
            response.setMessage("文件上传成功，正在后台处理内容提取...");
            response.setSuccess(true);
            return response;

        } catch (Exception e) {
            log.error("文件上传失败: userId={}, fileName={}",
                    userId, file.getOriginalFilename(), e);

            FileUploadResponse response = new FileUploadResponse();
            response.setMessage("文件上传失败: " + e.getMessage());
            response.setSuccess(false);
            return response;
        }
    }

    /**
     * 异步提取文件内容
     */
    @Async("asyncExecutor")
    public void extractContentAsync(UserFileEntity file) {
        try {
            log.info("开始异步提取文件内容: fileId={}, fileType={}, processingMode={}",
                    file.getId(), file.getFileType(), file.getProcessingMode());

            // 对于图片文件，根据处理模式选择不同处理方式
            if (file.getFileType() == FileType.IMAGE) {
                processImageFile(file);
                return;
            }

            // 其他文件类型的处理（音频、视频、文档）
            processNonImageFile(file);

        } catch (Exception e) {
            log.error("异步提取文件内容失败: fileId={}", file.getId(), e);
        }
    }

    /**
     * 处理图片文件 - 根据处理模式选择OCR或特征提取
     */
    private void processImageFile(UserFileEntity file) {
        String processingMode = file.getProcessingMode();

        if ("feature".equals(processingMode)) {
            // 特征提取模式：人脸识别、工装识别等
            processImageFeature(file);
        } else {
            // OCR模式：文字识别
            processImageOcr(file);
        }
    }

    /**
     * 处理图像特征提取
     */
    private void processImageFeature(UserFileEntity file) {
        if (imageFeatureProcessingServiceProvider.getIfAvailable() == null) {
            log.warn("图像特征提取服务未启用，跳过处理: fileId={}", file.getId());
            return;
        }

        try {
            // 调用图像特征提取服务
            imageFeatureProcessingServiceProvider.getIfAvailable().processImageFeature(file);

            log.info("图像特征提取完成: fileId={}, imageType={}",
                    file.getId(), file.getImageType());

        } catch (Exception e) {
            log.error("图像特征提取失败: fileId={}", file.getId(), e);
        }
    }

    /**
     * 处理图像OCR（文字识别）
     */
    private void processImageOcr(UserFileEntity file) {
        try {
            // 1. 检查是否支持该文件类型
            if (!contentExtractionService.supports(file.getFileType())) {
                log.warn("不支持提取该文件类型的内容: fileId={}, fileType={}",
                        file.getId(), file.getFileType());
                return;
            }

            // 2. 提取内容
            String content = contentExtractionService.extractContent(file);

            if (content == null || content.trim().isEmpty()) {
                log.warn("提取的文件内容为空: fileId={}", file.getId());
                return;
            }

            // 3. 更新文件记录
            file.setContent(content);
            file.setContentExtracted(true);
            file.setUpdatedAt(java.time.LocalDateTime.now());
            userFileMapper.update(file);

            // 4. 向量化内容
            vectorService.saveFileVector(file, content);

            // 5. 更新向量化状态
            file.setVectorized(true);
            file.setUpdatedAt(java.time.LocalDateTime.now());
            userFileMapper.update(file);

            log.info("OCR内容提取和向量化完成: fileId={}, contentLength={}",
                    file.getId(), content.length());

        } catch (Exception e) {
            log.error("OCR处理失败: fileId={}", file.getId(), e);
        }
    }

    /**
     * 处理非图片文件（音频、视频、文档）
     */
    private void processNonImageFile(UserFileEntity file) {
        try {
            // 1. 检查是否支持该文件类型
            if (!contentExtractionService.supports(file.getFileType())) {
                log.warn("不支持提取该文件类型的内容: fileId={}, fileType={}",
                        file.getId(), file.getFileType());
                return;
            }

            // 2. 提取内容
            String content = contentExtractionService.extractContent(file);

            if (content == null || content.trim().isEmpty()) {
                log.warn("提取的文件内容为空: fileId={}", file.getId());
                return;
            }

            // 3. 更新文件记录
            file.setContent(content);
            file.setContentExtracted(true);
            file.setUpdatedAt(java.time.LocalDateTime.now());
            userFileMapper.update(file);

            // 4. 向量化内容
            vectorService.saveFileVector(file, content);

            // 5. 更新向量化状态
            file.setVectorized(true);
            file.setUpdatedAt(java.time.LocalDateTime.now());
            userFileMapper.update(file);

            log.info("文件内容提取和向量化完成: fileId={}, contentLength={}",
                    file.getId(), content.length());

        } catch (Exception e) {
            log.error("文件内容提取失败: fileId={}", file.getId(), e);
        }
    }

    /**
     * 验证文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 检查文件类型
        FileType fileType = FileType.fromExtension(fileName);
        if (fileType == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileName);
        }
    }

    /**
     * 检测文件类型
     */
    private FileType detectFileType(String fileName, String requestedType) {
        // 如果指定了类型，优先使用指定的类型
        if (requestedType != null && !requestedType.isEmpty()) {
            try {
                return FileType.valueOf(requestedType.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("指定的文件类型无效: {}, 将自动检测", requestedType);
            }
        }

        // 自动检测
        return FileType.fromExtension(fileName);
    }

    /**
     * 删除文件
     *
     * @param fileId 文件ID
     */
    public void deleteFile(String fileId) {
        try {
            // 1. 查询文件信息
            UserFileEntity file = userFileMapper.findById(fileId);
            if (file == null) {
                log.warn("文件不存在: fileId={}", fileId);
                return;
            }

            // 2. 删除向量
            vectorService.deleteFileVector(fileId);

            // 3. 删除数据库记录
            userFileMapper.deleteById(fileId);

            // 4. 删除物理文件
            fileStorageService.delete(file.getFilePath());

            log.info("文件删除成功: fileId={}", fileId);

        } catch (Exception e) {
            log.error("文件删除失败: fileId={}", fileId, e);
            throw new RuntimeException("文件删除失败", e);
        }
    }

    /**
     * 检索相关文件内容（RAG）
     *
     * @param request 检索请求
     * @return 检索结果列表
     */
    public List<FileSearchResult> searchRelevantFiles(FileSearchRequest request) {
        return vectorService.searchRelevantFiles(request);
    }

    /**
     * 确定图像处理模式
     *
     * @param fileName     文件名
     * @param frontendMode 前端传递的处理模式（可为空）
     * @return 处理模式 (ocr/feature)
     */
    private String determineProcessingMode(String fileName, String frontendMode) {
        // 1. 优先使用前端传递的处理模式
        if (frontendMode != null && !frontendMode.trim().isEmpty()) {
            String mode = frontendMode.trim().toLowerCase();

            // 验证模式有效性
            if (mode.equals("ocr") || mode.equals("feature") || mode.equals("auto")) {
                log.info("使用前端传递的处理模式: {}", mode);
                return mode;
            } else {
                log.warn("前端传递的处理模式无效: {}, 将使用自动判断", frontendMode);
            }
        }

        // 2. 如果前端没有传递模式，检查服务是否启用
        if (!imageFeatureEnabled && !faceRecognitionEnabled) {
            log.debug("图像特征提取服务未启用，使用OCR模式");
            return "ocr";
        }

        // 3. 根据后端配置确定处理模式
        if (imageProcessingPropertiesProvider.getIfAvailable() != null) {
            String mode = imageProcessingPropertiesProvider.getIfAvailable().determineProcessingMode(fileName);
            log.debug("使用后端配置的处理模式: {}", mode);
            return mode;
        }

        // 4. 默认使用OCR
        log.debug("使用默认处理模式: ocr");
        return "ocr";
    }

    /**
     * 确定图像类型
     *
     * @param fileName          文件名
     * @param processingMode    处理模式
     * @param frontendImageType 前端传递的图像类型（可为空）
     * @return 图像类型 (face/uniform/document/general)
     */
    private String determineImageType(String fileName, String processingMode, String frontendImageType) {
        if (!"feature".equals(processingMode)) {
            return null;
        }

        // 1. 优先使用前端传递的图像类型
        if (frontendImageType != null && !frontendImageType.trim().isEmpty()) {
            String type = frontendImageType.trim().toLowerCase();

            // 验证图像类型有效性
            if (type.equals("face") || type.equals("uniform") ||
                    type.equals("document") || type.equals("general")) {
                log.info("使用前端传递的图像类型: {}", type);
                return type;
            } else {
                log.warn("前端传递的图像类型无效: {}, 将使用自动判断", frontendImageType);
            }
        }

        // 2. 根据文件名自动判断
        String lowerFileName = fileName.toLowerCase();

        // 根据文件名关键词判断图像类型
        if (lowerFileName.contains("face") || lowerFileName.contains("人脸") ||
                lowerFileName.contains("头像") || lowerFileName.contains("photo")) {
            return "face";
        }

        if (lowerFileName.contains("uniform") || lowerFileName.contains("工装") ||
                lowerFileName.contains("工作服") || lowerFileName.contains("制服")) {
            return "uniform";
        }

        if (lowerFileName.contains("document") || lowerFileName.contains("文档") ||
                lowerFileName.contains("证件") || lowerFileName.contains("证书")) {
            return "document";
        }

        return "general";
    }
}
