package io.yunxi.platform.shared.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.yunxi.platform.infra.file.FileType;
import io.yunxi.platform.shared.entity.UserFileEntity;

import java.util.List;

/**
 * 用户文件Mapper接口
 *
 * <p>
 * SQL语句配置在 resources/mapper/UserFileMapper.xml 中
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Mapper
public interface UserFileMapper {

    /**
     * 保存文件信息
     */
    int save(UserFileEntity file);

    /**
     * 根据ID查询文件
     */
    UserFileEntity findById(String id);

    /**
     * 根据用户ID查询文件列表
     */
    List<UserFileEntity> listByUserId(String userId);

    /**
     * 根据用户ID和文件类型查询文件列表
     */
    List<UserFileEntity> listByUserIdAndFileType(@Param("userId") String userId, @Param("fileType") FileType fileType);

    /**
     * 更新文件信息
     */
    int update(UserFileEntity file);

    /**
     * 删除文件
     */
    int deleteById(String id);

    /**
     * 根据用户ID删除所有文件
     */
    int deleteByUserId(String userId);

    /**
     * 统计用户文件数量
     */
    int countByUserId(String userId);

    /**
     * 统计用户文件总大小
     */
    long totalSizeByUserId(String userId);

    /**
     * 检查用户文件表是否存在
     *
     * @return 表是否存在
     */
    boolean checkUserFilesTableExists();

    /**
     * 创建用户文件表
     */
    void createUserFilesTable();
}
