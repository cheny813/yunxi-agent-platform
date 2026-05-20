package io.yunxi.platform.infra.file.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储服务接口
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface FileStorageService {

    /**
     * 保存文件
     *
     * @param userId 用户ID
     * @param file   文件
     * @return 文件存储路径
     */
    String save(String userId, MultipartFile file) throws IOException;

    /**
     * 保存文件流
     *
     * @param userId   用户ID
     * @param fileName 文件名
     * @param input    文件流
     * @return 文件存储路径
     */
    String save(String userId, String fileName, InputStream input) throws IOException;

    /**
     * 获取文件
     *
     * @param filePath 文件路径
     * @return 文件输入流
     */
    InputStream get(String filePath) throws IOException;

    /**
     * 删除文件
     *
     * @param filePath 文件路径
     */
    void delete(String filePath) throws IOException;

    /**
     * 检查文件是否存在
     *
     * @param filePath 文件路径
     * @return 是否存在
     */
    boolean exists(String filePath);

    /**
     * 获取文件URL
     *
     * @param filePath 文件路径
     * @return 文件访问URL
     */
    String getUrl(String filePath);

    /**
     * 获取文件大小
     *
     * @param filePath 文件路径
     * @return 文件大小（字节）
     */
    long getSize(String filePath) throws IOException;
}
