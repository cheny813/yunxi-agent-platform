package io.yunxi.platform.shared.codec;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 对象与数据库 JSON 文本列之间的通用映射基类。
 *
 * <p>绑定器取自 {@link CodecSpec#storage()}，与 Redis 侧使用同一套配置 —— 同一份数据无论落
 * 数据库还是落缓存，读写行为都应一致。</p>
 *
 * <p>子类只需声明目标类型，无须再自行配置绑定器。空字符串与 null 均视为空值，读回
 * {@code null}；序列化内容不附加类型信息。</p>
 *
 * @param <T> 映射的目标类型
 * @author yunxi-agent-platform
 */
public abstract class JsonTypeHandler<T> extends BaseTypeHandler<T> {

    /** 持久化绑定器：与其它数据面共用同一份配置 */
    protected static final ObjectMapper MAPPER = CodecSpec.storage();

    /**
     * 返回目标类型。
     *
     * @return 目标类型
     */
    protected abstract Class<T> targetType();

    /**
     * 目标类型为泛型容器时返回其类型引用，默认返回 null 表示使用 {@link #targetType()}。
     *
     * @return 泛型类型引用，无则返回 null
     */
    protected TypeReference<T> typeReference() {
        return null;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 列序列化失败: " + parameter.getClass().getName(), e);
        }
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private T parse(String json) throws SQLException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            TypeReference<T> reference = typeReference();
            if (reference != null) {
                return MAPPER.readValue(json, reference);
            }
            JavaType javaType = MAPPER.getTypeFactory().constructType(targetType());
            return MAPPER.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 列反序列化失败", e);
        }
    }
}
