package io.yunxi.platform.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * SQL配置加载器
 *
 * <p>
 * 从MyBatis mapper XML文件中加载SQL配置
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class SqlConfigLoader {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(SqlConfigLoader.class);
    /** 默认 Mapper XML 路径 */
    private static final String DEFAULT_MAPPER_PATH = "mapper/MemoryMapper.xml";

    /** SQL 映射表（ID -> SQL语句） */
    private final Map<String, String> sqlMap = new HashMap<>();

    /**
     * 从默认路径加载SQL配置
     */
    public static SqlConfigLoader load() {
        return load(DEFAULT_MAPPER_PATH);
    }

    /**
     * 从指定路径加载SQL配置
     *
     * @param mapperPath Mapper XML 文件路径
     * @return SQL配置加载器实例
     */
    public static SqlConfigLoader load(String mapperPath) {
        SqlConfigLoader loader = new SqlConfigLoader();
        loader.loadFromXml(mapperPath);
        return loader;
    }

    /**
     * 从XML文件加载SQL
     */
    private void loadFromXml(String mapperPath) {
        try {
            Resource resource = new ClassPathResource(mapperPath);
            if (!resource.exists()) {
                log.warn("SQL配置文件不存在: {}", mapperPath);
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                // 安全配置
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(is);

                NodeList sqlElements = document.getElementsByTagName("sql");

                for (int i = 0; i < sqlElements.getLength(); i++) {
                    Element sqlElement = (Element) sqlElements.item(i);
                    String id = sqlElement.getAttribute("id");
                    String sql = sqlElement.getTextContent().trim();

                    // 标准化SQL：移除多余空白
                    sql = normalizeSql(sql);
                    sqlMap.put(id, sql);
                }

                log.info("从 {} 加载了 {} 条SQL配置", mapperPath, sqlMap.size());
            }
        } catch (Exception e) {
            log.warn("加载SQL配置失败: {}", e.getMessage());
        }
    }

    /**
     * 标准化SQL字符串
     */
    private String normalizeSql(String sql) {
        // 移除多余的空白和换行
        return sql.replaceAll("\\s+", " ").trim();
    }

    /**
     * 获取SQL语句
     *
     * @param sqlId     SQL ID
     * @param tableName 表名（用于替换${tableName} 占位符）
     * @return SQL语句
     */
    public String getSql(String sqlId, String tableName) {
        String sql = sqlMap.get(sqlId);
        if (sql == null) {
            log.warn("未找到SQL配置: {}", sqlId);
            return null;
        }
        if (tableName != null) {
            sql = sql.replace("${tableName}", tableName);
        }
        return sql;
    }

    /**
     * 获取SQL语句（不替换表名）
     *
     * @param sqlId SQL ID
     * @return SQL语句
     */
    public String getSql(String sqlId) {
        return sqlMap.get(sqlId);
    }

    /**
     * 检查SQL是否存在
     *
     * @param sqlId SQL ID
     * @return 是否存在
     */
    public boolean hasSql(String sqlId) {
        return sqlMap.containsKey(sqlId);
    }

    /**
     * 获取所有SQL ID
     *
     * @return SQL ID 集合
     */
    public java.util.Set<String> getSqlIds() {
        return sqlMap.keySet();
    }
}
