package io.yunxi.platform.infra.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import io.yunxi.platform.infra.file.dto.ImageFeatureResult;
import io.yunxi.platform.infra.file.extractor.ImageFeatureExtractor;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.shared.mapper.UserFileMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 图像特征处理服务
 *
 * <p>
 * 负责图像特征提取和向量化，支持人脸识别、工装识别等场景
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "file-upload.image-feature.enabled", havingValue = "true", matchIfMissing = false)
public class ImageFeatureProcessingService {

    /** 图像特征提取器列表 */
    @Autowired
    private List<ImageFeatureExtractor> featureExtractors;

    /** 文件向量化服务 */
    @Autowired
    private FileVectorService vectorService;

    /** 用户文件 Mapper */
    @Autowired
    private UserFileMapper userFileMapper;

    /**
     * 处理图像特征提取
     *
     * @param file 文件信息
     */
    public void processImageFeature(UserFileEntity file) {
        try {
            log.info("开始处理图像特征: fileId={}, imageType={}", file.getId(), file.getImageType());

            // 1. 查找合适的特征提取器
            ImageFeatureExtractor extractor = findExtractor(file.getImageType());
            if (extractor == null) {
                log.error("未找到合适的图像特征提取器: imageType={}", file.getImageType());
                return;
            }

            // 2. 提取特征
            ImageFeatureResult featureResult = extractor.extract(file);

            // 3. 更新文件记录
            file.setContentExtracted(true);
            file.setFeatureDimension(featureResult.getDimension());
            file.setFeatureModel(featureResult.getModelName());
            file.setUpdatedAt(LocalDateTime.now());
            userFileMapper.update(file);

            // 4. 存储特征向量到Milvus
            vectorService.saveImageFeatureVector(file, featureResult);

            // 5. 更新向量化状态
            file.setVectorized(true);
            file.setUpdatedAt(LocalDateTime.now());
            userFileMapper.update(file);

            log.info("图像特征处理完成: fileId={}, dimension={}, model={}",
                    file.getId(), featureResult.getDimension(), featureResult.getModelName());

        } catch (Exception e) {
            log.error("图像特征处理失败: fileId={}", file.getId(), e);
            throw new RuntimeException("图像特征处理失败", e);
        }
    }

    /**
     * 查找合适的特征提取器
     *
     * @param imageType 图像类型
     * @return 特征提取器
     */
    private ImageFeatureExtractor findExtractor(String imageType) {
        if (featureExtractors == null || featureExtractors.isEmpty()) {
            return null;
        }

        // 优先查找专门支持该图像类型的提取器
        for (ImageFeatureExtractor extractor : featureExtractors) {
            if (extractor.supports(imageType)) {
                return extractor;
            }
        }

        // 如果没有找到专门的提取器，使用第一个可用的
        return featureExtractors.get(0);
    }

    /**
     * 搜索相似图像
     *
     * @param userId    用户ID
     * @param imageType 图像类型
     * @param file      查询图像
     * @param topK      返回数量
     * @return 相似图像列表
     */
    public List<Map<String, Object>> searchSimilarImages(String userId, String imageType,
            UserFileEntity file, int topK) {
        try {
            // 1. 提取查询图像特征
            ImageFeatureExtractor extractor = findExtractor(imageType);
            if (extractor == null) {
                log.error("未找到合适的图像特征提取器: imageType={}", imageType);
                return List.of();
            }

            ImageFeatureResult featureResult = extractor.extract(file);

            // 2. 在Milvus中搜索相似图像
            return vectorService.searchSimilarImageFeatures(userId, imageType,
                    featureResult.getFeatureVector(), topK);

        } catch (Exception e) {
            log.error("搜索相似图像失败: userId={}, imageType={}", userId, imageType, e);
            return List.of();
        }
    }
}
