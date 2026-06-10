package io.yunxi.platform.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.yunxi.platform.file.FileType;
import io.yunxi.platform.file.FileUploadService;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.file.dto.FileSearchRequest;
import io.yunxi.platform.file.dto.FileSearchResult;
import io.yunxi.platform.file.dto.FileUploadRequest;
import io.yunxi.platform.file.dto.FileUploadResponse;
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

    @Autowired
    private FileUploadService fileUploadService;
    @Autowired
    private UserFileMapper userFileMapper;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "extractContent", required = false, defaultValue = "true") Boolean extractContent,
            @RequestParam(value = "vectorize", required = false, defaultValue = "true") Boolean vectorize,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {

        try {
            FileUploadRequest request = FileUploadRequest.builder()
                    .type(type).extractContent(extractContent).vectorize(vectorize).build();
            FileUploadResponse response = fileUploadService.uploadFile(userId, file, request);
            Map<String, Object> result = new HashMap<>();
            if (response.getSuccess()) { result.put("success", true); result.put("data", response); return ResponseEntity.ok(result); }
            else { result.put("success", false); result.put("message", response.getMessage()); return ResponseEntity.status(400).body(result); }
        } catch (Exception e) {
            log.error("文件上传异常", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "文件上传失败: " + e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listFiles(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId,
            @RequestParam(value = "type", required = false) FileType type) {
        try {
            List<UserFileEntity> files = type != null ? userFileMapper.listByUserIdAndFileType(userId, type) : userFileMapper.listByUserId(userId);
            return ResponseEntity.ok(Map.of("success", true, "data", files));
        } catch (Exception e) {
            log.error("获取文件列表失败", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "获取文件列表失败"));
        }
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> getFile(
            @PathVariable String fileId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {
        try {
            UserFileEntity file = userFileMapper.findById(fileId);
            if (file == null) return ResponseEntity.status(404).body(Map.of("success", false, "message", "文件不存在"));
            if (!file.getUserId().equals(userId)) return ResponseEntity.status(403).body(Map.of("success", false, "message", "无权访问"));
            return ResponseEntity.ok(Map.of("success", true, "data", file));
        } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("success", false, "message", "获取文件详情失败")); }
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String fileId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {
        try {
            UserFileEntity file = userFileMapper.findById(fileId);
            if (file == null) return ResponseEntity.status(404).body(Map.of("success", false, "message", "文件不存在"));
            if (!file.getUserId().equals(userId)) return ResponseEntity.status(403).body(Map.of("success", false, "message", "无权删除"));
            fileUploadService.deleteFile(fileId);
            return ResponseEntity.ok(Map.of("success", true, "message", "文件删除成功"));
        } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("success", false, "message", "删除文件失败")); }
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchFiles(
            @RequestBody FileSearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {
        try {
            request.setUserId(userId);
            List<FileSearchResult> results = fileUploadService.searchRelevantFiles(request);
            return ResponseEntity.ok(Map.of("success", true, "data", results));
        } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("success", false, "message", "检索失败")); }
    }
}
