package io.yunxi.platform.shared.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashScope Schema 工具类
 * <p>
 * 用于处理 DashScope API 不支持的 JSON Schema $ref 引用
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class DashScopeSchemaUtils {

    /**
     * 递归展开 JSON Schema 中的所有$ref 引用
     *
     * @param schema 包含 $defs和$ref的schema
     * @return 展开后的 schema，不包含$defs和$ref
     */
    public static Map<String, Object> inlineSchemaReferences(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }

        // 创建深拷贝，避免修改原始数据
        Map<String, Object> result = deepCopyMap(schema);

        // 步骤1: 递归收集所有$defs 定义
        Map<String, Object> allDefs = new HashMap<>();
        collectDefs(result, allDefs);

        // 步骤2: 递归展开所有$ref 引用（带循环引用检测）
        if (!allDefs.isEmpty()) {
            inlineReferencesRecursive(result, allDefs, new HashSet<>());
        }

        // 步骤3: 移除所有$defs 节点
        removeDefs(result);

        return result;
    }

    /**
     * 递归收集 schema 中所有的 $defs 定义
     *
     * @param obj     当前处理的对象（Map 或 List）
     * @param allDefs 收集到的所有定义映射
     */
    @SuppressWarnings("unchecked")
    private static void collectDefs(Object obj, Map<String, Object> allDefs) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;

            // 如果当前 map 包含 $defs，收集其内容
            if (map.containsKey("$defs")) {
                Object defsValue = map.get("$defs");

                if (defsValue instanceof Map) {
                    Map<String, Object> defs = (Map<String, Object>) defsValue;

                    // 深拷贝每个定义，避免后续修改影响原始数据
                    for (Map.Entry<String, Object> entry : defs.entrySet()) {
                        if (!allDefs.containsKey(entry.getKey())) {
                            if (entry.getValue() instanceof Map) {
                                allDefs.put(entry.getKey(), deepCopyMap((Map<String, Object>) entry.getValue()));
                            } else {
                                allDefs.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
            }

            // 递归处理所有元素
            for (Object value : map.values()) {
                collectDefs(value, allDefs);

            }
        } else if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                collectDefs(item, allDefs);

            }
        }
    }

    /**
     * 递归移除 schema 中所有的 $defs 节点
     *
     * @param obj 当前处理的对象（Map 或 List）
     */
    @SuppressWarnings("unchecked")
    private static void removeDefs(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;

            map.remove("$defs");

            // 递归处理所有元素
            for (Object value : map.values()) {
                removeDefs(value);

            }
        } else if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                removeDefs(item);

            }
        }
    }

    /**
     * 递归遍历 schema 并替换所有$ref 引用为实际定义
     * 
     * @param obj       当前处理的对象
     * @param defs      所有定义的映射
     * @param resolving 正在解析中的引用集合，用于检测循环引用
     */
    @SuppressWarnings("unchecked")
    private static void inlineReferencesRecursive(Object obj, Map<String, Object> defs, Set<String> resolving) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;

            // 检查是否是 $ref
            if (map.containsKey("$ref")) {
                String ref = (String) map.get("$ref");

                if (ref.startsWith("#/$defs/")) {
                    String defKey = ref.substring("#/$defs/".length());

                    // 检测循环引用：如果正在解析这个引用，说明存在循环
                    // 直接用简单的 object 类型替换，避免任何后续处理
                    if (resolving.contains(defKey)) {
                        map.clear();
                        map.put("type", "object");
                        map.put("description", "Circular reference resolved");
                        return;
                    }

                    Object definition = defs.get(defKey);
                    if (definition instanceof Map) {
                        // 标记正在解析此引用
                        resolving.add(defKey);

                        // 用定义替换整个map（深拷贝）
                        Map<String, Object> defCopy = deepCopyMap((Map<String, Object>) definition);
                        map.clear();
                        map.putAll(defCopy);

                        // 替换后可能还包含 $ref，需要继续处理
                        inlineReferencesRecursive(map, defs, resolving);

                        // 解析完成，移除标记
                        resolving.remove(defKey);
                    } else {
                        // 找不到定义，用object替代
                        map.clear();
                        map.put("type", "object");
                    }
                }
                return;
            }

            // 递归处理所有元素 - 使用迭代器避免并发修改
            List<String> keys = new ArrayList<>(map.keySet());

            for (String key : keys) {
                Object value = map.get(key);

                if (value instanceof Map || value instanceof List) {
                    inlineReferencesRecursive(value, defs, resolving);
                }
            }
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;

            for (Object item : list) {
                if (item instanceof Map || item instanceof List) {
                    inlineReferencesRecursive(item, defs, resolving);
                }
            }
        }
    }

    /**
     * 创建 map 的深拷贝，避免修改共享引用
     *
     * @param map 原始映射对象
     * @return 深拷贝后的映射对象
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> map) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();

            if (value instanceof Map) {
                copy.put(entry.getKey(), deepCopyMap((Map<String, Object>) value));
            } else if (value instanceof List) {
                copy.put(entry.getKey(), deepCopyList((List<?>) value));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    /**
     * 创建 list 的深拷贝
     *
     * @param list 原始列表对象
     * @return 深拷贝后的列表对象
     */
    @SuppressWarnings("unchecked")
    private static List<Object> deepCopyList(List<?> list) {
        List<Object> copy = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                copy.add(deepCopyMap((Map<String, Object>) item));
            } else if (item instanceof List) {
                copy.add(deepCopyList((List<?>) item));
            } else {
                copy.add(item);
            }
        }
        return copy;
    }
}
