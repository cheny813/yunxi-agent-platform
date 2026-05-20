package io.yunxi.platform.infra.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.yunxi.platform.infra.file.FileType;
import io.yunxi.platform.infra.file.FileUploadService;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.infra.file.dto.FileSearchRequest;
import io.yunxi.platform.infra.file.dto.FileSearchResult;
import io.yunxi.platform.infra.file.dto.FileUploadRequest;
import io.yunxi.platform.infra.file.dto.FileUploadResponse;
import io.yunxi.platform.shared.mapper.UserFileMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件上传管理Controller
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    /** 文件上传服务 */
    @Autowired
    private FileUploadService fileUploadService;

    /** 用户文件 Mapper */
    @Autowired
    private UserFileMapper userFileMapper;

    /**
     * 上传文件
     *
     * @param file           上传的文件
     * @param type           文件类型
     * @param extractContent 是否提取内容
     * @param vectorize      是否向量化
     * @param userId         用户ID
     * @return 上传结果
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "extractContent", required = false, defaultValue = "true") Boolean extractContent,
            @RequestParam(value = "vectorize", required = false, defaultValue = "true") Boolean vectorize,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {

        try {
            FileUploadRequest request = FileUploadRequest.builder()
                    .type(type)
                    .extractContent(extractContent)
                    .vectorize(vectorize)
                    .build();

            FileUploadResponse response = fileUploadService.uploadFile(userId, file, request);

            Map<String, Object> result = new HashMap<>();
            if (response.getSuccess()) {
                result.put("success", true);
                result.put("data", response);
                return ResponseEntity.ok(result);
            } else {
                result.put("success", false);
                result.put("message", response.getMessage());
                return ResponseEntity.status(400).body(result);
            }

        } catch (Exception e) {
            log.error("文件上传异常: userId={}, fileName={}",
                    userId, file.getOriginalFilename(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "文件上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 获取文件列表
     *
     * @param userId 用户ID
     * @param type   文件类型（可选）
     * @return 文件列表
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listFiles(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId,
            @RequestParam(value = "type", required = false) FileType type) {

        try {
            List<UserFileEntity> files;
            if (type != null) {
                files = userFileMapper.listByUserIdAndFileType(userId, type);
            } else {
                files = userFileMapper.listByUserId(userId);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", files);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("获取文件列表失败: userId={}, type={}", userId, type, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取文件列表失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 获取文件详情
     *
     * @param fileId 文件ID
     * @param userId 用户ID
     * @return 文件详情
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> getFile(
            @PathVariable String fileId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {

        try {
            UserFileEntity file = userFileMapper.findById(fileId);

            if (file == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "文件不存在");
                return ResponseEntity.status(404).body(result);
            }

            // 权限检查：用户只能访问自己的文件
            if (!file.getUserId().equals(userId)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "无权访问该文件");
                return ResponseEntity.status(403).body(result);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", file);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("获取文件详情失败: fileId={}", fileId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取文件详情失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 删除文件
     *
     * @param fileId 文件ID
     * @param userId 用户ID
     * @return 删除结果
     */
    @DeleteMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String fileId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {

        try {
            // 权限检查
            UserFileEntity file = userFileMapper.findById(fileId);
            if (file == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "文件不存在");
                return ResponseEntity.status(404).body(result);
            }

            if (!file.getUserId().equals(userId)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "无权删除该文件");
                return ResponseEntity.status(403).body(result);
            }

            fileUploadService.deleteFile(fileId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "文件删除成功");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("删除文件失败: fileId={}", fileId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "删除文件失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 检索文件内容（RAG）
     *
     * @param request 检索请求
     * @param userId  用户ID
     * @return 检索结果列表
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchFiles(
            @RequestBody FileSearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {

        try {
            // 权限检查：只能检索自己的文件
            request.setUserId(userId);

            List<FileSearchResult> results = fileUploadService.searchRelevantFiles(request);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", results);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("检索文件内容失败: userId={}, query={}",
                    userId, request.getQuery(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "检索文件内容失败: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}
