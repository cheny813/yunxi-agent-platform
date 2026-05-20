package io.yunxi.platform.shared.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * MyBatis TypeHandler 用于处理 {@code List<Msg>} 类型的数据库映射
 * <p>
 * 负责在以下两种数据格式之间进行转换：
 * <ul>
 * <li>Java对象: {@code List<Msg>} - 消息列表</li>
 * <li>数据库: JSON字符串 - 存储在TEXT/VARCHAR字段</li>
 * </ul>
 * </p>
 * <p>
 * <b>使用场景</b>：
 * <ul>
 * <li>ConversationEntity.messages 字段映射</li>
 * <li>会话消息历史的持久化</li>
 * <li>AgentScope 消息对象的序列化/反序列化</li>
 * </ul>
 * </p>
 * <p>
 * <b>注意事项</b>：
 * <ul>
 * <li>使用Jackson ObjectMapper进行JSON转换</li>
 * <li>数据库字段类型为TEXT（支持大量历史消息）</li>
 * <li>空字符串或null返回null列表</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @see io.agentscope.core.message.Msg
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class MsgListTypeHandler extends BaseTypeHandler<List<Msg>> {

    /**
     * Jackson ObjectMapper，用于JSON序列化和反序列化
     * <p>
     * 使用单例模式，避免重复创建对象开销
     * </p>
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * List<Msg> 的类型引用
     * <p>
     * 用于在反序列化时指定目标类型
     * </p>
     */
    private static final TypeReference<List<Msg>> TYPE_REFERENCE = new TypeReference<List<Msg>>() {
    };

    /**
     * 设置PreparedStatement参数
     * <p>
     * 将 List<Msg> 对象序列化为JSON字符串并设置到PreparedStatement中
     * </p>
     *
     * @param ps        PreparedStatement对象
     * @param i         参数索引（从1开始）
     * @param parameter List<Msg>对象
     * @param jdbcType  JDBC类型（通常为VARCHAR）
     * @throws SQLException 序列化失败时抛出
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Msg> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            String json = objectMapper.writeValueAsString(parameter);
            ps.setString(i, json);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize List<Msg> to JSON", e);
        }
    }

    /**
     * 从ResultSet中根据列名获取List<Msg>对象
     * <p>
     * 从指定列名读取JSON字符串并反序列化为List<Msg>
     * </p>
     *
     * @param rs         ResultSet对象
     * @param columnName 列名
     * @return List<Msg>对象，如果列为null或空字符串则返回null
     * @throws SQLException 反序列化失败时抛出
     */
    @Override
    public List<Msg> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parseJson(json);
    }

    /**
     * 从ResultSet中根据列索引获取List<Msg>对象
     * <p>
     * 从指定列索引读取JSON字符串并反序列化为List<Msg>
     * </p>
     *
     * @param rs          ResultSet对象
     * @param columnIndex 列索引（从1开始）
     * @return List<Msg>对象，如果列为null或空字符串则返回null
     * @throws SQLException 反序列化失败时抛出
     */
    @Override
    public List<Msg> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parseJson(json);
    }

    /**
     * 从CallableStatement中根据列索引获取List<Msg>对象
     * <p>
     * 从存储过程的OUT参数读取JSON字符串并反序列化为List<Msg>
     * </p>
     *
     * @param cs          CallableStatement对象
     * @param columnIndex 列索引（从1开始）
     * @return List<Msg>对象，如果列为null或空字符串则返回null
     * @throws SQLException 反序列化失败时抛出
     */
    @Override
    public List<Msg> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parseJson(json);
    }

    /**
     * 解析JSON字符串为List<Msg>对象
     * <p>
     * 统一的JSON解析逻辑，处理null和空字符串情况
     * </p>
     *
     * @param json JSON字符串
     * @return List<Msg>对象，如果输入为null或空字符串则返回null
     * @throws SQLException JSON解析失败时抛出
     */
    private List<Msg> parseJson(String json) throws SQLException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TYPE_REFERENCE);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to deserialize JSON to List<Msg>: " + json, e);
        }
    }
}
