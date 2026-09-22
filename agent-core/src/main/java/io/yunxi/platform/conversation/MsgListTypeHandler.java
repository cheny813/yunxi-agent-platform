package io.yunxi.platform.conversation;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.codec.JsonTypeHandler;

/**
 * {@code List<Msg>} 与数据库 JSON 文本列之间的映射。
 *
 * <p>本类只声明目标类型，编解码一律由 {@link JsonTypeHandler} 基类经统一编解码器完成。
 * 消息列表既可能落数据库也可能落缓存，两侧读写行为必须一致，故不在此处另行配置绑定器。</p>
 *
 * @author yunxi-agent-platform
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class MsgListTypeHandler extends JsonTypeHandler<List<Msg>> {

    @Override
    protected Class<List<Msg>> targetType() {
        return null;
    }

    @Override
    protected TypeReference<List<Msg>> typeReference() {
        return new TypeReference<>() {
        };
    }
}
